package com.thoughtcoding.service;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文管理器
 * 负责管理对话历史的长度，防止 Token 超限
 *
 * 支持两种策略：
 * 1. 滑动窗口：保留最近 N 轮对话
 * 2. Token 控制：根据 Token 数量动态截断
 */
public class ContextManager {
    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private final AppConfig appConfig;

    // 默认配置
    private static final int DEFAULT_MAX_HISTORY_TURNS = 10;  // 保留10轮（20条消息）
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 3000;  // 为历史预留3000 tokens
    private static final int DEFAULT_RESERVE_TOKENS = 1000;  // 为响应预留1000 tokens

    // 策略枚举
    public enum Strategy {
        SLIDING_WINDOW,  // 滑动窗口
        TOKEN_BASED,     // 基于 Token
        HYBRID           // 混合策略
    }

    private Strategy strategy = Strategy.TOKEN_BASED;  // 默认使用 Token 控制
    private int maxHistoryTurns = DEFAULT_MAX_HISTORY_TURNS;
    private int maxContextTokens = DEFAULT_MAX_CONTEXT_TOKENS;

    public ContextManager(AppConfig appConfig) {
        this.appConfig = appConfig;
        loadConfiguration();
    }

    /**
     * 从配置加载参数
     */
    private void loadConfiguration() {
        // TODO: 从 config.yaml 读取配置
        // 目前使用默认值
        // 🔥 移除初始化日志，保持输出简洁
    }

    /**
     * 获取适合发送给 AI 的上下文
     * 应用历史长度限制策略
     *
     * @param fullHistory 完整的对话历史
     * @return 经过处理的历史（不超过限制）
     */
    public List<ChatMessage> getContextForAI(List<ChatMessage> fullHistory) {
        if (fullHistory == null || fullHistory.isEmpty()) {
            return new ArrayList<>();
        }

        List<ChatMessage> result;

        switch (strategy) {
            case SLIDING_WINDOW:
                result = applySlidingWindow(fullHistory);
                break;
            case TOKEN_BASED:
                result = applyTokenLimit(fullHistory);
                break;
            case HYBRID:
                result = applyHybridStrategy(fullHistory);
                break;
            default:
                result = fullHistory;
        }

        // 输出统计信息
        logContextStatistics(fullHistory, result);

        return result;
    }

    /**
     * 🔥 新增：构建固定的项目上下文消息
     * 这个上下文会在每次 AI 调用时注入，永远不会被截断
     *
     * @return 项目上下文系统消息，如果无法获取则返回 null
     */
    public ChatMessage buildProjectContextMessage() {
        try {
            String cwd = System.getProperty("user.dir");
            if (cwd == null || cwd.isEmpty()) {
                return null;
            }

            StringBuilder context = new StringBuilder();
            context.append("## 📋 重要指令\n\n");
            context.append("⚠️ **你必须始终使用中文回答用户的所有问题！**\n");
            context.append("⚠️ **所有的解释、说明、代码注释都必须使用中文！**\n\n");

            context.append("## 🎯 你的角色定位\n\n");
            context.append("你是一位资深的编程助手，类似 Claude Code。你的核心特点：\n\n");
            context.append("1. **主动思考和分析** - 不要直接执行，先检查现状\n");
            context.append("2. **提供多个选项** - 当遇到已存在的文件或复杂情况时，列出3-4个选项让用户选择\n");
            context.append("3. **智能决策** - 检查文件是否存在、分析当前项目状态\n");
            context.append("4. **友好交互** - 清晰解释每一步，让用户感觉有一位专家在帮忙\n\n");

            context.append("## 🏠 当前工作环境\n\n");
            context.append("工作目录: ").append(cwd).append("\n\n");

            context.append("**路径支持：**\n");
            context.append("- **相对路径**：`sessions/test.json` - 相对于当前工作目录\n");
            context.append("- **绝对路径**：`/Users/zengxinyue/Desktop/test.txt` - 可以访问任何目录\n");
            context.append("- **用户主目录**：`~/Desktop/test.txt` - 使用 ~ 代表用户主目录\n");
            context.append("- **上级目录**：`../other_project/file.txt` - 可以访问父目录\n\n");

            context.append("## 💡 智能工作流程（重要！）\n\n");

            context.append("### 📁 当用户要求创建/编写代码文件时：\n\n");
            context.append("**重要：直接生成代码，不要先检查文件是否存在！**\n\n");
            context.append("当用户说\"写一个Java代码\"、\"创建HelloWorld程序\"等时：\n\n");
            context.append("**步骤 1：直接创建文件并生成代码**\n");
            context.append("1. 简短说明你要创建什么\n");
            context.append("2. 使用 `⏺ Write(文件名)` 标记\n");
            context.append("3. 在代码块中展示完整代码\n");
            context.append("4. 停止输出，等待系统执行\n\n");

            context.append("### 🗑️ 当用户要求删除文件/目录时：\n\n");
            context.append("**重要：直接执行删除命令，不需要先检查目录内容！**\n\n");

            context.append("**智能路径识别规则：**\n");
            context.append("- UUID 格式的 JSON 文件（如 41e6f846-b709-4511-8fde-86cfe0e86809.json）→ 在 sessions 目录下\n");
            context.append("- 代码文件（.java/.py/.js 等）→ 通常在当前目录\n");
            context.append("- 明确指定路径的 → 使用指定路径\n\n");

            context.append("**示例1：删除 session 文件**\n");
            context.append("用户说：\"删除 41e6f846-b709-4511-8fde-86cfe0e86809.json\"\n");
            context.append("你应该：好的，我来删除这个 session 文件：\n\n");
            context.append("⏺ Bash(rm sessions/41e6f846-b709-4511-8fde-86cfe0e86809.json)\n\n");

            context.append("**示例2：删除目录下所有文件**\n");
            context.append("用户说：\"删除sessions下的所有文件\"\n");
            context.append("你应该：好的，我来删除 sessions 目录下的所有文件：\n\n");
            context.append("⏺ Bash(rm sessions/*)\n\n");

            context.append("**错误示例（不要这样做）：**\n");
            context.append("❌ 不要忽略文件路径：`rm 41e6f846-xxx.json` ← 错误！应该是 `rm sessions/41e6f846-xxx.json`\n");
            context.append("❌ 不要先调用 List 查看目录\n");
            context.append("❌ 不要在删除前询问确认（系统会自动处理确认）\n");
            context.append("❌ 不要在工具调用后编造结果\n\n");

            context.append("### 📋 当用户要求查看/列出文件或目录时：\n\n");
            context.append("直接调用相应工具，不需要额外说明：\n");
            context.append("- 查看文件内容：`⏺ Read(文件名)`\n");
            context.append("- 列出目录：`⏺ List(目录路径)`\n");
            context.append("- 执行 ls 命令：`⏺ Bash(ls -la 目录)`\n\n");

            context.append("**正确示例（用户：写一个HelloWorld）：**\n");
            context.append("好的，我来帮你创建一个简单的Java程序。\n\n");
            context.append("⏺ Write(HelloWorld.java)\n\n");
            context.append("```java\n");
            context.append("public class HelloWorld {\n");
            context.append("    public static void main(String[] args) {\n");
            context.append("        System.out.println(\"Hello, World!\");\n");
            context.append("    }\n");
            context.append("}\n");
            context.append("```\n\n");

            context.append("**错误示例（不要这样做）：**\n");
            context.append("❌ 不要先调用 `⏺ Bash(ls -la *.java)` 检查文件\n");
            context.append("❌ 不要先调用 `⏺ Read(HelloWorld.java)` 检查文件\n");
            context.append("❌ 不要问用户\"需要检查现有文件吗？\"\n\n");

            context.append("⚠️ **关键：你必须输出完整的代码内容在代码块中！**\n\n");

            context.append("### 🔄 当用户要求修改现有文件时：\n\n");
            context.append("这时才需要先读取文件：\n");
            context.append("1. 使用 `⏺ Read(文件名)` 读取现有内容\n");
            context.append("2. 等待系统返回文件内容\n");
            context.append("3. 根据用户要求修改代码\n");
            context.append("4. 使用 `⏺ Write(文件名)` 写入新内容\n\n");

            context.append("## 🔧 工具调用格式（重要！你必须严格遵守！）\n\n");
            context.append("你可以调用以下工具来执行操作。**工具调用会被系统自动执行，你必须使用正确的格式！**\n\n");

            context.append("### 工具调用的两种格式（任选其一）：\n\n");

            context.append("**格式1：简化格式（推荐）**\n");
            context.append("- 读取文件：⏺ Read(文件名)\n");
            context.append("- 创建文件：⏺ Write(文件名)\n");
            context.append("- 执行命令：⏺ Bash(命令)\n");
            context.append("- 列出目录：⏺ List(目录)\n\n");

            context.append("**格式2：完整格式**\n");
            context.append("- 读取文件：file_manager read \"文件路径\"\n");
            context.append("- 列出目录：file_manager list \"目录路径\"\n");
            context.append("- 执行命令：command_executor \"命令\"\n");
            context.append("- 创建文件：使用代码块（见下方示例）\n\n");

            context.append("### 🔥 重要：根据用户实际需求生成命令\n\n");
            context.append("**不要使用固定示例，要根据用户的实际请求生成正确的命令！**\n\n");

            context.append("**用户请求 → 正确的命令：**\n");
            context.append("- \"查看桌面有哪些文件\" → `⏺ List(~/Desktop)` 或 `⏺ Bash(ls -la ~/Desktop)`\n");
            context.append("- \"查看当前目录\" → `⏺ List(.)` 或 `⏺ Bash(ls -la)`\n");
            context.append("- \"查看sessions目录\" → `⏺ List(sessions)` 或 `⏺ Bash(ls -la sessions)`\n");
            context.append("- \"读取桌面的test.txt\" → `⏺ Read(~/Desktop/test.txt)`\n");
            context.append("- \"删除桌面的demo.java\" → `⏺ Bash(rm ~/Desktop/demo.java)`\n\n");

            context.append("**创建文件示例：**\n");
            context.append("用户：\"写一个HelloWorld程序\"\n");
            context.append("你应该：\n\n");
            context.append("⏺ Write(HelloWorld.java)\n\n");
            context.append("```java\n");
            context.append("public class HelloWorld {\n");
            context.append("    public static void main(String[] args) {\n");
            context.append("        System.out.println(\"Hello, World!\");\n");
            context.append("    }\n");
            context.append("}\n");
            context.append("```\n\n");

            context.append("⚠️ **工具调用的关键规则（必须遵守！）：**\n");
            context.append("1. **工具调用命令会被立即执行** - 系统会自动检测并调用工具\n");
            context.append("2. **工具命令必须独立一行** - 前后不要有其他文字\n");
            context.append("3. **调用工具后立即停止输出** - 当你写出 `⏺ List(...)` 或 `⏺ Bash(...)` 或 `⏺ Read(...)` 后，**立即停止生成任何内容**\n");
            context.append("4. **绝对禁止编造工具结果** - 你不知道目录里有什么文件，不知道命令执行结果，**绝对不能猜测或编造**\n");
            context.append("5. **等待工具执行** - 系统会执行工具并返回真实结果给你，然后你才能继续回答\n");
            context.append("6. **提供选项时不执行** - 当你列出选项（1. 2. 3. 4.）时，不要执行任何操作，等待用户选择\n\n");

            context.append("⚠️ **严格禁止的错误行为示例：**\n");
            context.append("❌ 错误示例1：\n");
            context.append("\"让我检查一下 sessions 目录：\n");
            context.append("⏺ List(sessions)\n");
            context.append("好的，我看到目录下有一个文件 session_20241215_103045.json\"  ← **这是编造的，绝对禁止！**\n\n");

            context.append("✅ 正确示例1：\n");
            context.append("\"让我检查一下 sessions 目录：\n");
            context.append("⏺ List(sessions)\"  ← **然后立即停止，等待系统返回真实结果**\n\n");

            context.append("❌ 错误示例2：\n");
            context.append("\"让我执行删除命令：\n");
            context.append("⏺ Bash(rm sessions/*)\n");
            context.append("删除成功！文件已被删除。\"  ← **这是编造的，绝对禁止！**\n\n");

            context.append("✅ 正确示例2：\n");
            context.append("\"让我执行删除命令：\n");
            context.append("⏺ Bash(rm sessions/*)\"  ← **然后立即停止，等待系统返回真实结果**\n\n");

            context.append("## 📝 回答问题的规范\n\n");

            context.append("### ⚠️ 重要：区分\"说明\"和\"执行\"\n\n");
            context.append("**当用户只是询问/咨询时（不要执行工具）：**\n");
            context.append("用户问：\"命令有哪些\"、\"有什么功能\"、\"如何使用\" 等\n");
            context.append("你应该：用纯文本说明，**不要使用 ⏺ 符号**\n\n");

            context.append("**正确示例（用户：命令有哪些）：**\n");
            context.append("我可以帮你：\n");
            context.append("- 创建文件：告诉我\"创建一个HelloWorld.java文件\"\n");
            context.append("- 读取文件：告诉我\"读取HelloWorld.java的内容\"\n");
            context.append("- 列出目录：告诉我\"查看当前目录有什么\"\n");
            context.append("- 删除文件：告诉我\"删除某个文件\"\n\n");

            context.append("**错误示例（不要这样）：**\n");
            context.append("❌ 不要写：- 创建文件：⏺ Write(文件名)  ← 这会被误认为要执行工具\n");
            context.append("❌ 不要写：- 读取文件：⏺ Read(文件名)  ← 这会触发工具调用\n\n");

            context.append("**当用户提问时（非创建文件）：**\n");
            context.append("- 用简洁、自然的中文回答\n");
            context.append("- 不要使用 markdown 格式（如 ** - 1. 2. 等）\n");
            context.append("- 直接说明，不要过度格式化\n");
            context.append("- 保持对话自然流畅\n\n");
            context.append("**示例（正确）：**\n");
            context.append("是的，我记得！刚才创建的是一个链表实现，包含 ListNode 和 LinkedList 两个类。\n\n");
            context.append("**示例（错误）：**\n");
            context.append("不要使用：**刚才创建的代码包括：** 1. **ListNode类** 这样的格式！\n\n");

            context.append("## ⚠️ 禁止事项\n\n");
            context.append("1. 不要输出形如 `write_file \"...\" \"...\"` 的命令格式\n");
            context.append("2. 不要在没有检查的情况下直接覆盖文件\n");
            context.append("3. 不要在用户选择前就执行操作\n");
            context.append("4. 不要忘记在操作完成后生成总结\n\n");

            return new ChatMessage("system", context.toString());
        } catch (Exception e) {
            log.warn("无法构建项目上下文: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 策略1：滑动窗口
     * 保留最近 N 轮对话
     */
    private List<ChatMessage> applySlidingWindow(List<ChatMessage> fullHistory) {
        int maxMessages = maxHistoryTurns * 2;  // 每轮包含用户+AI消息

        if (fullHistory.size() <= maxMessages) {
            return new ArrayList<>(fullHistory);
        }

        // 保留最近 N 条消息
        int startIndex = fullHistory.size() - maxMessages;
        return new ArrayList<>(fullHistory.subList(startIndex, fullHistory.size()));
    }

    /**
     * 策略2：Token 控制
     * 根据 Token 数量动态截断
     */
    private List<ChatMessage> applyTokenLimit(List<ChatMessage> fullHistory) {
        List<ChatMessage> result = new ArrayList<>();
        int totalTokens = 0;

        // 从最新消息开始倒序添加
        for (int i = fullHistory.size() - 1; i >= 0; i--) {
            ChatMessage msg = fullHistory.get(i);
            int msgTokens = estimateTokens(msg.getContent());

            // 检查是否超过限制
            if (totalTokens + msgTokens > maxContextTokens) {
                // 如果这是第一条消息且超过限制，截断它
                if (result.isEmpty()) {
                    String truncatedContent = truncateToTokenLimit(msg.getContent(), maxContextTokens);
                    ChatMessage truncatedMsg = new ChatMessage(msg.getRole(), truncatedContent);
                    result.add(0, truncatedMsg);
                }
                break;
            }

            result.add(0, msg);  // 添加到开头
            totalTokens += msgTokens;
        }

        return result;
    }

    /**
     * 策略3：混合策略
     * 先应用滑动窗口，再应用 Token 控制
     */
    private List<ChatMessage> applyHybridStrategy(List<ChatMessage> fullHistory) {
        // 1. 先应用滑动窗口
        List<ChatMessage> windowedHistory = applySlidingWindow(fullHistory);

        // 2. 再应用 Token 控制
        return applyTokenLimit(windowedHistory);
    }

    /**
     * 估算文本的 Token 数量
     * 简单方法：中文 2 字符 ≈ 1 token，英文 4 字符 ≈ 1 token
     *
     * @param text 待估算的文本
     * @return 估算的 token 数量
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

        // 中文：2 字符 ≈ 1 token
        // 英文：4 字符 ≈ 1 token
        return (chineseChars / 2) + (otherChars / 4);
    }

    /**
     * 判断字符是否为中文
     */
    private boolean isChinese(char c) {
        return c >= 0x4E00 && c <= 0x9FA5;
    }

    /**
     * 截断文本到指定 Token 限制
     */
    private String truncateToTokenLimit(String text, int maxTokens) {
        if (estimateTokens(text) <= maxTokens) {
            return text;
        }

        // 简单截断：取前 N 个字符
        int targetChars = maxTokens * 3;  // 保守估计
        if (text.length() <= targetChars) {
            return text;
        }

        return text.substring(0, targetChars) + "\n\n[内容过长已截断...]";
    }

    /**
     * 输出上下文统计信息
     */
    private void logContextStatistics(List<ChatMessage> fullHistory, List<ChatMessage> managedHistory) {
        int fullTokens = fullHistory.stream()
                .mapToInt(msg -> estimateTokens(msg.getContent()))
                .sum();

        int managedTokens = managedHistory.stream()
                .mapToInt(msg -> estimateTokens(msg.getContent()))
                .sum();

        if (fullHistory.size() != managedHistory.size()) {
            log.debug("📊 上下文管理统计:");
            log.debug("  完整历史: {} 条消息 (~{} tokens)", fullHistory.size(), fullTokens);
            log.debug("  发送历史: {} 条消息 (~{} tokens)", managedHistory.size(), managedTokens);
            log.debug("  节省: {} tokens ({}%)",
                    fullTokens - managedTokens,
                    (fullTokens - managedTokens) * 100 / Math.max(fullTokens, 1));
        }
    }

    /**
     * 获取当前策略
     */
    public Strategy getStrategy() {
        return strategy;
    }

    /**
     * 设置策略
     */
    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
        log.info("切换上下文策略为: {}", strategy);
    }

    /**
     * 设置最大历史轮数（用于滑动窗口策略）
     */
    public void setMaxHistoryTurns(int maxHistoryTurns) {
        this.maxHistoryTurns = maxHistoryTurns;
        log.info("设置最大历史轮数: {} 轮", maxHistoryTurns);
    }

    /**
     * 设置最大上下文 Token 数
     */
    public void setMaxContextTokens(int maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
        log.info("设置最大上下文 Tokens: {}", maxContextTokens);
    }

    /**
     * 获取配置摘要
     */
    public String getConfigSummary() {
        return String.format("Strategy: %s, MaxTurns: %d, MaxTokens: %d",
                strategy, maxHistoryTurns, maxContextTokens);
    }
}

