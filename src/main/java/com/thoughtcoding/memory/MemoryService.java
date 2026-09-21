package com.thoughtcoding.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆系统的 LLM 大脑：召回注入(recall) / 储存(remember) / 整理 dream(consolidate)。
 *
 * <p>三件事全部<b>由 LLM 驱动</b>（与 s09_memory.py 的 memory 设计对齐），且<b>不作为工具</b>——
 * 它们由 {@code AgentLoop} 生命周期编排，模型永远不会看到记忆工具。
 *
 * <ul>
 *   <li>{@link #recall}：取最近对话，让 LLM 从记忆索引里挑相关条目，把完整正文注入本轮 system prompt。</li>
 *   <li>{@link #remember}：每轮结束，让 LLM 从对话里抽取新记忆（对现有记忆去重后落盘）。</li>
 *   <li>{@link #dream}：记忆文件数达到阈值时，让 LLM 整合/合并/清理全部记忆。</li>
 * </ul>
 *
 * <p>使用<b>独立的同步模型实例</b>（仿 {@code ContextManager} 的 L4 摘要模型），不触碰
 * {@code LangChainService} 的共享流式状态——见 [[subagent-isolation-constraint]]。
 * 所有公开方法<b>永不抛出</b>：记忆失败绝不能中断对话。
 */
public class MemoryService {
    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private static final Pattern JSON_ARRAY = Pattern.compile("\\[.*\\]", Pattern.DOTALL);
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_-]+");
    private static final Pattern HAN_SEQUENCE = Pattern.compile("[\\p{IsHan}]+");
    private static final int DREAM_INPUT_CHARS = 14_000;

    private final MemoryStore store;
    private final boolean autoExtract;
    private final int consolidateThreshold;
    private final int maxPerTurnInjections;
    private final int maxInjectionChars;
    private final OpenAiChatModel model; // 独立同步模型（null = 模型不可用 → 记忆功能静默降级）
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MemoryService(AppConfig appConfig, MemoryStore store, AppConfig.MemoryConfig config) {
        this.store = store;
        this.autoExtract = config != null && config.isAutoExtract();
        this.consolidateThreshold = config != null ? config.getConsolidateThreshold() : 10;
        this.maxPerTurnInjections = config != null ? config.getMaxPerTurnInjections() : 5;
        this.maxInjectionChars = config != null ? config.getMaxInjectionChars() : 12_000;
        this.model = buildModel(appConfig);
    }

    /** 独立同步模型：与主模型同源。构建失败 → null（记忆功能静默降级，不阻塞主对话）。 */
    private OpenAiChatModel buildModel(AppConfig appConfig) {
        try {
            AppConfig.ModelConfig modelConfig = appConfig.getModelConfig(appConfig.getDefaultModel());
            if (modelConfig == null) {
                return null;
            }
            return OpenAiChatModel.builder()
                    .baseUrl(modelConfig.getBaseURL())
                    .apiKey(modelConfig.getApiKey())
                    .modelName(modelConfig.getName())
                    .temperature(modelConfig.getTemperature())
                    .maxTokens(modelConfig.getMaxTokens())
                    .logRequests(false)
                    .logResponses(false)
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 召回（recall）：选相关记忆，把完整正文注入本轮
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 从历史里挑最近几条用户消息作为上下文，让 LLM 从记忆目录里选出相关条目，
     * 返回其完整正文（包在 {@code <relevant_memories>} 里）。返回空串表示无可注入内容。
     * 空库直接返回空串（省一次无谓的模型往返）。任何失败退化为关键词匹配。
     */
    public String recall(List<ChatMessage> history) {
        if (store == null || store.isEmpty()) {
            return "";
        }
        String recent = recentUserText(history, 2000);
        if (recent.isBlank()) {
            return "";
        }

        String catalog = buildCatalog();
        String prompt = "给定下面的最近对话和记忆目录，挑选其中<b>明确相关</b>的记忆条目的索引号。"
                + "只返回一个 JSON 数组，例如 [0, 3]；都不相关则返回 []。"
                + "索引必须是记忆目录中真实存在的编号（范围 0 到 N-1，N 为目录条数），不要返回越界的索引。\n\n"
                + "最近对话:\n" + recent + "\n\n"
                + "记忆目录:\n" + catalog;

        List<Integer> selected = null;
        if (model != null) {
            try {
                selected = parseIndexArray(callLlm(prompt));
            } catch (Exception ignored) {
                selected = null;
            }
        }

        // 失败退化为关键词匹配（对 name+description）
        if (selected == null) {
            selected = keywordFallback(recent);
        }

        if (selected.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<relevant_memories>\n");
        int count = 0;
        int chars = 0;
        List<MemoryStore.Memory> memories = store.list();
        for (int idx : selected) {
            if (count >= maxPerTurnInjections) {
                break;
            }
            if (idx < 0 || idx >= memories.size()) {
                continue;
            }
            String body = memories.get(idx).body();
            int room = Math.max(0, maxInjectionChars - chars);
            if (room == 0) {
                break;
            }
            if (body.length() > room) {
                sb.append(body, 0, room).append("\n[记忆正文已按上下文预算截断]\n\n");
                chars += room;
            } else {
                sb.append(body).append("\n\n");
                chars += body.length();
            }
            count++;
        }
        sb.append("</relevant_memories>");
        return sb.toString().stripTrailing();
    }

    private String buildCatalog() {
        StringBuilder sb = new StringBuilder();
        List<MemoryStore.Memory> list = store.list();
        for (int i = 0; i < list.size(); i++) {
            MemoryStore.Memory m = list.get(i);
            sb.append(i).append(": ").append(m.name()).append(" — ").append(m.description()).append('\n');
        }
        return sb.toString();
    }

    private List<Integer> keywordFallback(String recent) {
        Set<String> keywords = new LinkedHashSet<>();
        String normalized = recent.toLowerCase();
        Matcher words = WORD.matcher(normalized);
        while (words.find()) {
            String word = words.group();
            if (word.length() > 3) {
                keywords.add(word);
            }
        }
        Matcher hanSequences = HAN_SEQUENCE.matcher(normalized);
        while (hanSequences.find()) {
            String sequence = hanSequences.group();
            for (int i = 0; i + 2 <= sequence.length(); i++) {
                keywords.add(sequence.substring(i, i + 2));
            }
        }
        List<Integer> selected = new ArrayList<>();
        List<MemoryStore.Memory> list = store.list();
        for (int i = 0; i < list.size() && selected.size() < maxPerTurnInjections; i++) {
            MemoryStore.Memory m = list.get(i);
            String hay = (m.name() + " " + m.description()).toLowerCase();
            for (String kw : keywords) {
                if (hay.contains(kw)) {
                    selected.add(i);
                    break;
                }
            }
        }
        return selected;
    }

    // ────────────────────────────────────────────────────────────────────────
    // 储存（remember）：每轮结束抽取新记忆
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 从最近对话抽取新记忆。autoExtract 关闭或模型不可用则直接跳过。
     * 让 LLM 对照现有记忆去重后返回 {@code [{name,type,description,body}]}；无新增返回 []。
     */
    public void remember(List<ChatMessage> history) {
        if (!autoExtract || model == null || store == null) {
            return;
        }
        String dialogue = recentDialogue(history, 4000);
        if (dialogue.isBlank()) {
            return;
        }

        String existing = existingCatalog();
        String prompt = "从这段对话中抽取用户偏好、约束或项目事实，整理成记忆。\n"
                + "返回一个 JSON 数组，每项含四个字段:\n"
                + "- name: 简短 kebab-case 标识（如 user-preference-tabs）\n"
                + "- type: user（用户偏好）/ feedback（反馈约定）/ project（项目事实）/ reference（外部指引）\n"
                + "- description: 一行摘要，供索引查找\n"
                + "- body: Markdown 全文细节\n"
                + "已被现有记忆覆盖的、或没有持久价值的内容不要抽取；没有新记忆时返回 []。\n\n"
                + "现有记忆:\n" + existing + "\n\n"
                + "对话:\n" + dialogue;

        try {
            String text = callLlm(prompt);
            List<Object> items = extractJsonArray(text);
            if (items == null || items.isEmpty()) {
                return;
            }
            int written = 0;
            for (Object itemObj : items) {
                if (!(itemObj instanceof java.util.Map<?, ?> map)) {
                    continue;
                }
                Object name = map.get("name");
                Object type = map.get("type");
                Object desc = map.get("description");
                Object body = map.get("body");
                if (name == null || name.toString().isBlank()) {
                    continue;
                }
                String descStr = desc == null ? "" : desc.toString().trim();
                String bodyStr = body == null ? "" : body.toString().trim();
                if (descStr.isEmpty() && bodyStr.isEmpty()) {
                    continue;
                }
                String typeStr = type == null ? "user" : type.toString().trim();
                if (store.write(name.toString().trim(), typeStr, descStr, bodyStr) != null) {
                    written++;
                }
            }
            if (written > 0) {
                System.out.println("\n[33m[Memory: extracted " + written + " new memories][0m");
            }
        } catch (Exception e) {
            log.warn("记忆抽取失败（不影响对话）: {}", e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 整理（dream）：记忆多了之后整合去重
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 记忆文件数超过阈值时，让 LLM 合并重复、清理过时、保留用户偏好。
     * 单批输入受字符预算限制，没交给模型的条目原样保留；目标压缩到阈值的一半左右。
     * 任何失败都静默，保留原记忆不丢。
     */
    public void dream() {
        if (store == null || model == null || store.isEmpty()) {
            return;
        }
        if (consolidateThreshold <= 0 || store.size() <= consolidateThreshold) {
            return;
        }

        List<MemoryStore.Memory> selectedForDream = new ArrayList<>();
        List<MemoryStore.Memory> untouched = new ArrayList<>();
        StringBuilder catalog = new StringBuilder();
        for (MemoryStore.Memory m : store.list()) {
            String rendered = renderForDream(m);
            if (catalog.length() + rendered.length() <= DREAM_INPUT_CHARS) {
                catalog.append(rendered);
                selectedForDream.add(m);
            } else {
                untouched.add(m);
            }
        }
        if (selectedForDream.size() < 2) {
            return;
        }
        int targetCount = Math.min(selectedForDream.size(), Math.max(1, consolidateThreshold / 2));
        String prompt = "整合下面这些记忆文件:\n"
                + "1. 重复的合并为一条\n"
                + "2. 删除过时/被推翻的记忆\n"
                + "3. 总数控制在 " + targetCount + " 条以内（若本来就少于 " + targetCount + " 条，保持不变即可）\n"
                + "4. 用户偏好（type=user）优先保留\n"
                + "返回一个 JSON 数组，每项 {name, type, description, body}。\n\n"
                + catalog;
        try {
            String text = callLlm(prompt);
            List<Object> items = extractJsonArray(text);
            if (items == null || items.isEmpty()) {
                return;
            }
            List<MemoryStore.Memory> next = new ArrayList<>();
            for (Object itemObj : items) {
                if (!(itemObj instanceof java.util.Map<?, ?> map)) {
                    continue;
                }
                Object name = map.get("name");
                Object type = map.get("type");
                Object desc = map.get("description");
                Object body = map.get("body");
                if (name == null || name.toString().isBlank()) {
                    continue;
                }
                String descStr = desc == null ? "" : desc.toString().trim();
                String bodyStr = body == null ? "" : body.toString().trim();
                if (descStr.isEmpty() && bodyStr.isEmpty()) {
                    continue;
                }
                String typeStr = type == null ? "user" : type.toString().trim();
                next.add(new MemoryStore.Memory("", name.toString().trim(), descStr, typeStr, bodyStr));
            }
            if (next.isEmpty()) {
                return;
            }
            next.addAll(untouched);
            int before = store.size();
            if (!store.replaceAll(next)) {
                return;
            }
            System.out.println("\n[33m[Memory: consolidated " + before + " → " + next.size() + " memories][0m");
        } catch (Exception e) {
            log.warn("记忆整合失败（保留原记忆，不影响对话）: {}", e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 辅助
    // ────────────────────────────────────────────────────────────────────────

    private String existingCatalog() {
        if (store == null || store.isEmpty()) {
            return "(无)";
        }
        StringBuilder sb = new StringBuilder();
        for (MemoryStore.Memory m : store.list()) {
            sb.append("- ").append(m.name()).append(": ").append(m.description()).append('\n');
        }
        return sb.toString();
    }

    static String recentUserText(List<ChatMessage> history, int maxChars) {
        if (history == null) {
            return "";
        }
        List<String> texts = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && texts.size() < 3; i--) {
            ChatMessage msg = history.get(i);
            if (msg != null && msg.isUserMessage()) {
                String c = msg.getContent();
                if (c != null && !c.isBlank()) {
                    texts.add(c);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = texts.size() - 1; i >= 0; i--) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(texts.get(i));
        }
        String joined = sb.toString();
        return keepTail(joined, maxChars);
    }

    static String recentDialogue(List<ChatMessage> history, int maxChars) {
        if (history == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        int from = Math.max(0, history.size() - 10);
        for (int i = from; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            if (msg == null) {
                continue;
            }
            if (msg.isToolMessage()) {
                continue; // 工具结果不参与抽取
            }
            String c = msg.getContent();
            if (c == null || c.isBlank()) {
                continue;
            }
            lines.add(msg.getRole() + ": " + c);
        }
        String joined = String.join("\n", lines);
        return keepTail(joined, maxChars);
    }

    private static String keepTail(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
    }

    private static String renderForDream(MemoryStore.Memory m) {
        return "## " + m.filename() + '\n'
                + "name: " + m.name() + '\n'
                + "description: " + m.description() + '\n'
                + m.body() + "\n\n";
    }

    /** 同步 LLM 调用，失败返回 null（调用方各自降级）。 */
    private String callLlm(String prompt) {
        try {
            ChatResponse response = model.chat(UserMessage.from(prompt));
            return response.aiMessage().text();
        } catch (Exception e) {
            log.warn("记忆模型调用失败: {}", e.getMessage());
            return null;
        }
    }

    /** 从响应里解析整数索引数组（recall 用）。 */
    private List<Integer> parseIndexArray(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = JSON_ARRAY.matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            java.util.List<?> arr = objectMapper.readValue(m.group(), java.util.List.class);
            List<Integer> out = new ArrayList<>();
            for (Object o : arr) {
                if (o instanceof Number n) {
                    out.add(n.intValue());
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从响应里解析对象数组（remember/dream 用）。 */
    @SuppressWarnings("unchecked")
    private List<Object> extractJsonArray(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = JSON_ARRAY.matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            Object parsed = objectMapper.readValue(m.group(), Object.class);
            return parsed instanceof java.util.List<?> list ? new ArrayList<>(list) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
