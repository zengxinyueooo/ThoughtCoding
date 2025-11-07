package com.thoughtcoding.core;



import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.service.PerformanceMonitor;




import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 在ThoughtCodingCommand中管理AI交互的核心循环（此类中没有实现 转到ThoughtCodingCommand查看）
 *
 * AgentLoop启动和协调 ，AI对话、工具调用、会话管理等
 *
 * 流程协调：管理从用户输入到AI响应的完整流程
 *
 * 工具调度：协调AI模型与工具系统的交互
 *
 * 状态管理：维护对话状态和上下文
 *
 * 错误处理：处理整个流程中的异常情况
 */
public class AgentLoop {
    private final ThoughtCodingContext context;
    private final List<ChatMessage> history;
    private final String sessionId;
    private final String modelName;

    public AgentLoop(ThoughtCodingContext context, String sessionId, String modelName) {
        this.context = context;
        this.sessionId = sessionId;
        this.modelName = modelName;
        this.history = new ArrayList<>();

        // 设置消息和工具调用处理器
        context.getAiService().setMessageHandler(this::handleMessage);
        context.getAiService().setToolCallHandler(this::handleToolCall);
    }

    public void loadHistory(List<ChatMessage> previousHistory) {
        if (previousHistory != null) {
            history.addAll(previousHistory);
        }
    }

    public void processInput(String input) {
        // 开始性能监控
        PerformanceMonitor monitor = context.getPerformanceMonitor();
        monitor.start();

        try {
            // 添加用户消息到历史
            ChatMessage userMessage = new ChatMessage("user", input);
            history.add(userMessage);

            // 显示用户消息
            //context.getUi().displayUserMessage((com.thoughtcoding.model.ChatMessage) userMessage);

            // 流式处理AI响应
            context.getAiService().streamingChat(input, history, modelName);

            // 保存会话
            context.getSessionService().saveSession(sessionId, history);

        } catch (Exception e) {
            context.getUi().displayError("Error processing input: " + e.getMessage());
        } finally {
            // 结束性能监控
            monitor.stop();
        }
    }


    private void handleMessage(ChatMessage message) {
        // 显示AI消息（用于流式输出的实时显示）
        context.getUi().displayAIMessage(message);

        // 注意：不在这里添加到历史记录
        // LangChainService 会在流式输出完成后，将完整的AI响应添加到历史记录
        // 这样可以避免历史记录中出现大量零散的 token 消息
    }

    private void handleToolCall(ToolCall toolCall) {
        // 显示工具调用
        context.getUi().displayToolCall(toolCall);
    }

    public List<ChatMessage> getHistory() {
        return new ArrayList<>(history);
    }

    public String getSessionId() {
        return sessionId;
    }
}