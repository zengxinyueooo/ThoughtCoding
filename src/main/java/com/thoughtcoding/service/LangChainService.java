package com.thoughtcoding.service;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.tools.ToolRegistry;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 集成LangChain4j和DeepSeek API的AI服务实现
 */
public class LangChainService implements AIService {
    private final AppConfig appConfig;
    private final ContextManager contextManager;
    private Consumer<ChatMessage> messageHandler;
    private Consumer<ToolCall> toolCallHandler;
    private StreamingChatModel streamingChatModel;

    // 用于跟踪生成状态
    private volatile boolean isGenerating = false;
    private volatile boolean shouldStop = false;
    private boolean hasTriggeredToolCall = false;

    // 用于智能识别代码块意图
    private String lastCodeBlock = null;
    private String lastCodeFileName = null;
    private static final int CONTEXT_THRESHOLD = 50; // 判断阈值: 代码块后文字少于50字符则触发工具调用

    public LangChainService(AppConfig appConfig, ToolRegistry toolRegistry, ContextManager contextManager) {
        this.appConfig = appConfig;
        this.contextManager = contextManager;
        initializeChatModel();
    }

    private void initializeChatModel() {
        try {
            AppConfig.ModelConfig modelConfig = appConfig.getModelConfig(appConfig.getDefaultModel());
            if (modelConfig != null) {
                this.streamingChatModel = createDeepSeekModel(modelConfig);
            }
        } catch (Exception e) {
            System.err.println("初始化模型失败: " + e.getMessage());
        }
    }

    private StreamingChatModel createDeepSeekModel(AppConfig.ModelConfig config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseURL())
                .apiKey(config.getApiKey())
                .modelName(config.getName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Override
    public List<ChatMessage> chat(String input, List<ChatMessage> history, String modelName) {
        throw new UnsupportedOperationException("Use streamingChat for real AI service");
    }

    @Override
    public List<ChatMessage> streamingChat(String input, List<ChatMessage> history, String modelName) {
        if (messageHandler == null) {
            throw new IllegalStateException("Message handler not set");
        }

        if (streamingChatModel == null) {
            throw new IllegalStateException("DeepSeek model not initialized. Please check your configuration.");
        }

        isGenerating = true;
        shouldStop = false;
        hasTriggeredToolCall = false;

        final StringBuilder fullResponse = new StringBuilder();
        final CompletableFuture<Void> completionFuture = new CompletableFuture<>();

        try {
            List<dev.langchain4j.data.message.ChatMessage> messages = prepareMessages(input, history);

            // 移除提示信息，保持输出简洁
            // System.out.println("🚀 Sending request to DeepSeek API...");

            streamingChatModel.chat(messages, new StreamingChatResponseHandler() {
                private final StringBuilder codeBuffer = new StringBuilder();
                private boolean confirmationDisplayed = false;
                private boolean inCodeBlock = false;
                private String detectedFileName = null;
                private int codeBlockCount = 0;

                @Override
                public void onPartialResponse(String token){
                    if (shouldStop || hasTriggeredToolCall) {
                        return;
                    }

                    fullResponse.append(token);
                    String currentText = fullResponse.toString();

                    // 🔥 持续检测文件名
                    if (detectedFileName == null) {
                        detectedFileName = extractFileNameFromText(currentText);
                    }

                    // 🔥 检测代码块开始（```java 或 ```python 等）
                    if (!inCodeBlock && token.contains("```")) {
                        inCodeBlock = true;
                        codeBlockCount++;

                         // 检测文件名但不显示任何提示
                        if (!confirmationDisplayed) {
                            if (detectedFileName == null) {
                                detectedFileName = extractFileNameFromText(currentText);
                            }
                            if (detectedFileName == null) {
                                detectedFileName = "NewFile.java";
                            }
                            confirmationDisplayed = true;
                        }

                        // ✅ 输出代码块开始标记
                        messageHandler.accept(new ChatMessage("assistant", token));
                        return;
                    }

                    // 🔥 检测代码块结束（```）
                    if (inCodeBlock && token.contains("```")) {
                        inCodeBlock = false;
                        codeBlockCount++;

                        // ✅ 先输出代码块结束标记
                        messageHandler.accept(new ChatMessage("assistant", token));

                        // ✅ 记录代码块信息,但不立即触发工具调用
                        if (confirmationDisplayed && codeBlockCount >= 2) {
                            String cleanCode = codeBuffer.toString();
                            // 移除语言标记（如 "java"、"python"）
                            cleanCode = cleanCode.replaceFirst("^\\s*(java|python|javascript|cpp|c|python3|js|ts)\\s*\n", "");

                            lastCodeBlock = cleanCode.trim();
                            lastCodeFileName = detectedFileName;

                            // ❌ 移除这行: hasTriggeredToolCall = true;
                            // 不再阻止后续 token 输出
                        }

                        return;
                    }

                    // 🔥 在代码块内，输出所有内容（包括语言标记）
                    if (inCodeBlock) {
                        // ✅ 输出所有内容,包括语言标记
                        codeBuffer.append(token);
                        messageHandler.accept(new ChatMessage("assistant", token));
                        return;
                    }

                    // 正常输出 AI 的描述文本
                    messageHandler.accept(new ChatMessage("assistant", token));
                }

                private void triggerToolCallWithCode(String fileName, String code) {
                    // 🔥 创建工具调用并触发
                    java.util.Map<String, Object> params = new java.util.HashMap<>();
                    params.put("path", fileName);
                    params.put("content", code.trim());

                    // 🔥 关键：使用 6 参数构造函数，最后一个参数标记为 true 表示流式触发
                    ToolCall toolCall = new ToolCall("write_file", params, null, false, 0, true);
                    //                                                            参数顺序：
                    //                                                            toolName, params, result, success, executionTime, streamingTriggered

                    // ✅ 在这里设置标志位,防止 detectAndTriggerToolCall() 重复触发
                    hasTriggeredToolCall = true;

                    // 立即触发工具调用处理器
                    if (toolCallHandler != null) {
                        toolCallHandler.accept(toolCall);
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse chatResponse) {
                    try {
                        // ✅ 智能判断是否触发工具调用(基于代码块)
                        if (lastCodeBlock != null && !lastCodeBlock.isEmpty()) {
                            String fullText = fullResponse.toString();

                            // 找到最后一个代码块结束位置
                            int lastCodeBlockEndIndex = fullText.lastIndexOf("```");
                            if (lastCodeBlockEndIndex > 0) {
                                // ✅ 新增: 检查代码块前的上下文(前100字符)
                                int lastCodeBlockStartIndex = fullText.lastIndexOf("```", lastCodeBlockEndIndex - 1);
                                boolean isExplanation = false;

                                if (lastCodeBlockStartIndex > 0) {
                                    // 获取代码块前的文字(最多100字符)
                                    int startPos = Math.max(0, lastCodeBlockStartIndex - 100);
                                    String beforeCodeBlock = fullText.substring(startPos, lastCodeBlockStartIndex).trim();

                                    // 检查是否包含讲解相关的关键词
                                    String[] explainKeywords = {"讲解", "解释", "说明", "介绍", "演示", "展示", "分析", "理解"};
                                    for (String keyword : explainKeywords) {
                                        if (beforeCodeBlock.contains(keyword)) {
                                            isExplanation = true;
                                            break;
                                        }
                                    }
                                }

                                // ✅ 如果是讲解,不触发工具调用
                                if (isExplanation) {
                                    // 清理状态但不触发工具调用
                                    lastCodeBlock = null;
                                    lastCodeFileName = null;
                                } else {
                                    // 原有的 CONTEXT_THRESHOLD 逻辑
                                    String afterCodeBlock = fullText.substring(lastCodeBlockEndIndex + 3).trim();
                                    if (afterCodeBlock.length() < CONTEXT_THRESHOLD) {
                                        triggerToolCallWithCode(lastCodeFileName, lastCodeBlock);
                                    }
                                }
                            }
                        }

                        detectAndTriggerToolCall(fullResponse.toString());

                        if (shouldStop && !fullResponse.isEmpty()) {
                            String cleanContent = removeToolCommandText(fullResponse.toString());
                            ChatMessage truncatedMessage = new ChatMessage("assistant",
                                    cleanContent + "\n\n💡 [生成已被用户停止]");
                            history.add(truncatedMessage);
                            return;
                        }

                        if (!fullResponse.isEmpty()) {
                            // 🔥 保存到历史记录前，清理掉工具调用命令文本
                            String cleanContent = removeToolCommandText(fullResponse.toString());
                            if (!cleanContent.isEmpty()) {
                                ChatMessage completeMessage = new ChatMessage("assistant", cleanContent);
                                history.add(completeMessage);
                            }
                        }

                        System.out.println();
                    } finally {
                        isGenerating = false;
                        shouldStop = false;
                        completionFuture.complete(null); // 🔥 通知主线程：流式响应已完成

                        // ✅ 清理代码块状态
                        lastCodeBlock = null;
                        lastCodeFileName = null;
                        codeBuffer.setLength(0);
                        detectedFileName = null;
                        confirmationDisplayed = false;
                        codeBlockCount = 0;
                        inCodeBlock = false;
                    }
                }

                @Override
                public void onError(Throwable error) {
                    try {
                        System.err.println("❌ DeepSeek API error: " + error.getMessage());

                        ChatMessage errorMessage = new ChatMessage("assistant",
                                "抱歉，我在处理您的请求时遇到了问题： " + error.getMessage());
                        messageHandler.accept(errorMessage);
                        history.add(errorMessage);
                    } finally {
                        isGenerating = false;
                        shouldStop = false;
                        completionFuture.completeExceptionally(error); // 🔥 通知主线程：发生错误
                    }
                }
            });

            // 🔥 等待流式响应完成（最多等待 5 分钟）
            try {
                completionFuture.get(5, TimeUnit.MINUTES);
            } catch (java.util.concurrent.TimeoutException e) {
                System.err.println("⚠️  流式响应超时");
                completionFuture.cancel(true);
            } catch (Exception e) {
                System.err.println("⚠️  等待流式响应时发生错误: " + e.getMessage());
            }

        } catch (Exception e) {
            isGenerating = false;
            shouldStop = false;

            System.err.println("❌ Service error: " + e.getMessage());

            ChatMessage errorMessage = new ChatMessage("assistant",
                    "服务暂时不可用，请稍后重试。错误信息: " + e.getMessage());
            messageHandler.accept(errorMessage);
            history.add(errorMessage);
        }

        return history;
    }

    private List<dev.langchain4j.data.message.ChatMessage> prepareMessages(
            String input, List<ChatMessage> history) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();

        if (contextManager != null) {
            ChatMessage projectContext = contextManager.buildProjectContextMessage();
            if (projectContext != null) {
                messages.add(dev.langchain4j.data.message.SystemMessage.from(projectContext.getContent()));
            }
        }

        List<ChatMessage> managedHistory = history;
        if (contextManager != null && history != null && !history.isEmpty()) {
            managedHistory = contextManager.getContextForAI(history);
        }

        if (managedHistory != null && !managedHistory.isEmpty()) {
            messages.addAll(convertToLangChainHistory(managedHistory));
        }

        messages.add(dev.langchain4j.data.message.UserMessage.from(input));

        return messages;
    }

    private List<dev.langchain4j.data.message.ChatMessage> convertToLangChainHistory(
            List<ChatMessage> history) {
        return history.stream()
                .map(msg -> {
                    if ("user".equals(msg.getRole())) {
                        return dev.langchain4j.data.message.UserMessage.from(msg.getContent());
                    } else if ("assistant".equals(msg.getRole())) {
                        return dev.langchain4j.data.message.AiMessage.from(msg.getContent());
                    } else {
                        return dev.langchain4j.data.message.SystemMessage.from(msg.getContent());
                    }
                })
                .collect(Collectors.toList());
    }

    @Override
    public void setMessageHandler(Consumer<ChatMessage> handler) {
        this.messageHandler = handler;
    }

    @Override
    public void setToolCallHandler(Consumer<ToolCall> handler) {
        this.toolCallHandler = handler;
    }

    @Override
    public boolean validateModel(String modelName) {
        return appConfig.getModels().containsKey(modelName);
    }

    @Override
    public List<String> getAvailableModels() {
        return new ArrayList<>(appConfig.getModels().keySet());
    }

    private boolean detectAndTriggerToolCall(String aiResponse) {
        if (toolCallHandler == null || aiResponse == null || aiResponse.isEmpty()) {
            return false;
        }

        if (hasTriggeredToolCall) {
            return true;
        }

        // ✅ 新增: 检查响应中是否包含代码块
        // 用于判断是否应该触发文件写入操作
        boolean hasCodeBlock = aiResponse.contains("```");

        String lowerResponse = aiResponse.toLowerCase();

        // 🔥 优先检测简化格式（⏺ Read/Write/Bash/List）- 这些格式必须严格执行
        // ⏺ Read(文件名)
        if (aiResponse.contains("⏺ Read(") || lowerResponse.matches(".*⏺\\s*read\\s*\\(.*")) {
            String filePath = extractFromSimplifiedFormat(aiResponse, "read");
            if (filePath != null) {
                triggerFileManagerRead(filePath);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        // ⏺ List(目录)
        if (aiResponse.contains("⏺ List(") || lowerResponse.matches(".*⏺\\s*list\\s*\\(.*")) {
            String dirPath = extractFromSimplifiedFormat(aiResponse, "list");
            if (dirPath != null) {
                triggerFileManagerList(dirPath);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        // ⏺ Bash(命令)
        if (aiResponse.contains("⏺ Bash(") || lowerResponse.matches(".*⏺\\s*bash\\s*\\(.*")) {
            String command = extractFromSimplifiedFormat(aiResponse, "bash");
            if (command != null) {
                triggerCommandExecutor(command);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        // ⏺ Write(文件名) - 注意：这个只是标记，实际内容在代码块中
        if (aiResponse.contains("⏺ Write(") || lowerResponse.matches(".*⏺\\s*write\\s*\\(.*")) {
            // ✅ 只有当响应中包含代码块时才触发文件写入
            if (!hasCodeBlock) {
                // 纯文字讲解中提到了 Write,但没有代码块,不触发
                return false;
            }

            String fileName = extractFromSimplifiedFormat(aiResponse, "write");
            String content = extractFileContent(aiResponse);
            if (fileName != null && content != null) {
                triggerWriteFile(fileName, content);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        // 🔥 检测完整格式
        // 🔥 新增：检测 file_manager read 命令
        if (lowerResponse.contains("file_manager read")) {
            String filePath = extractQuotedPath(aiResponse, "file_manager read");
            if (filePath != null) {
                triggerFileManagerRead(filePath);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        // 🔥 新增：检测 file_manager list 命令
        if (lowerResponse.contains("file_manager list")) {
            String dirPath = extractQuotedPath(aiResponse, "file_manager list");
            if (dirPath != null) {
                triggerFileManagerList(dirPath);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        // 🔥 新增：检测 command_executor 命令
        if (lowerResponse.contains("command_executor")) {
            String command = extractQuotedPath(aiResponse, "command_executor");
            if (command != null) {
                triggerCommandExecutor(command);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        // 🔥 提前检测：看到 write_f 就知道可能是 write_file，提前标记（但不触发）
        if (lowerResponse.contains("write_f") && !hasTriggeredToolCall) {
            // 继续积累，等待完整命令
        }

        if (lowerResponse.contains("write_file")) {
            String fileName = extractFileNameFromCommand(aiResponse);
            String content = extractContentFromCommand(aiResponse);

            if (fileName != null && content != null) {
                triggerWriteFile(fileName, content);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        // 🔥 新增：检测代码块格式（优先，避免命令格式）
        if (lowerResponse.contains("```java") || lowerResponse.contains("```python") ||
            lowerResponse.contains("```javascript")) {

            // 检查是否包含文件名提示
            if (lowerResponse.contains("文件名") || lowerResponse.contains("filename") ||
                lowerResponse.matches(".*\\w+\\.\\w+.*")) {

                String fileName = extractFileNameFromText(aiResponse);
                String content = extractFileContent(aiResponse);

                if (fileName != null && content != null) {
                    triggerWriteFile(fileName, content);
                    hasTriggeredToolCall = true;
                    return true;
                }
            }
        }

        if ((lowerResponse.contains("创建") || lowerResponse.contains("create")) &&
            (lowerResponse.contains("文件") || lowerResponse.contains("file")) &&
            lowerResponse.contains(".java")) {

            String fileName = extractFileName(aiResponse);
            String content = extractFileContent(aiResponse);

            if (fileName != null && content != null) {
                triggerWriteFile(fileName, content);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        if ((lowerResponse.contains("已创建") || lowerResponse.contains("创建成功") ||
             lowerResponse.contains("已成功创建")) &&
            lowerResponse.contains(".java")) {

            String fileName = extractFileName(aiResponse);
            String content = extractCodeFromText(aiResponse);

            if (fileName != null && content != null) {
                triggerWriteFile(fileName, content);
                hasTriggeredToolCall = true;
                return true;
            }
        }

        return false;
    }

    private void triggerWriteFile(String fileName, String content) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("path", fileName);
        params.put("content", content);

        // 不再显示中间状态信息，直接触发工具调用确认
        ToolCall toolCall = new ToolCall("write_file", params, null, false, 0);
        toolCallHandler.accept(toolCall);
    }

    private String extractFileNameFromCommand(String response) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("write_file\\s+\"([^\"]+)\"");
        java.util.regex.Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String extractContentFromCommand(String response) {
        // 使用贪婪匹配，匹配到最后一个引号
        // 支持转义的引号 \"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "write_file\\s+\"[^\"]+\"\\s+\"((?:[^\"\\\\]|\\\\.)*)\"");
        java.util.regex.Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            String content = matcher.group(1);
            // 处理转义字符
            return content.replace("\\n", "\n")
                         .replace("\\\"", "\"")
                         .replace("\\\\", "\\")
                         .replace("\\t", "    ");
        }
        return null;
    }

    private String extractFileName(String response) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("([\\w/]+\\.java)");
        java.util.regex.Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 从文本中提取文件名（支持多种格式）
     * 例如："文件名：HelloWorld.java" 或 "创建 HelloWorld.java"
     */
    private String extractFileNameFromText(String response) {
        // 优先匹配 "文件名：XXX" 或 "filename: XXX" 格式
        java.util.regex.Pattern pattern1 = java.util.regex.Pattern.compile(
            "(?:文件名|filename|file name)\\s*[:：]?\\s*([\\w/]+\\.\\w+)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher1 = pattern1.matcher(response);
        if (matcher1.find()) {
            return matcher1.group(1);
        }

        // 其次匹配任何文件名格式
        java.util.regex.Pattern pattern2 = java.util.regex.Pattern.compile("([\\w/]+\\.(?:java|py|js|ts|cpp|c|h))");
        java.util.regex.Matcher matcher2 = pattern2.matcher(response);
        if (matcher2.find()) {
            return matcher2.group(1);
        }

        return null;
    }

    private String extractFileContent(String response) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("```(?:java)?\\s*\\n([\\s\\S]*?)\\n```");
        java.util.regex.Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String extractCodeFromText(String response) {
        String codeBlock = extractFileContent(response);
        if (codeBlock != null) {
            return codeBlock;
        }

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "(?:public\\s+)?class\\s+\\w+\\s*\\{[\\s\\S]*?\\n\\}");
        java.util.regex.Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            return matcher.group(0).trim();
        }

        return null;
    }

    /**
     * 🔥 从简化格式中提取参数
     * 例如：⏺ Read(HelloWorld.java) -> "HelloWorld.java"
     *       ⏺ Bash(ls -la) -> "ls -la"
     */
    private String extractFromSimplifiedFormat(String response, String command) {
        // 匹配格式：⏺ Command(参数)
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "⏺\\s*" + command + "\\s*\\(([^)]+)\\)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * 🔥 从完整格式中提取带引号的路径
     * 例如：file_manager read "test.java" -> "test.java"
     */
    private String extractQuotedPath(String response, String command) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            command + "\\s+\"([^\"]+)\"",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 🔥 触发文件读取工具调用
     */
    private void triggerFileManagerRead(String filePath) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("command", "read");
        params.put("path", filePath);

        ToolCall toolCall = new ToolCall("file_manager", params, null, false, 0);
        toolCallHandler.accept(toolCall);
    }

    /**
     * 🔥 触发目录列出工具调用
     */
    private void triggerFileManagerList(String dirPath) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("command", "list");
        params.put("path", dirPath);

        ToolCall toolCall = new ToolCall("file_manager", params, null, false, 0);
        toolCallHandler.accept(toolCall);
    }

    /**
     * 🔥 触发命令执行工具调用
     */
    private void triggerCommandExecutor(String command) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("command", command);

        ToolCall toolCall = new ToolCall("command_executor", params, null, false, 0);
        toolCallHandler.accept(toolCall);
    }

    /**
     * 移除文本中的工具调用命令部分
     * 例如: "好的，我来帮你创建。\n\nwrite_file \"test.java\" \"...\"\n"
     * 返回: "好的，我来帮你创建。"
     */
    private String removeToolCommandText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // 移除 write_file 命令及其后续内容
        String result = text.replaceAll("\\s*write_file\\s+\"[^\"]+\"[\\s\\S]*", "");

        // 移除尾部的多余空白
        return result.trim();
    }

    public boolean isGenerating() {
        return isGenerating;
    }

    public void stopCurrentGeneration() {
        if (isGenerating) {
            shouldStop = true;
            System.out.println("⏸️  正在停止生成...");
        }
    }
}

