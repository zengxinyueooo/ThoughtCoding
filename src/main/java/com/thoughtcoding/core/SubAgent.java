package com.thoughtcoding.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.hook.DuplicateToolCallGuard;
import com.thoughtcoding.hook.HookRegistry;
import com.thoughtcoding.hook.HookType;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.SubagentTurn;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolCallRef;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.security.PermissionHook;
import com.thoughtcoding.ui.ThoughtCodingUI;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SubAgent循环 —— 在【全新、隔离】的对话历史里跑一个独立的 agentic 循环。
 *
 * <p>与主 {@link AgentLoop} 的区别：
 * <ul>
 *   <li>自带独立 history（不碰主对话），只回传最终结论，中间过程丢弃；</li>
 *   <li>看不到 {@code subAgent} 工具（已从其可见的工具规格中过滤），因此不可能递归；</li>
 *   <li>内部工具调用照样走 PRE_TOOL_USE 权限管道（写/执行类仍需确认）；</li>
 *   <li>调用 {@link com.thoughtcoding.service.AIService#chatOnceForSubagent} —— 隔离的模型往返，
 *       不抢占主循环共享的流式回调/生成状态。</li>
 * </ul>
 *
 * <p>由 {@code SubAgentTool} 在被 dispatch 时同步创建并运行；此时主循环已阻塞在 dispatch 中，
 * 复用同一个底层模型是顺序、无并发的，安全。
 */
public class SubAgent {

    private final ThoughtCodingContext context;

    public SubAgent(ThoughtCodingContext context) {
        this.context = context;
    }

    /**
     * 运行SubAgent直到得出结论或达到最大轮次。
     *
     * @param subAgentPrompt 交给SubAgent的详细任务指令（作为它的首条 user 消息）
     * @param label      简短标签，仅用于终端展示
     * @param token      取消令牌（主回合 token 或后台任务私有 token），可为 null（不可取消）
     * @return SubAgent的最终结论文本（唯一回传给主Agent的内容）
     */
    public String run(String subAgentPrompt, String label, CancelToken token) {
        return run(subAgentPrompt, label, token, null);
    }

    public String run(String prompt, String label, CancelToken token,
                      com.thoughtcoding.task.TaskService.Run task) {
        return execute(prompt, label, token, task, false);
    }

    public String resume(String label, CancelToken token, com.thoughtcoding.task.TaskService.Run task) {
        return execute(task.state().objective(), label, token, task, true);
    }

    private String execute(String prompt, String label, CancelToken token,
                           com.thoughtcoding.task.TaskService.Run task, boolean resume) {
        ThoughtCodingUI ui = context.getUi();
        ObjectMapper mapper = new ObjectMapper();
        List<ChatMessage> history = new ArrayList<>();
        if (resume) task.checkpoint().messages().forEach(m -> history.add(m.toChatMessage()));
        else history.add(new ChatMessage("user", prompt));
        int total = task == null ? 0 : task.checkpoint().iterations();
        boolean pendingBatch = resume && task.checkpoint().phase() == com.thoughtcoding.task.ExecutionCheckpoint.Phase.TOOLS;
        if (resume && task.checkpoint().phase() == com.thoughtcoding.task.ExecutionCheckpoint.Phase.FINISHED) {
            task.status(com.thoughtcoding.task.TaskStatus.WAITING_INPUT, "RESPONSE_COMPLETE");
            return finalText(history);
        }
        if (pendingBatch) com.thoughtcoding.task.RecoveryHistory.removeCurrentBatchResults(history, task.checkpoint());
        ToolExecutionConfirmation confirmation = new ToolExecutionConfirmation(ui, ui.getLineReader(),
                "[SubAgent " + label + "]", context::getConsoleInputRouter);
        HookRegistry hooks = context.getHookRegistry().copy();
        hooks.registerFirst(HookType.PRE_TOOL_USE, new PermissionHook(confirmation));
        hooks.registerFirst(HookType.PRE_TOOL_USE, new DuplicateToolCallGuard());
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(context, hooks, context.getToolRegistry(), task);
        String systemPrompt = context.getContextManager().buildSubagentSystemPrompt();
        for (int iteration = 0; iteration < 30; iteration++) {
            if (token != null && token.isCancelled()) return stop(task, token);
            List<ToolCall> calls;
            if (pendingBatch) {
                calls = task.checkpoint().batch().stream().map(task::invocation)
                        .map(com.thoughtcoding.task.ExecutionCheckpoint.Invocation::toToolCall).toList();
                pendingBatch = false;
            } else {
                if (task != null) task.boundary(com.thoughtcoding.task.ExecutionCheckpoint.Phase.BEFORE_MODEL,
                        history, total, 0, false);
                // Task state is request-local and is counted by the existing context budget pipeline.
                List<ChatMessage> request = new ArrayList<>(history);
                if (task != null) request.add(new ChatMessage("user", task.modelSummary()));
                StringBuilder lineBuffer = new StringBuilder();
                SubagentTurn turn = context.getAiService().chatOnceForSubagent(systemPrompt, request,
                        text -> { lineBuffer.append(text); emitCompletedLines(ui, lineBuffer); }, token);
                if (!lineBuffer.isEmpty()) printLine(ui, lineBuffer.toString());
                if (token != null && token.isCancelled()) return stop(task, token);
                if (turn.isFailed()) {
                    if (task != null) task.status(com.thoughtcoding.task.TaskStatus.FAILED, turn.getText());
                    return turn.getText();
                }
                if (!turn.hasToolCalls()) {
                    history.add(new ChatMessage("assistant", turn.getText()));
                    if (task != null) {
                        task.boundary(com.thoughtcoding.task.ExecutionCheckpoint.Phase.FINISHED, history, total, 0, false);
                        task.status(com.thoughtcoding.task.TaskStatus.WAITING_INPUT, "RESPONSE_COMPLETE");
                    }
                    return turn.getText();
                }
                history.add(ChatMessage.assistantWithToolCalls(turn.getText(), turn.getToolCalls()));
                calls = turn.getToolCalls().stream().map(ref -> new ToolCall(ref.getName(),
                        parseArgs(mapper, ref.getArguments()), null, false, 0, false, ref.getId())).toList();
                if (task != null) task.prepare(calls, history);
            }
            for (ToolCall call : calls) {
                if (token != null && token.isCancelled()) return stop(task, token);
                pipeline.executeAndRecord(call, token, history);
                if (task != null) {
                    task.boundary(com.thoughtcoding.task.ExecutionCheckpoint.Phase.TOOLS, history, total, 0, false);
                    if (task.hasUnknown()) {
                        if (token != null && token.isCancelled()) return stop(task, token);
                        task.status(com.thoughtcoding.task.TaskStatus.WAITING_INPUT, "UNKNOWN_TOOL_RESULT");
                        return "工具结果不明，需要核验。任务 ID: " + task.state().taskId();
                    }
                }
            }
            total++;
            if (task != null) task.boundary(com.thoughtcoding.task.ExecutionCheckpoint.Phase.BEFORE_MODEL,
                    history, total, 0, false);
        }
        if (task != null) task.status(com.thoughtcoding.task.TaskStatus.PAUSED, "ITERATION_LIMIT");
        return "SubAgent 已达到工具轮次上限，使用 /task resume 继续。";
    }

    private static String finalText(List<ChatMessage> history) {
        return history.stream().filter(m -> m.isAssistantMessage() && !m.hasToolCalls())
                .map(ChatMessage::getContent).filter(java.util.Objects::nonNull).reduce((a, b) -> b).orElse("");
    }

    private static String stop(com.thoughtcoding.task.TaskService.Run task, CancelToken token) {
        if (task != null) task.status(token.isPauseRequested() ? com.thoughtcoding.task.TaskStatus.PAUSED
                : com.thoughtcoding.task.TaskStatus.CANCELLED, "USER_STOPPED");
        return "子任务已停止。";
    }

    // ── 显示辅助（纯文本、无装饰 emoji）──

    private void printLine(ThoughtCodingUI ui, String text) {
        if (ui == null) return;
        ui.printAbove(text);
    }

    /** 把行缓冲中已完成的行经 printAbove 上屏；半行片段留在缓冲里等后续 token。 */
    private void emitCompletedLines(ThoughtCodingUI ui, StringBuilder buffer) {
        if (ui == null) return;
        int newline;
        while ((newline = buffer.indexOf("\n")) >= 0) {
            String line = buffer.substring(0, newline);
            buffer.delete(0, newline + 1);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            ui.printAbove(line);
        }
    }

    /** 解析工具参数 JSON 为 Map；失败则退化为 {"input": 原始串}（与主服务一致的兜底）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(ObjectMapper mapper, String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return mapper.readValue(argumentsJson, Map.class);
        } catch (Exception e) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("input", argumentsJson);
            return fallback;
        }
    }

    /** 从参数里挑一个有代表性的值做单行摘要（command/path/pattern 等），仅用于展示。 */
    private String argSummary(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "";
        for (String key : new String[]{"command", "path", "file_path", "pattern"}) {
            Object v = params.get(key);
            if (v != null) return " (" + oneLine(v.toString(), 60) + ")";
        }
        return "";
    }

    private String oneLine(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
