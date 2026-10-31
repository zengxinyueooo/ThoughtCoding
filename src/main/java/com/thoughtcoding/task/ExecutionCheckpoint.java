package com.thoughtcoding.task;

import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolCallRef;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.core.ToolExecutionPipeline;

import java.util.List;
import java.util.Map;

/** One committed generation contains task state, history, control state and tool journal. */
public record ExecutionCheckpoint(int schemaVersion, TaskState task, String workspace,
                                  Map<String, String> fileHashes,
                                  Phase phase, int iterations, int stopContinuations,
                                  boolean completionNudge, List<Message> messages,
                                  List<Invocation> invocations, List<String> batch,
                                  Map<String, String> metadata, java.util.Set<String> receipts) {
    public enum Phase { BEFORE_MODEL, TOOLS, AFTER_MODEL, FINISHED }
    public enum InvocationStatus { PREPARED, STARTED, SUCCEEDED, FAILED, UNKNOWN }

    public ExecutionCheckpoint {
        fileHashes = Map.copyOf(fileHashes);
        messages = List.copyOf(messages);
        invocations = List.copyOf(invocations);
        batch = List.copyOf(batch);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        receipts = receipts == null ? java.util.Set.of() : java.util.Set.copyOf(receipts);
    }

    /** Explicit DTO avoids ChatMessage's computed flags and no-op setters during deserialization. */
    public record Message(String role, String content, String toolCallId, String toolName,
                          List<ToolRequest> toolCalls) {
        public static Message from(ChatMessage message) {
            return new Message(message.getRole(), message.getContent(), message.getToolCallId(),
                    message.getToolName(), message.getToolCalls() == null ? null : message.getToolCalls().stream()
                    .map(r -> new ToolRequest(r.getId(), r.getName(), r.getArguments())).toList());
        }

        public ChatMessage toChatMessage() {
            ChatMessage result = new ChatMessage(role, content);
            result.setToolCallId(toolCallId);
            result.setToolName(toolName);
            result.setToolCalls(toolCalls == null ? null : toolCalls.stream()
                    .map(r -> new ToolCallRef(r.id(), r.name(), r.arguments())).toList());
            return result;
        }
    }

    public record ToolRequest(String id, String name, String arguments) { }

    public record Invocation(String invocationId, String providerCallId, String toolName,
                             Map<String, Object> parameters, InvocationStatus status,
                             boolean success, String output, String error, long elapsedMs,
                             boolean blocked) {
        public ToolCall toToolCall() {
            return new ToolCall(toolName, parameters, null, false, 0, false, providerCallId);
        }

        public boolean finished() {
            return status == InvocationStatus.SUCCEEDED || status == InvocationStatus.FAILED;
        }

        public ToolExecutionPipeline.Outcome outcome() {
            if (!finished()) throw new IllegalStateException("工具尚无确定结果: " + invocationId);
            return new ToolExecutionPipeline.Outcome(new ToolResult(success, output, error, elapsedMs), blocked);
        }

        public Invocation withStatus(InvocationStatus next) {
            return new Invocation(invocationId, providerCallId, toolName, parameters, next,
                    success, output, error, elapsedMs, blocked);
        }

        public Invocation withOutcome(ToolExecutionPipeline.Outcome outcome) {
            ToolResult r = outcome.result();
            return new Invocation(invocationId, providerCallId, toolName, parameters,
                    r.isSuccess() ? InvocationStatus.SUCCEEDED : InvocationStatus.FAILED,
                    r.isSuccess(), r.getOutput(), r.getError(), r.getExecutionTime(), outcome.blocked());
        }
    }
}
