// src/main/java/com/thoughtcoding/core/AgentLoop.java
package com.thoughtcoding.core;



import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.service.PerformanceMonitor;




import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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

   /* public void processInput(String input) {
        // 开始性能监控
        PerformanceMonitor monitor = context.getPerformanceMonitor();
        monitor.start();

        try {
            // 添加用户消息到历史
            ChatMessage userMessage = new ChatMessage("user", input);
            history.add(userMessage);

            // 显示用户消息（带对话框效果）- 确保调用这个方法
            if (context.getUi() != null) {
                context.getUi().displayUserMessageWithBox(userMessage);
            } else {
                System.out.println("UI is null!");
            }

            // 显示AI正在思考的提示
            context.getUi().displayTypingEffect();

            // 流式处理AI响应
            List<ChatMessage> aiResponse = context.getAiService().streamingChat(input, history, modelName);

            // 添加AI响应到历史
            if (aiResponse != null && !aiResponse.isEmpty()) {
                for (ChatMessage message : aiResponse) {
                    history.add(message);
                    // 显示AI消息（带对话框效果）
                    context.getUi().displayAssistantMessageWithBox(message);
                }
            } else {
                // 如果没有响应，显示一个默认消息
                ChatMessage defaultResponse = new ChatMessage("assistant", "I'm thinking...");
                history.add(defaultResponse);
                context.getUi().displayAssistantMessageWithBox(defaultResponse);
            }

            // 保存会话
            context.getSessionService().saveSession(sessionId, history);

        } catch (Exception e) {
            context.getUi().displayError("Error processing input: " + e.getMessage());
            e.printStackTrace(); // 添加堆栈跟踪以便调试
        } finally {
            // 结束性能监控
            monitor.stop();
        }
    }*/

    private void handleMessage(ChatMessage message) {
        // 显示AI消息
        context.getUi().displayAIMessage(message);

        // 添加到历史
        history.add(message);
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