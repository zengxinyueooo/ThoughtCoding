package com.thoughtcoding.service;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.tools.ToolRegistry;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 集成LangChain4j和DeepSeek API的AI服务实现，功能包括：
 * - 流式聊天：支持实时响应的流式聊天交互
 * - 模型管理：根据配置动态选择和初始化模型
 * - 消息处理：通过回调处理和传递消息
 * - 错误处理：捕获和处理API调用中的异常
 * - 工具集成：与工具注册系统协同工作
 * - 性能监控：跟踪和报告令牌使用情况
 */
public class LangChainService implements AIService {
    private final AppConfig appConfig;
    private final ToolRegistry toolRegistry;
    private Consumer<ChatMessage> messageHandler;
    private Consumer<ToolCall> toolCallHandler;
    private StreamingChatLanguageModel streamingChatModel;

    // 用于跟踪生成状态
    private volatile boolean isGenerating = false;
    private volatile boolean shouldStop = false;

    public LangChainService(AppConfig appConfig, ToolRegistry toolRegistry) {
        this.appConfig = appConfig;
        this.toolRegistry = toolRegistry;
        initializeChatModel();
    }

    private void initializeChatModel() {
        try {
            // 根据配置初始化DeepSeek模型
            AppConfig.ModelConfig modelConfig = appConfig.getModelConfig(appConfig.getDefaultModel());
            if (modelConfig != null) {
                this.streamingChatModel = createDeepSeekModel(modelConfig);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private StreamingChatLanguageModel createDeepSeekModel(AppConfig.ModelConfig config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseURL())
                .apiKey(config.getApiKey())
                .modelName(config.getName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .logRequests(true)
                .logResponses(true)
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

        // 设置生成状态
        isGenerating = true;
        shouldStop = false;

        // 用于累积完整的AI响应
        final StringBuilder fullResponse = new StringBuilder();

        try {
            // 准备消息
            List<dev.langchain4j.data.message.ChatMessage> messages = prepareMessages(input, history);

            System.out.println("🚀 Sending request to DeepSeek API...");

            // 执行流式调用
            streamingChatModel.generate(messages, new StreamingResponseHandler<AiMessage>() {
                @Override
                public void onNext(String token) {
                    // 检查是否需要停止
                    if (shouldStop) {
                        return;
                    }

                    // 累积完整响应
                    fullResponse.append(token);

                    // 实时发送每个token给UI显示（不添加到历史记录）
                    ChatMessage tokenMessage = new ChatMessage("assistant", token);
                    messageHandler.accept(tokenMessage);
                }

                @Override
                public void onComplete(Response<dev.langchain4j.data.message.AiMessage> response) {
                    try {
                        // 检查是否被用户停止
                        if (shouldStop && fullResponse.length() > 0) {
                            // 添加被截断的响应到历史记录
                            ChatMessage truncatedMessage = new ChatMessage("assistant",
                                fullResponse.toString() + "\n\n💡 [生成已被用户停止]");
                            history.add(truncatedMessage);
                            return;
                        }

                        // 正常完成：将完整的AI响应添加到历史记录
                        if (fullResponse.length() > 0) {
                            ChatMessage completeMessage = new ChatMessage("assistant", fullResponse.toString());
                            history.add(completeMessage);
                        }

                        // 输出一个换行，让提示符在新行显示
                        System.out.println();
                    } finally {
                        // 重置生成状态
                        isGenerating = false;
                        shouldStop = false;
                    }
                }

                @Override
                public void onError(Throwable error) {
                    try {
                        System.err.println("❌ DeepSeek API error: " + error.getMessage());

                        // 发送错误消息
                        ChatMessage errorMessage = new ChatMessage("assistant",
                                "抱歉，我在处理您的请求时遇到了问题： " + error.getMessage());
                        messageHandler.accept(errorMessage);
                        history.add(errorMessage);
                    } finally {
                        // 重置生成状态
                        isGenerating = false;
                        shouldStop = false;
                    }
                }
            });

        } catch (Exception e) {
            isGenerating = false;
            shouldStop = false;

            System.err.println("❌ Service error: " + e.getMessage());
            e.printStackTrace();

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

        // 添加历史消息
        if (history != null && !history.isEmpty()) {
            messages.addAll(convertToLangChainHistory(history));
        }

        // 添加当前用户消息
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

    /**
     * 检查当前是否正在生成响应
     * @return true 如果正在生成，false 否则
     */
    public boolean isGenerating() {
        return isGenerating;
    }

    /**
     * 停止当前正在进行的生成
     */
    public void stopCurrentGeneration() {
        if (isGenerating) {
            shouldStop = true;
            System.out.println("⏸️  正在停止生成...");
        }
    }
}

