package com.thoughtcoding.core;

import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolExecution;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.service.PerformanceMonitor;
import com.thoughtcoding.tools.BaseTool;

import java.util.ArrayList;
import java.util.List;

/**
 * 在ThoughtCodingCommand中管理AI交互的核心循环
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
    private final ToolExecutionConfirmation confirmation;  // 🔥 新增：交互式确认组件

    public AgentLoop(ThoughtCodingContext context, String sessionId, String modelName) {
        this.context = context;
        this.sessionId = sessionId;
        this.modelName = modelName;
        this.history = new ArrayList<>();

        // 🔥 创建交互式确认组件
        this.confirmation = new ToolExecutionConfirmation(
            context.getUi(),
            context.getUi().getLineReader()
        );

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
            // 重置待处理的工具调用
            pendingToolCall = null;

            // 添加用户消息到历史
            ChatMessage userMessage = new ChatMessage("user", input);
            history.add(userMessage);

            // 流式处理AI响应
            context.getAiService().streamingChat(input, history, modelName);

            // 🔥 AI 响应完成后，执行待处理的工具调用
            executePendingToolCall();

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

    // 用于缓存工具调用，等待 AI 响应完成后再执行
    private ToolCall pendingToolCall = null;

    private void handleToolCall(ToolCall toolCall) {
        // 🔥 不再显示工具调用通知（已在流式输出中显示）
        // context.getUi().displayToolCall(toolCall);

        // 🔥 缓存工具调用，不立即执行（等待 AI 流式输出完成）
        this.pendingToolCall = toolCall;
    }

    /**
     * 🔥 在 AI 响应完成后执行待处理的工具调用
     */
    public void executePendingToolCall() {
        if (pendingToolCall == null) {
            return;
        }

        try {
            // 🔥 检查是否是流式触发的工具调用（已经在流式输出中显示过确认框）
            if (pendingToolCall.isStreamingTriggered()) {
                // 流式触发的工具调用，确认框已在流式输出中显示，只需简单询问
                boolean approved = confirmation.askSimpleConfirmation();

                if (approved) {
                    executeToolCall(pendingToolCall);
                } else {
                    context.getUi().displayWarning("⏭️  操作已取消");
                }
            } else {
                // 非流式触发的工具调用，需要显示完整的确认框
                ToolExecution execution = new ToolExecution(
                    pendingToolCall.getToolName(),
                    pendingToolCall.getDescription() != null ? pendingToolCall.getDescription() : "执行工具操作",
                    pendingToolCall.getParameters(),
                    true
                );

                // 询问用户确认
                boolean approved = confirmation.askConfirmation(execution);

                if (approved) {
                    // 用户同意，执行工具
                    executeToolCall(pendingToolCall);
                } else {
                    // 用户拒绝
                    context.getUi().displayWarning("⏭️  操作已取消");
                }
            }
        } finally {
            // 清空待处理的工具调用
            pendingToolCall = null;
        }
    }

    /**
     * 🔥 实际执行工具调用
     */
    private void executeToolCall(ToolCall toolCall) {
        try {
            // 🔥 简化输出：只显示简短的执行提示
            // context.getUi().displayInfo("⚙️  正在执行: " + toolCall.getToolName() + "...");

            // 从工具注册表获取工具
            BaseTool tool = context.getToolRegistry().getTool(toolCall.getToolName());

            if (tool == null) {
                context.getUi().displayError("❌ 工具不存在: " + toolCall.getToolName());
                return;
            }

            // 执行工具
            String arguments = convertParametersToJson(toolCall.getParameters());
            ToolResult result = tool.execute(arguments);

            // 🔥 简化输出：只在失败时显示信息
            if (result.isSuccess()) {
                context.getUi().displaySuccess("✅ 完成");
            } else {
                context.getUi().displayError("❌ 失败: " + result.getError());
            }

        } catch (Exception e) {
            context.getUi().displayError("❌ 执行异常: " + e.getMessage());
            // 调试时可以取消注释
            // e.printStackTrace();
        }
    }

    /**
     * 将参数 Map 转换为 JSON 字符串
     */
    private String convertParametersToJson(java.util.Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "{}";
        }

        try {
            // 使用 Jackson 将参数转换为 JSON
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(parameters);
        } catch (Exception e) {
            // 降级：简单的 JSON 拼接
            StringBuilder json = new StringBuilder("{");
            parameters.forEach((key, value) -> {
                json.append("\"").append(key).append("\":\"").append(value).append("\",");
            });
            if (json.length() > 1) {
                json.setLength(json.length() - 1);
            }
            json.append("}");
            return json.toString();
        }
    }

    /**
     * 🔥 设置自动批准模式（用于批量操作）
     */
    public void setAutoApprove(boolean enabled) {
        confirmation.setAutoApproveMode(enabled);
    }

    /**
     * 🔥 检查是否处于自动批准模式
     */
    public boolean isAutoApproveMode() {
        return confirmation.isAutoApproveMode();
    }

    public List<ChatMessage> getHistory() {
        return new ArrayList<>(history);
    }

    public String getSessionId() {
        return sessionId;
    }
}

