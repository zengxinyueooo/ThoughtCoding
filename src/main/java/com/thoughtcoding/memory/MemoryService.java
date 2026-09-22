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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆系统的 LLM 大脑：召回注入(recall) / 储存(remember) / 整理 dream(consolidate)。
 *
 * <p>三件事全部<b>由 LLM 驱动</b>（与 s09_memory.py 的 memory 设计对齐），且<b>不作为工具</b>——
 * 它们由 {@code AgentLoop} 生命周期编排，模型永远不会看到记忆工具。
 *
 * <ul>
 *   <li>{@link #recall}：取最近对话，本地 IDF 加权关键词打分挑相关条目（零 API 调用），把完整正文注入本轮消息尾部。</li>
 *   <li>{@link #remember}：每轮结束，让 LLM 从对话里抽取新记忆（对现有记忆去重后落盘）。</li>
 *   <li>{@link #dreamAsync}：记忆文件数达到阈值时在后台线程让 LLM 整合/合并/清理全部记忆。</li>
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

    /** 记忆信号标记：主模型在值得沉淀的轮次末尾输出，AgentLoop 据此门控 remember。 */
    public static final String MEMORY_SIGNAL = "<memory-signal/>";

    /** 小库短路阈值：条数不超过此值时 recall 跳过 LLM 挑选，直接全量注入（更便宜也更准）。 */
    private static final int SMALL_STORE_THRESHOLD = 3;

    private final MemoryStore store;
    private final boolean autoExtract;
    private final int consolidateThreshold;
    private final int maxIndexEntries;
    private final int maxPerTurnInjections;
    private final int maxInjectionChars;
    private final OpenAiChatModel model; // 独立同步模型（null = 模型不可用 → 记忆功能静默降级）
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** dream 进行中标记：整库替换期间 remember 跳过（避免并发写入被替换覆盖），也防止重复触发。 */
    private final AtomicBoolean dreaming = new AtomicBoolean(false);

    public MemoryService(AppConfig appConfig, MemoryStore store, AppConfig.MemoryConfig config) {
        this.store = store;
        this.autoExtract = config != null && config.isAutoExtract();
        this.consolidateThreshold = config != null ? config.getConsolidateThreshold() : 10;
        this.maxIndexEntries = config != null ? config.getMaxIndexEntries() : 200;
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
     * 从历史里取最近几条用户消息，用本地 IDF 加权关键词打分选出相关条目，
     * 返回其完整正文（包在 {@code <relevant_memories>} 里）。返回空串表示无可注入内容。
     * 空库直接返回空串；小库（≤{@value #SMALL_STORE_THRESHOLD} 条）短路全量注入；
     * 中大库走 {@link #selectByKeyword}（零 API 调用，体量论证见其注释）。
     */
    public String recall(List<ChatMessage> history) {
        if (store == null || store.isEmpty()) {
            return "";
        }
        String recent = recentUserText(history, 2000);
        if (recent.isBlank()) {
            return "";
        }

        // 单次快照，全程复用：dream 后台整库替换期间，目录/正文若各自取 list 会错位注入
        List<MemoryStore.Memory> memories = store.list();

        List<Integer> selected;
        if (memories.size() <= SMALL_STORE_THRESHOLD) {
            // 小库全量注入：条目这么少时全注比挑得更准，也省一次打分
            selected = new ArrayList<>();
            for (int i = 0; i < memories.size(); i++) {
                selected.add(i);
            }
            return renderSelected(selected, memories);
        }

        // 检索主路径：本地 IDF 加权关键词打分，零 API 调用。
        // 体量论证见 selectByKeyword：dream 阈值把库摁在 ~10 条，这个规模上
        // LLM 挑选的质量优势可忽略，而每轮一次的模型往返是纯经常性成本。
        selected = selectByKeyword(memories, recent);
        if (selected.isEmpty()) {
            return "";
        }
        return renderSelected(selected, memories);
    }

    /** 按索引渲染召回正文（条数/字符预算受限），是 recall 所有路径共用的出口。 */
    private String renderSelected(List<Integer> selected, List<MemoryStore.Memory> memories) {
        StringBuilder sb = new StringBuilder("<relevant_memories>\n");
        int count = 0;
        int chars = 0;
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

    /**
     * IDF 加权关键词打分（检索主路径，零 API 调用）。
     *
     * <p>近期对话分词（英文词 &gt;3 字符 + 中文二元组）；每个关键词按「出现在多少条记忆里」算
     * IDF（idf = ln(N/df)）——到处都出现的词（"我们/一个"类高频二元组）权重自然趋近 0，
     * 不需要停用词表。命中 name/description 记 2 倍权重（索引字段比正文更可信），命中正文记 1 倍。
     * 得分 &gt; 0 的按分降序取前 K（K 由 renderSelected 的 maxPerTurnInjections 截断）；
     * 全零说明无明确相关 → 返回空（coding agent 的记忆宁缺勿错：错误注入的偏好会直接写坏代码）。
     */
    static List<Integer> selectByKeyword(List<MemoryStore.Memory> memories, String recent) {
        Set<String> keywords = tokenize(recent);
        if (keywords.isEmpty()) {
            return List.of();
        }
        int n = memories.size();
        List<String> nameDesc = new ArrayList<>(n);
        List<String> bodies = new ArrayList<>(n);
        for (MemoryStore.Memory m : memories) {
            nameDesc.add((m.name() + " " + m.description()).toLowerCase());
            bodies.add(m.body() == null ? "" : m.body().toLowerCase());
        }

        // IDF：df = 含该词的记忆条数；df = N（词在每条里都有）时 idf = ln(1) = 0，无区分度
        Map<String, Double> idf = new LinkedHashMap<>();
        for (String kw : keywords) {
            int df = 0;
            for (int i = 0; i < n; i++) {
                if (nameDesc.get(i).contains(kw) || bodies.get(i).contains(kw)) {
                    df++;
                }
            }
            if (df > 0) {
                idf.put(kw, Math.log((double) n / df));
            }
        }
        if (idf.isEmpty()) {
            return List.of();
        }

        double[] scores = new double[n];
        for (Map.Entry<String, Double> e : idf.entrySet()) {
            String kw = e.getKey();
            double w = e.getValue();
            for (int i = 0; i < n; i++) {
                if (nameDesc.get(i).contains(kw)) {
                    scores[i] += 2 * w;
                } else if (bodies.get(i).contains(kw)) {
                    scores[i] += w;
                }
            }
        }

        List<Integer> ranked = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (scores[i] > 0) {
                ranked.add(i);
            }
        }
        ranked.sort((a, b) -> Double.compare(scores[b], scores[a]));
        return ranked;
    }

    /** 近期文本分词：英文/数字词（&gt;3 字符）+ 中文连续段的所有二元组。 */
    private static Set<String> tokenize(String text) {
        Set<String> keywords = new LinkedHashSet<>();
        String normalized = text.toLowerCase();
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
        return keywords;
    }

    // ────────────────────────────────────────────────────────────────────────
    // 储存（remember）：每轮结束抽取新记忆
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 从最近对话抽取新记忆。autoExtract 关闭或模型不可用则直接跳过；
     * dream 进行中跳过（整库替换期间的并发写入会被覆盖，不如丢弃这一轮）。
     * 让 LLM 对照现有记忆去重后返回 {@code [{name,type,description,body}]}；
     * 用户明确要求「忘记」的目标以 {@code {"delete": "<name或文件名>"}} 返回；无新增返回 []。
     */
    public void remember(List<ChatMessage> history) {
        if (!autoExtract || model == null || store == null) {
            return;
        }
        if (dreaming.get()) {
            log.debug("dream 进行中，跳过本轮记忆抽取");
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
                + "用户明确要求「记住/不要忘」的内容必须抽取；用户明确要求「忘记/删掉」的现有记忆，"
                + "以 {\"delete\": \"<对应记忆的name或文件名>\"} 单独一项返回。\n"
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
            int deleted = 0;
            for (Object itemObj : items) {
                if (!(itemObj instanceof java.util.Map<?, ?> map)) {
                    continue;
                }
                Object del = map.get("delete");
                if (del != null && !del.toString().isBlank()) {
                    if (store.delete(del.toString().trim())) {
                        deleted++;
                    }
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
            if (deleted > 0) {
                System.out.println("\n[33m[Memory: deleted " + deleted + " memories on user request][0m");
            }
        } catch (Exception e) {
            log.warn("记忆抽取失败（不影响对话）: {}", e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 整理（dream）：记忆多了之后整合去重
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 后台异步整理：轮末不再同步等待整库 LLM 调用（一次 14k 字符输入的往返会明显卡顿），
     * 整理完成前旧版本照常服务。{@code dreaming} 标记防止重复触发，也让并发的 remember 跳过
     * （整库替换期间的写入会被 {@code replaceAll} 覆盖，不如丢弃那一轮）。
     *
     * @param force    true 时跳过阈值检查（{@code /memory dream} 手动触发用）
     * @param notifier 整理完成（且有变更）时的回调（如 UI 提示），可为 null
     */
    public void dreamAsync(boolean force, java.util.function.Consumer<String> notifier) {
        if (store == null || model == null || store.isEmpty()) {
            return;
        }
        if (!force && (consolidateThreshold <= 0 || store.size() <= consolidateThreshold)) {
            return;
        }
        if (!dreaming.compareAndSet(false, true)) {
            return; // 已有整理在进行
        }
        Thread.ofVirtual().name("memory-dream").start(() -> {
            try {
                int before = store.size();
                if (doDream() && notifier != null) {
                    notifier.accept("🧠 记忆整理完成: " + before + " → " + store.size() + " 条");
                }
            } finally {
                dreaming.set(false);
            }
        });
    }

    /**
     * 整理的核心实现（在后台线程运行）：让 LLM 合并重复、清理过时、保留用户偏好。
     * 单批输入受字符预算限制，没交给模型的条目原样保留；目标压缩到阈值的一半左右。
     * 任何失败都静默，保留原记忆不丢。
     *
     * @return 是否实际发生了整库替换
     */
    private boolean doDream() {
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
            return false;
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
                return false;
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
                return false;
            }
            next.addAll(untouched);
            return store.replaceAll(next);
        } catch (Exception e) {
            log.warn("记忆整合失败（保留原记忆，不影响对话）: {}", e.getMessage());
            return false;
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
        List<MemoryStore.Memory> list = store.list();
        int limit = Math.min(list.size(), maxIndexEntries); // 与索引同一上限，控制 remember 提示词体积
        for (int i = 0; i < limit; i++) {
            MemoryStore.Memory m = list.get(i);
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
