package com.thoughtcoding.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.security.Sandbox;
import com.thoughtcoding.memory.MemoryStore;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.skill.SkillRegistry;
import com.thoughtcoding.util.FileUtils;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文管理器：管理对话历史长度，防止 Token 超限。
 *
 * <p>采用四层压缩管线（顺序严格不可换，仿 Claude Code）：
 * <ol>
 *   <li><b>L3 toolResultBudget</b>：单条巨型工具结果落盘，上下文只留标记+预览（<b>必须最先</b>，趁全文还在）。</li>
 *   <li><b>L1 snipCompact</b>：消息条数超限时裁掉中段，保留头尾（带工具配对边界保护）。</li>
 *   <li><b>L2 microCompact</b>：仅最近 N 条工具结果保留全文，更旧的换一行占位。</li>
 *   <li><b>L4 compactHistory</b>：前三层跑完仍超 token 阈值时，落盘完整对话并用 LLM 摘要替换旧历史。</li>
 * </ol>
 * 最后统一经 {@link #sanitizeToolPairs} 兜底工具调用/结果配对，保证不触发模型 400。
 *
 * <p>贯穿全案的铁律：<b>改副本不改原历史</b>——入口深拷贝一次，各层只动副本，绝不修改调用方传入的 List。
 */
public class ContextManager {
    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private final AppConfig appConfig;
    private final SkillRegistry skillRegistry;
    private final MemoryStore memoryStore; // 记忆存储（可空 = 记忆功能关闭）

    // ── 四层管线参数（构造时从 config 读入，全部有默认值）──
    private int maxContextTokens = 48000;       // L4 触发阈值（估算 token）
    private int maxMessages = 50;               // L1 触发的消息数上限
    private int snipKeepHead = 3;               // L1 保留头部条数
    private int snipKeepTail = 20;              // L1 保留尾部条数
    private int keepRecentToolResults = 3;      // L2 保留最近工具结果全文条数
    private int maxToolResultBytes = 200000;    // L3 当轮工具结果聚合预算（UTF-8 字节）：这批总量超过才触发落盘
    private int perResultPersistBytes = 30000;  // L3 单块落盘阈值：触发后只落单条超过此值的结果
    private int l4KeepTail = 6;                 // L4 摘要后保留尾部条数

    private static final int PREVIEW_CHARS = 2000; // L3 落盘后保留的预览字符数

    // 熔断器：L4 摘要连续失败达到此次数后，本会话不再尝试摘要，避免每轮都白白调用 LLM（+延迟+日志噪音）。
    // 对齐 Claude Code MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES (autoCompact.ts:70)。失败为模型无关计数，可直接迁移。
    private static final int MAX_CONSECUTIVE_COMPACT_FAILURES = 3;
    private int consecutiveCompactFailures = 0; // L4 连续失败计数（摘要成功即清零）

    private static final Path TRANSCRIPT_DIR = Paths.get("transcripts");
    private static final Path PERSISTED_DIR = TRANSCRIPT_DIR.resolve("persisted");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ObjectMapper objectMapper;

    private OpenAiChatModel ChatModel;

    public ContextManager(AppConfig appConfig, SkillRegistry skillRegistry, MemoryStore memoryStore) {
        this.appConfig = appConfig;
        this.skillRegistry = skillRegistry;
        this.memoryStore = memoryStore;
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        loadConfiguration();
        initializeChatModel();
    }

    /**
     * 从 config 的 ai 段读入四层管线参数（缺省则用字段默认值）。
     */
    private void loadConfiguration() {
        AppConfig.AIConfig ai = appConfig != null ? appConfig.getAi() : null;
        if (ai == null) {
            return;
        }
        this.maxContextTokens = ai.getMaxContextTokens();
        this.maxMessages = ai.getMaxMessages();
        this.snipKeepHead = ai.getSnipKeepHead();
        this.snipKeepTail = ai.getSnipKeepTail();
        this.keepRecentToolResults = ai.getKeepRecentToolResults();
        this.maxToolResultBytes = this.maxContextTokens / 2;
        this.perResultPersistBytes = ai.getPerResultPersistBytes();
        this.l4KeepTail = ai.getL4KeepTail();
    }

    private void initializeChatModel() {
        try {
            AppConfig.ModelConfig modelConfig = appConfig.getModelConfig(appConfig.getDefaultModel());
            if (modelConfig != null) {
                this.ChatModel = createDeepSeekModel(modelConfig);
            }
        } catch (Exception e) {
            System.err.println("初始化模型失败: " + e.getMessage());
        }
    }

    /**
     * 构建 L4 摘要专用模型：与主模型同源。摘要输出长度由 config 的 maxTokens 决定（用户按自己的模型设定）。
     */
    private OpenAiChatModel createDeepSeekModel(AppConfig.ModelConfig config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseURL())
                .apiKey(config.getApiKey())
                .modelName(config.getName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /**
     * 获取适合发送给 AI 的上下文——四层压缩管线。
     *
     * <p>顺序严格不可换：L3(落盘) → L1(裁中段) → L2(旧结果占位) → L4(超阈值则摘要) → 配对兜底。
     * 入口统一深拷贝一次，后续各层只动副本，绝不修改传入的 {@code fullHistory}。
     *
     * @param fullHistory 完整的对话历史（只读，不会被修改）
     * @return 经过处理的历史（不超过限制、工具配对一致）
     */
    public List<ChatMessage> getContextForAI(List<ChatMessage> fullHistory) {
        if (fullHistory == null || fullHistory.isEmpty()) {
            return new ArrayList<>();
        }

        // 入口深拷贝一次，后续各层原地改这份副本，天然不碰入参
        List<ChatMessage> work = deepCopyAll(fullHistory);

        work = toolResultBudget(work);   // L3：当轮工具结果聚合超预算才落盘其中最大的几块（正常单次 read 不碰）
        work = snipCompact(work);        // L1：消息数超限裁中段（边界保护）
        work = microCompact(work);       // L2：最近 N 条全文，更旧的占位（跳过 L3 已落盘标记）

        if (estimateTotalTokens(work) > maxContextTokens) {
            work = compactHistory(work, fullHistory); // L4：LLM 摘要，返回新列表，只读 fullHistory
        }

        // 🔥 保证发给模型的历史工具调用/结果配对一致（防止各层裁剪导致孤立 id → 模型 400）
        List<ChatMessage> result = sanitizeToolPairs(work);

        logContextStatistics(fullHistory, result);
        return result;
    }

    /** 深拷贝整个历史（各层只动副本，不碰调用方入参）。 */
    private List<ChatMessage> deepCopyAll(List<ChatMessage> messages) {
        List<ChatMessage> copy = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            copy.add(new ChatMessage(m));
        }
        return copy;
    }

    // ────────────────────────────────────────────────────────────────────────
    // L3：当轮工具结果聚合预算落盘（对齐真实 CC per-message budget / s08 tool_result_budget）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * L3：只处理<b>当轮那批工具结果</b>——即历史尾部连续的一段 {@code role=tool} 消息
     * <b>双阈值</b>：这批总字节超过 {@link #maxToolResultBytes}（当轮聚合预算）才触发；
     * 触发后按大小从大到小，仅把单块超过 {@link #perResultPersistBytes} 的落盘到 {@link #PERSISTED_DIR}，
     * 直到批总量回落到预算内。正文换 {@code <persisted-output>} 标记 + 预览 + 可 read 重读的磁盘路径。
     *
     * <p><b>为何这样定</b>：正常单次 read 大文件（几十 KB）落在聚合预算内、根本不碰——这正是"agent 读得到
     * 文件全貌"的保证；只有一轮内 N 个并行工具（bash/grep/glob 等）合起来塞爆预算时，才落盘其中最大的几块。
     * 不区分新旧、不扫全历史（每轮 O(尾部批)），落一次即成标记、L2 随后跳过（见 {@link #isPersistedMarker}）。
     * 不动 role/toolCallId/toolName，保持配对；落盘失败降级保留原内容，绝不因落盘破坏管线。
     */
    private List<ChatMessage> toolResultBudget(List<ChatMessage> work) {
        // 取历史尾部连续的一段 role=tool 消息（当轮那批工具结果）
        List<ChatMessage> batch = new ArrayList<>();
        for (int i = work.size() - 1; i >= 0; i--) {
            ChatMessage m = work.get(i);
            if (m != null && m.isToolMessage()) {
                batch.add(m);
            } else {
                break; // 遇到非 tool 消息即批边界
            }
        }
        if (batch.isEmpty()) {
            return work;
        }

        // 批总字节（跳过已带落盘标记的，避免重复计入/重复落盘）
        long total = 0;
        for (ChatMessage m : batch) {
            String c = m.getContent();
            if (c != null && !isPersistedMarker(c)) {
                total += c.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        if (total <= maxToolResultBytes) {
            return work; // 当轮聚合未超预算 → 全部保留全文（单个大 read 落在这里）
        }

        // 超预算：按单块大小从大到小落盘，仅落超过单块阈值的，直到回落到预算内
        batch.sort((a, b) -> Integer.compare(
                byteLen(b.getContent()), byteLen(a.getContent())));
        for (ChatMessage m : batch) {
            if (total <= maxToolResultBytes) {
                break;
            }
            String content = m.getContent();
            if (content == null || isPersistedMarker(content)) {
                continue;
            }
            int bytes = byteLen(content);
            if (bytes <= perResultPersistBytes) {
                continue; // 单块太小不值得落盘（即便批超预算）
            }
            String marker = persistToolResult(m, content, bytes);
            if (marker != null) {
                m.setContent(marker);
                total -= bytes; // 预览很小，近似认为这块从预算里移除
            }
        }
        return work;
    }

    /** 内容的 UTF-8 字节长度（null 视作 0）。 */
    private int byteLen(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 把一条工具结果的全文落盘，返回 {@code <persisted-output>} 标记+预览；失败返回 null（调用方保留原文）。
     */
    private String persistToolResult(ChatMessage m, String content, int bytes) {
        try {
            String id = m.getToolCallId() != null ? m.getToolCallId() : m.getId();
            String tool = m.getToolName() != null ? m.getToolName() : "unknown";
            String sid = m.getSessionId() != null ? m.getSessionId() : "nosession";
            String fileName = safeName(sid) + "-" + safeName(id) + "-" + System.nanoTime() + ".txt";
            Path target = PERSISTED_DIR.resolve(fileName);

            FileUtils.writeFile(target, content);

            String preview = content.length() > PREVIEW_CHARS
                    ? content.substring(0, PREVIEW_CHARS) : content;
            return "<persisted-output path=\"" + target.toString().replace('\\', '/')
                    + "\" bytes=\"" + bytes + "\" tool=\"" + tool + "\">\n"
                    + preview
                    + "\n...(truncated, full content persisted to disk)\n</persisted-output>";
        } catch (Exception e) {
            log.warn("L3 工具结果落盘失败，保留原内容: {}", e.getMessage());
            return null;
        }
    }

    /** 把字符串净化为安全文件名片段。 */
    private String safeName(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ────────────────────────────────────────────────────────────────────────
    // L1：消息条数超限时裁中段
    // ────────────────────────────────────────────────────────────────────────

    /**
     * L1：消息数超过 {@link #maxMessages} 时，保留头部 {@link #snipKeepHead} 条 + 尾部 {@link #snipKeepTail} 条，
     * 中间换成一条 {@code [snipped N messages]} 占位（role=user）。切点带工具配对边界保护，避免拆散
     * assistant(toolCalls)↔tool 组——这是非破坏性对齐，与最终 {@link #sanitizeToolPairs}（破坏性兜底）分工互补。
     */
    private List<ChatMessage> snipCompact(List<ChatMessage> work) {
        int n = work.size();
        if (n <= maxMessages) {
            return work;
        }

        int headEnd = Math.min(snipKeepHead, n);
        int tailStart = Math.max(0, n - snipKeepTail);
        if (tailStart <= headEnd) {
            return work; // 头尾就已覆盖全部，无中段可裁
        }

        headEnd = adjustHeadEndForToolPairs(work, headEnd);
        tailStart = adjustTailStartForToolPairs(work, tailStart);
        if (tailStart <= headEnd) {
            return work; // 边界保护后无可裁中段
        }

        int snipped = tailStart - headEnd;
        String sessionId = work.get(0).getSessionId();

        List<ChatMessage> out = new ArrayList<>(headEnd + 1 + (n - tailStart));
        out.addAll(work.subList(0, headEnd));
        out.add(new ChatMessage("user", "[snipped " + snipped + " messages from conversation middle]", sessionId));
        out.addAll(work.subList(tailStart, n));
        return out;
    }

    /**
     * 头切点保护：若头部最后一条是「发起了工具调用的 assistant」，其工具结果在中段会成孤儿，
     * 故把 headEnd 前移到该 assistant 之前（整组推入被裁中段）。
     */
    private int adjustHeadEndForToolPairs(List<ChatMessage> work, int headEnd) {
        while (headEnd > 0 && work.get(headEnd - 1).hasToolCalls()) {
            headEnd--;
        }
        return headEnd;
    }

    /**
     * 尾切点保护：若 tailStart 落在 role=tool 上，其发起调用的 assistant 在中段会成孤儿，
     * 故把 tailStart 前移越过这些工具结果，直到落在非工具消息（通常即发起调用的 assistant）上，整组纳入尾部。
     */
    private int adjustTailStartForToolPairs(List<ChatMessage> work, int tailStart) {
        while (tailStart > 0 && work.get(tailStart).isToolMessage()) {
            tailStart--;
        }
        return tailStart;
    }

    // ────────────────────────────────────────────────────────────────────────
    // L2：旧工具结果占位
    // ────────────────────────────────────────────────────────────────────────

    /**
     * L2：仅保留最近 {@link #keepRecentToolResults} 条工具结果的全文，更旧的换成一行占位
     * （只截断内容，不删消息、不动 role/toolCallId，保持配对）。入参已是副本，原地改。
     * 已被 L3 落盘的旧结果（带 {@code <persisted-output>} 标记）跳过——保留其预览+磁盘路径，不覆盖成占位。
     */
    private List<ChatMessage> microCompact(List<ChatMessage> work) {
        List<ChatMessage> toolResults = collectToolResultMessages(work);
        if (toolResults.size() <= keepRecentToolResults) {
            return work;
        }

        // 压缩早期的工具结果（除了最后 keepRecentToolResults 条）——只截断内容，保持配对
        List<ChatMessage> toCompact = toolResults.subList(0, toolResults.size() - keepRecentToolResults);
        for (ChatMessage msg : toCompact) {
            String content = msg.getContent();
            if (content == null || content.length() <= 100) {
                continue;
            }
            if (isPersistedMarker(content)) {
                continue; // L3 已落盘，保留预览+磁盘路径，不覆盖
            }
            String toolName = (msg.isToolMessage() && msg.getToolName() != null)
                    ? msg.getToolName() : extractToolNameFromContent(content);
            msg.setContent(String.format("[Previous: used %s]", toolName));
        }

        return work;
    }

    /**
     * 收集历史中的工具结果消息（原生 role=tool，或旧会话的 role=system + "Tool '" 前缀），按出现顺序。
     * L2 与 L3 共用它，保证两者对"最近 N 条 / 更旧"的划分完全一致，不产生错位。
     */
    private List<ChatMessage> collectToolResultMessages(List<ChatMessage> work) {
        List<ChatMessage> toolResults = new ArrayList<>();
        for (ChatMessage msg : work) {
            if (msg == null) continue;
            if (msg.isToolMessage()) {
                toolResults.add(msg);
            }
        }
        return toolResults;
    }

    /** 是否为 L3 落盘后写入的 {@code <persisted-output>} 标记内容。 */
    private boolean isPersistedMarker(String content) {
        return content != null && content.startsWith("<persisted-output ");
    }

    // 从消息内容中提取工具名称
    // 格式示例: "Tool 'read_file' executed successfully ..." 或 "Tool execution failed: 'unknown_tool' not found."
    private String extractToolNameFromContent(String content) {
        try {
            int start = content.indexOf('\'');
            int end = content.indexOf('\'', start + 1);
            if (start != -1 && end != -1) {
                return content.substring(start + 1, end);
            }
            if (content.startsWith("Tool execution failed: ")) {
                String after = content.substring("Tool execution failed: ".length());
                int space = after.indexOf(' ');
                if (space > 0) {
                    return after.substring(0, space);
                }
                return "unknown";
            }
            return "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // L4：LLM 摘要（前三层跑完仍超阈值时）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * L4：把待摘要段（尾部 {@link #l4KeepTail} 条之前的全部）落盘完整原始对话 → LLM 摘要 →
     * 构造并返回<b>全新列表</b>（[摘要消息] + 尾部）。绝不修改 {@code fullHistory}（只读它取 sessionId、写 transcript）。
     * 模型不可用或摘要失败时放弃 L4，原样返回 {@code work}。
     */
    private List<ChatMessage> compactHistory(List<ChatMessage> work, List<ChatMessage> fullHistory) {
        if (ChatModel == null) {
            return work; // 模型不可用 → 放弃 L4
        }
        if (consecutiveCompactFailures >= MAX_CONSECUTIVE_COMPACT_FAILURES) {
            // 熔断：连续失败过多，本会话不再尝试摘要（前三层的本地裁剪仍在生效）
            log.warn("L4 摘要已连续失败 {} 次，触发熔断，本会话停止摘要尝试。", consecutiveCompactFailures);
            return work;
        }

        int n = work.size();
        int tailStart = adjustTailStartForToolPairs(work, Math.max(0, n - l4KeepTail));
        if (tailStart <= 0) {
            return work; // 没有可摘要的前段
        }

        // 摘要前先落盘完整原始对话，保留可恢复记录
        writeTranscript(fullHistory);

        List<ChatMessage> toSummarize = work.subList(0, tailStart);
        List<ChatMessage> tail = work.subList(tailStart, n);

        String summary;
        try {
            String prompt = "Summarize this conversation for continuity. Include: " +
                    "1) What was accomplished, 2) Current state, 3) Key decisions made. " +
                    "Be concise but preserve critical details (file paths, tool outcomes, pending TODOs).\n\n" +
                    truncateConversation(toSummarize);
            summary = callLlmForSummary(prompt);
        } catch (Exception e) {
            consecutiveCompactFailures++;
            log.warn("L4 摘要失败({}/{})，放弃本次摘要: {}",
                    consecutiveCompactFailures, MAX_CONSECUTIVE_COMPACT_FAILURES, e.getMessage());
            return work;
        }

        consecutiveCompactFailures = 0; // 摘要成功 → 清零熔断计数
        String sessionId = work.get(0).getSessionId();
        List<ChatMessage> out = new ArrayList<>(1 + tail.size());
        out.add(new ChatMessage("user", "[Conversation compressed.]\n\n" + summary, sessionId));
        out.addAll(tail);
        return out;
    }

    /** 摘要前把完整原始对话落盘到 transcripts/，失败仅告警不阻断。 */
    private void writeTranscript(List<ChatMessage> fullHistory) {
        try {
            String sid = fullHistory.get(0).getSessionId();
            String name = "session-" + safeName(sid != null ? sid : "nosession")
                    + "-" + LocalDateTime.now().format(TS_FMT) + ".json";
            FileUtils.writeFile(TRANSCRIPT_DIR.resolve(name), objectMapper.writeValueAsString(fullHistory));
        } catch (Exception e) {
            log.warn("L4 transcript 落盘失败（不阻断摘要）: {}", e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 系统提示构建（保持不变）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 构建固定的项目上下文消息（原生 function calling 的简短系统提示），每次 AI 调用注入。
     *
     * @return 系统消息，如果无法获取则返回 null
     */
    public ChatMessage buildProjectContextMessage() {
        try {
            String cwd = Sandbox.workspaceRoot().toString();
            if (cwd == null || cwd.isEmpty()) {
                return null;
            }
            return new ChatMessage("system", buildNativeSystemPrompt(cwd));
        } catch (Exception e) {
            log.warn("无法构建项目上下文: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 🔥 原生 function calling 的简短系统提示：只讲角色/语言/规则；
     * 不再罗列工具——工具的名称/说明/参数已由 ToolRegistry.getToolSpecifications() 原生注入给模型。
     */
    private String buildNativeSystemPrompt(String cwd) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 指令\n");
        sb.append("- 始终用中文回答用户的所有问题，解释与代码注释也用中文。\n");
        sb.append("- 你是一位资深编程助手，可调用工具完成任务。\n\n");

        sb.append("## 工作环境\n");
        sb.append("工作目录: ").append(cwd).append("\n");
        sb.append("路径支持：相对路径、绝对路径、~ 用户主目录、.. 上级目录。\n\n");
        sb.append("操作系统: ").append(System.getProperty("os.name")).append("\n");
        sb.append("\n");

        sb.append("## 规则\n");
        sb.append("1. 需要操作时直接调用系统提供的工具（其名称/说明/参数已由系统注入），不要把工具名或命令写进普通文本，也不要编造工具结果。\n");
        sb.append("2. 改动已有文件优先用 edit；新建/覆盖用 write；读文件用 read；跑命令或搜索内容用 bash。\n");
        sb.append("3. 只在确有需要时调用工具；纯咨询类问题直接用中文回答，不调用工具。\n");
        sb.append("4. 完成任务后用简洁自然的中文给出总结。\n");

        appendPlanModeInstructions(sb);
        appendSkillCatalog(sb);
        appendMemory(sb);
        return sb.toString();
    }

    /**
     * 计划模式（{@link PlanMode}）激活时追加的行为约束段。
     *
     * <p>放 system prompt 而非 {@code <system-reminder>} 尾注：模式约束需贯穿整轮研究行为，
     * 语义上属于系统级规则；且模式只在切换瞬间变化一次，之后前缀重新稳定，前缀缓存损失可控。
     * 与权限门的硬拒绝（write/edit/bash 白名单外一律 DENY）互为软硬两层——
     * 提示让模型主动配合，权限门兜住越界。
     */
    private void appendPlanModeInstructions(StringBuilder sb) {
        if (!com.thoughtcoding.security.PlanMode.isActive()) {
            return;
        }
        sb.append("\n## 当前模式：计划模式 (Plan Mode)\n");
        sb.append("- 你现在处于【计划模式】：只能研究与规划，禁止任何修改操作。"
                + "写文件、改文件、有副作用的命令都会被系统直接拒绝。\n");
        sb.append("- 允许的手段：read/glob 阅读代码、只读 shell 命令（git log/diff/status、ls、grep 等）、"
                + "todo_write 记录规划、skill 加载技能说明。\n");
        sb.append("- 充分研究后，输出完整的实施计划，包含：目标、分步方案、涉及文件、风险与权衡。\n");
        sb.append("- 计划输出后即停止，等待用户批准；用户批准后才会进入执行阶段。\n");
    }

    /** 技能目录（名称+简介）常驻注入 system prompt；完整正文由模型显式调用 skill 工具按需加载。 */
    private void appendSkillCatalog(StringBuilder sb) {
        if (skillRegistry != null && !skillRegistry.isEmpty()) {
            sb.append("\n## 可用技能 (Skills)\n");
            sb.append("以下技能可通过 skill 工具按需加载完整说明后使用：\n");
            sb.append(skillRegistry.catalog()).append("\n");
        }
    }

    /**
     * 记忆目录（名称+简介）常驻注入 system prompt，零额外 API 调用；相对稳定（仅 remember/dream 写入时才变）。
     *
     * <p>注意：本轮召回的<b>相关记忆正文</b>（每轮都不同）<b>不</b>放这里——它由
     * {@link #buildRecallReminder(String)} 包成 {@code <system-reminder>}，经方法参数沿
     * {@code AgentLoop → LangChainService.prepareMessages} 请求局部传递，注入到<b>消息列表尾部</b>（贴当前轮）。
     * 这样每轮易变的召回只动尾巴，不冲掉「system 前缀 + 历史」的前缀缓存
     * （仿 Claude Code 把易变上下文贴当前用户轮，而非塞进被缓存的 system 前缀）。
     */
    private void appendMemory(StringBuilder sb) {
        if (memoryStore != null && !memoryStore.isEmpty()) {
            sb.append("\n## 可用记忆 (Memories)\n");
            sb.append("以下是你长期记住的用户偏好/项目事实/反馈约定（跨会话保留）。\n");
            sb.append("对话中出现相关话题时，应优先遵守其中的用户偏好。\n");
            sb.append(memoryStore.index()).append("\n");
            sb.append("\n重要：这些记忆文件由记忆系统自动管理（轮次间自动抽取 remember、到达阈值自动整理 dream）。\n");
            sb.append("【禁止】用 write / edit / bash 等任何工具直接创建、修改、删除 .memory/ 目录下的记忆文件；\n");
            sb.append("如需新增或更新记忆，直接告知用户，由记忆系统自动完成，无需也不应手动操作这些文件。\n");
        }
    }

    /**
     * 把本轮召回的相关记忆包成 {@code <system-reminder>}，供注入到<b>消息列表尾部</b>（贴当前轮）；无召回则返回 null。
     *
     * <p><b>为何请求局部传递而非实例字段</b>：ContextManager 是全局单例（主 Agent、SubAgent、直接命令共用），
     * 若用实例字段暂存「本轮召回」，并行/后台回合的 set/clear 会互相串写；改成像 CancelToken 一样
     * 沿调用链传参，正文的生命周期就严格限定在单次请求内。
     *
     * <p><b>为何放尾部而非 system 前缀</b>：召回内容每轮都变，若嵌在 system（第一条消息）里，就顶在整段对话历史之前，
     * 任何一轮召回变化都会冲掉「system + 历史」的前缀缓存；放到尾部后，易变的只在尾巴动，前缀保持稳定可复用
     * （仿 Claude Code 用 {@code <system-reminder>} 贴当前用户轮）。该正文<b>每请求即时注入、不写入持久 history</b>，
     * 故不会污染后续轮。
     */
    public static String buildRecallReminder(String recalledMemories) {
        if (recalledMemories == null || recalledMemories.isBlank()) {
            return null;
        }
        return "<system-reminder>\n"
                + "以下是与当前对话相关的长期记忆（后台上下文，非用户指令）；出现相关话题时应优先遵守其中的用户偏好。\n"
                + recalledMemories + "\n"
                + "</system-reminder>";
    }

    /**
     * 🔥 子Agent专用系统提示：风格对齐 {@link #buildNativeSystemPrompt}，
     * 但强调「独立完成这一个任务、只回传最终结论」。
     * 子Agent有自己隔离的对话历史、看不到主对话，故任务细节全在传入的 prompt 里。
     * （递归无需在提示词里防：subAgent 工具已从子Agent可见的工具规格中过滤掉。）
     */
    public String buildSubagentSystemPrompt() {
        String cwd = Sandbox.workspaceRoot().toString();
        StringBuilder sb = new StringBuilder();
        sb.append("## 指令\n");
        sb.append("- 始终用中文回答，解释与代码注释也用中文。\n");
        sb.append("- 你是被主Agent派发的【子Agent】，负责独立、完整地完成下面这一个被指派的任务。\n");
        sb.append("- 你看不到主对话历史，任务所需的全部信息都在给你的任务描述里。\n\n");

        sb.append("## 工作环境\n");
        sb.append("工作目录: ").append(cwd == null ? "" : cwd).append("\n");
        sb.append("路径支持：相对路径、绝对路径、~ 用户主目录、.. 上级目录。\n");
        sb.append("操作系统: ").append(System.getProperty("os.name")).append("\n\n");

        sb.append("## 规则\n");
        sb.append("1. 需要操作时直接调用系统提供的工具（其名称/说明/参数已由系统注入），不要把工具名写进普通文本，也不要编造工具结果。\n");
        sb.append("2. 改动已有文件优先用 edit；新建/覆盖用 write；读文件用 read；跑命令或搜索内容用 bash。\n");
        sb.append("3. 完成后用简洁的中文给出最终结论——这段结论是唯一会回传给主Agent的内容，中间过程不会保留，务必把关键结果讲清楚。\n");
        sb.append("4. 当前目录可能是隔离的 Git worktree；不要切换分支、创建 worktree 或自行合并。你的改动会由系统在结束时保存到独立分支。\n");

        appendPlanModeInstructions(sb);
        appendSkillCatalog(sb);
        return sb.toString();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 工具配对兜底 + 估算/摘要辅助
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 🔥 保证发给模型的历史中工具调用/结果配对一致（无论各层如何裁剪）：
     *  - 丢弃没有对应 assistant 工具调用的孤立 role=tool 结果；
     *  - assistant 消息里剥掉没有对应结果的 toolCalls（非破坏性：修改副本，不动原始历史）。
     * 违反 "assistant 工具调用必须紧跟同 id 的 tool 结果" 会导致模型 400。
     */
    private List<ChatMessage> sanitizeToolPairs(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return history;
        }

        java.util.Set<String> resultIds = new java.util.HashSet<>();
        java.util.Set<String> callIds = new java.util.HashSet<>();
        for (ChatMessage m : history) {
            if (m.isToolMessage() && m.getToolCallId() != null) {
                resultIds.add(m.getToolCallId());
            }
            if (m.getToolCalls() != null) {
                for (com.thoughtcoding.model.ToolCallRef r : m.getToolCalls()) {
                    if (r.getId() != null) callIds.add(r.getId());
                }
            }
        }

        List<ChatMessage> out = new ArrayList<>(history.size());
        for (ChatMessage m : history) {
            if (m.isToolMessage()) {
                if (m.getToolCallId() == null || !callIds.contains(m.getToolCallId())) {
                    continue; // 孤立工具结果 → 丢弃
                }
                out.add(m);
            } else if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                List<com.thoughtcoding.model.ToolCallRef> kept = new ArrayList<>();
                for (com.thoughtcoding.model.ToolCallRef r : m.getToolCalls()) {
                    if (r.getId() != null && resultIds.contains(r.getId())) {
                        kept.add(r);
                    }
                }
                if (kept.size() == m.getToolCalls().size()) {
                    out.add(m); // 全部有结果，原样保留
                } else {
                    ChatMessage copy = new ChatMessage(m); // 非破坏性：改副本
                    copy.setToolCalls(kept.isEmpty() ? null : kept);
                    out.add(copy);
                }
            } else {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * 估算整段历史的 token 数（含 assistant 工具调用参数 arguments，否则阈值判断偏低）。
     */
    private int estimateTotalTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage m : messages) {
            total += estimateTokens(m.getContent());
            if (m.getToolCalls() != null) {
                for (com.thoughtcoding.model.ToolCallRef r : m.getToolCalls()) {
                    total += estimateTokens(r.getArguments());
                }
            }
        }
        return total;
    }

    /**
     * 估算文本的 Token 数量：中文 2 字符 ≈ 1 token，英文 4 字符 ≈ 1 token。
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int chineseChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (isChinese(c)) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        return (chineseChars / 2) + (otherChars / 4);
    }

    private boolean isChinese(char c) {
        return c >= 0x4E00 && c <= 0x9FA5;
    }

    /**
     * 输出上下文统计信息
     */
    private void logContextStatistics(List<ChatMessage> fullHistory, List<ChatMessage> managedHistory) {
        if (!log.isDebugEnabled()) {
            return;
        }
        int fullTokens = estimateTotalTokens(fullHistory);
        int managedTokens = estimateTotalTokens(managedHistory);

        if (fullHistory.size() != managedHistory.size() || fullTokens != managedTokens) {
            log.debug("📊 上下文管理统计:");
            log.debug("  完整历史: {} 条消息 (~{} tokens)", fullHistory.size(), fullTokens);
            log.debug("  发送历史: {} 条消息 (~{} tokens)", managedHistory.size(), managedTokens);
            log.debug("  节省: {} tokens ({}%)",
                    fullTokens - managedTokens,
                    (fullTokens - managedTokens) * 100 / Math.max(fullTokens, 1));
        }
    }

    /**
     * 将消息列表序列化为 JSON 字符串（用于传给 LLM 摘要）。
     */
    private String truncateConversation(List<ChatMessage> messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize messages", e);
        }
    }

    private String callLlmForSummary(String prompt) {
        ChatResponse response = ChatModel.chat(UserMessage.from(prompt));
        return response.aiMessage().text();
    }
}
