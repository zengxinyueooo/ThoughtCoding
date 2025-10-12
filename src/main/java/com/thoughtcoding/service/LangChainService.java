package com.thoughtcoding.service;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.core.StreamingOutput;
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
                //System.out.println("✅ DeepSeek streaming model initialized successfully");
            } else {
                //System.err.println("❌ Model configuration not found for: " + appConfig.getDefaultModel());
            }
        } catch (Exception e) {
           // System.err.println("❌ Failed to initialize DeepSeek model: " + e.getMessage());
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
        // 非流式聊天 - 如果需要的话
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

        // 使用StreamingOutput来管理累积逻辑
        StreamingOutput streamingOutput = new StreamingOutput(messageHandler);

        try {
            // 准备消息
            List<dev.langchain4j.data.message.ChatMessage> messages = prepareMessages(input, history);

            System.out.println("🚀 Sending request to DeepSeek API...");

            // 执行真实的流式调用 - 使用正确的StreamingResponseHandler
            streamingChatModel.generate(messages, new StreamingResponseHandler<AiMessage>() {
                @Override
                public void onNext(String token) {
                    // 实时输出每个token
                    streamingOutput.appendContent(token);
                }

                @Override
                public void onComplete(Response<dev.langchain4j.data.message.AiMessage> response) {
                    // 流式输出完成
                    streamingOutput.complete();

                    // 输出token使用情况
                    TokenUsage tokenUsage = response.tokenUsage();
                    if (tokenUsage != null) {
                        System.out.println("\n📊 Token usage - Input: " + tokenUsage.inputTokenCount() +
                                ", Output: " + tokenUsage.outputTokenCount() +
                                ", Total: " + tokenUsage.totalTokenCount());
                    }

                    //System.out.println("✅ DeepSeek response completed");
                }

                @Override
                public void onError(Throwable error) {
                    streamingOutput.reset();
                    System.err.println("❌ DeepSeek API error: " + error.getMessage());

                    // 发送错误消息
                    ChatMessage errorMessage = new ChatMessage("assistant",
                            "抱歉，我在处理您的请求时遇到了问题： " + error.getMessage());
                    messageHandler.accept(errorMessage);
                }
            });

        } catch (Exception e) {
            streamingOutput.reset();
            System.err.println("❌ Service error: " + e.getMessage());
            e.printStackTrace();

            ChatMessage errorMessage = new ChatMessage("assistant",
                    "服务暂时不可用，请稍后重试。错误信息: " + e.getMessage());
            messageHandler.accept(errorMessage);
        }
        return history;
    }


    private List<dev.langchain4j.data.message.ChatMessage> prepareMessages(
            String input, List<ChatMessage> history) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();

        // 添加系统消息（可选）
        // messages.add(SystemMessage.from("你是一个专业的编程助手，专门帮助解决编程问题。"));

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
}