package com.thoughtcoding.observability;

import com.thoughtcoding.core.CancelToken;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One generation per actual application-level model attempt, including explicit retries. */
public final class ModelTracing {
    private ModelTracing() {}
    public static void stream(StreamingChatModel model, ChatRequest request,
                              StreamingChatResponseHandler handler, String modelName,
                              CancelToken token, CompletableFuture<?> completion) {
        if (completion.isDone() || (token != null && token.isCancelled())) return;
        Context parent = Context.current();
        var observation = Telemetry.observation("llm.stream", "generation",
                request.messages().stream().map(ModelTracing::message).toList());
        observation.metadata("tool_definitions", String.valueOf(request.toolSpecifications()));
        observation.attribute("langfuse.observation.model.name", modelName);
        observation.metadata("usage_available", false);
        AtomicBoolean terminal = new AtomicBoolean();
        StringBuilder partial = new StringBuilder();
        // Covers cancellation, timeout and stop without waiting for a late HTTP callback.
        completion.whenComplete((value, error) -> {
            if (terminal.compareAndSet(false, true)) {
                boolean cancelled = token != null && token.isCancelled();
                if (!cancelled) observation.error("Model wait ended before response (timeout or interruption)");
                observation.metadata("status", cancelled ? "cancelled" : "abandoned");
                synchronized (partial) { observation.output(assistant(partial.toString())); }
                observation.close();
            }
        });
        try {
            model.chat(request, new StreamingChatResponseHandler() {
                @Override public void onPartialResponse(String text) {
                    if (terminal.get()) return;
                    observation.firstToken();
                    synchronized (partial) {
                        if (partial.length() < 32768) partial.append(text);
                    }
                    try (Scope ignored = parent.makeCurrent()) { handler.onPartialResponse(text); }
                }
                @Override public void onCompleteResponse(ChatResponse response) {
                    if (!terminal.compareAndSet(false, true)) return;
                    record(observation, response);
                    observation.close();
                    try (Scope ignored = parent.makeCurrent()) { handler.onCompleteResponse(response); }
                }
                @Override public void onError(Throwable error) {
                    if (!terminal.compareAndSet(false, true)) return;
                    observation.error(error);
                    synchronized (partial) { observation.output(assistant(partial.toString())); }
                    observation.close();
                    try (Scope ignored = parent.makeCurrent()) { handler.onError(error); }
                }
            });
        } catch (RuntimeException error) {
            if (terminal.compareAndSet(false, true)) {
                observation.error(error);
                observation.close();
            }
            throw error;
        }
    }
    public static ChatResponse sync(String purpose, String modelName, String prompt,
                                    Supplier<ChatResponse> call) {
        if (!Telemetry.hasTask()) {
            try (var task = Telemetry.task("maintenance", purpose)) {
                try {
                    ChatResponse response = sync(purpose, modelName, prompt, call);
                    task.output(response.aiMessage() == null ? "" : response.aiMessage().text());
                    return response;
                } catch (RuntimeException error) {
                    task.status("error");
                    throw error;
                }
            }
        }
        Telemetry.logicalCall();
        try (var observation = Telemetry.observation("llm." + purpose, "generation",
                List.of(Map.of("role", "user", "content", prompt)))) {
            observation.attribute("langfuse.observation.model.name", modelName);
            observation.metadata("usage_available", false);
            try {
                ChatResponse response = call.get();
                record(observation, response);
                return response;
            } catch (RuntimeException error) {
                observation.error(error);
                throw error;
            }
        }
    }
    private static void record(Telemetry.Observation observation, ChatResponse response) {
        if (response == null) return;
        observation.metadata("status", "success");
        if (response.modelName() != null) {
            observation.attribute("langfuse.observation.model.name", response.modelName());
        }
        if (response.aiMessage() != null) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("role", "assistant");
            output.put("content", response.aiMessage().text());
            var calls = response.aiMessage().toolExecutionRequests();
            output.put("tool_calls", calls == null ? List.of() : calls.stream().map(call ->
                    toolCall(call)).toList());
            observation.output(List.of(output));
        }
        var usage = response.tokenUsage();
        Map<String, Integer> details = new LinkedHashMap<>();
        if (usage != null) {
            if (usage.inputTokenCount() != null) details.put("input", usage.inputTokenCount());
            if (usage.outputTokenCount() != null) details.put("output", usage.outputTokenCount());
            if (usage.totalTokenCount() != null) details.put("total", usage.totalTokenCount());
        }
        if (details.isEmpty()) observation.metadata("usage_available", false);
        else {
            observation.metadata("usage_available", true);
            observation.attribute("langfuse.observation.usage_details", TracePayload.json(details));
        }
    }
    private static Map<String, Object> message(dev.langchain4j.data.message.ChatMessage message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("role", message.type().name().toLowerCase(Locale.ROOT));
        if (message instanceof dev.langchain4j.data.message.SystemMessage system) {
            data.put("content", system.text());
        } else if (message instanceof dev.langchain4j.data.message.UserMessage user) {
            data.put("content", user.hasSingleText() ? user.singleText() : user.contents().toString());
        } else if (message instanceof dev.langchain4j.data.message.AiMessage ai) {
            data.put("role", "assistant");
            data.put("content", ai.text());
            data.put("tool_calls", ai.toolExecutionRequests() == null ? List.of() :
                    ai.toolExecutionRequests().stream().map(call ->
                            toolCall(call)).toList());
        } else if (message instanceof dev.langchain4j.data.message.ToolExecutionResultMessage tool) {
            data.put("role", "tool");
            data.put("content", tool.text());
            data.put("tool_call_id", tool.id());
            data.put("name", tool.toolName());
        } else data.put("content", message.toString());
        return data;
    }
    private static Map<String, Object> toolCall(dev.langchain4j.agent.tool.ToolExecutionRequest call) {
        return Map.of("id", String.valueOf(call.id()), "type", "function",
                "function", Map.of("name", call.name(), "arguments", String.valueOf(call.arguments())));
    }
    private static List<Map<String, String>> assistant(String text) {
        return List.of(Map.of("role", "assistant", "content", text));
    }
}
