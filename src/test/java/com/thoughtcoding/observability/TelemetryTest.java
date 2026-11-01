package com.thoughtcoding.observability;

import com.thoughtcoding.core.CancelToken;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelemetryTest {
    InMemorySpanExporter exporter;
    @BeforeEach void setup() {
        exporter = InMemorySpanExporter.create();
        Telemetry.install(SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build());
    }
    @AfterEach void cleanup() { Telemetry.shutdown(); }
    SpanData span(String name) {
        return exporter.getFinishedSpanItems().stream().filter(s -> s.getName().equals(name))
                .findFirst().orElseThrow();
    }
    String string(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }
    long count(SpanData span, String key) {
        return Objects.requireNonNull(span.getAttributes().get(
                AttributeKey.longKey("langfuse.observation.metadata." + key)));
    }

    @Test void backgroundKeepsOriginalTraceWhileNextTaskRuns() throws Exception {
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        Thread thread;
        try (var task = Telemetry.task("session-a", "first");
             var main = Telemetry.observation("agent.main", "agent", "first");
             var scope = main.activate()) {
            var background = Telemetry.background(() -> {
                started.countDown();
                try { finish.await(); } catch (InterruptedException error) { throw new RuntimeException(error); }
                try (var sub = Telemetry.observation("agent.subagent", "agent", "work");
                     var subScope = sub.activate();
                     var tool = Telemetry.observation("tool.read", "tool", Map.of("path", "a"))) {
                    tool.output("content");
                }
            });
            thread = Thread.ofVirtual().start(background);
            assertTrue(started.await(2, TimeUnit.SECONDS));
        }
        assertTrue(exporter.getFinishedSpanItems().stream().noneMatch(s -> s.getName().equals("task")));
        try (var next = Telemetry.task("session-a", "second")) { next.output("done"); }
        String nextId = span("task").getTraceId();
        finish.countDown();
        thread.join(3000);
        assertFalse(thread.isAlive());
        SpanData main = span("agent.main"), sub = span("agent.subagent"), tool = span("tool.read");
        assertEquals(main.getTraceId(), sub.getTraceId());
        assertEquals(main.getSpanId(), sub.getParentSpanId());
        assertEquals(sub.getSpanId(), tool.getParentSpanId());
        assertNotEquals(nextId, sub.getTraceId());
        SpanData root = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("task") && s.getTraceId().equals(sub.getTraceId()))
                .findFirst().orElseThrow();
        assertEquals(1, count(root, "subagent_count"));
        assertEquals(1, count(root, "tool_call_count"));
        assertEquals("session-a", string(tool, "langfuse.session.id"));
        assertEquals(Context.root(), Context.current());
    }

    @Test void streamRecordsUsageAndIgnoresDuplicateCompletion() {
        AtomicReference<StreamingChatResponseHandler> callback = new AtomicReference<>();
        StreamingChatModel model = mock(StreamingChatModel.class);
        doAnswer(inv -> { callback.set(inv.getArgument(1)); return null; })
                .when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        CompletableFuture<Void> completion = new CompletableFuture<>();
        AtomicInteger delivered = new AtomicInteger();
        try (var task = Telemetry.task("session", "hi")) {
            ModelTracing.stream(model, request(), new StreamingChatResponseHandler() {
                public void onPartialResponse(String text) {}
                public void onCompleteResponse(ChatResponse response) {
                    delivered.incrementAndGet(); completion.complete(null);
                }
                public void onError(Throwable error) { fail(error); }
            }, "configured-model", new CancelToken(), completion);
            callback.get().onPartialResponse("hello");
            ChatResponse response = response();
            callback.get().onCompleteResponse(response);
            callback.get().onCompleteResponse(response);
        }
        assertEquals(1, delivered.get());
        SpanData llm = span("llm.stream");
        assertTrue(string(llm, "langfuse.observation.usage_details").contains("\"input\":12"));
        assertTrue(string(llm, "langfuse.observation.output").contains("hello"));
        assertEquals(1, count(span("task"), "llm_call_count"));
        assertNotNull(string(llm, "langfuse.observation.completion_start_time"));
    }

    @Test void cancellationEndsSpanOnceAndDiscardsLateResponse() {
        StreamingChatModel model = mock(StreamingChatModel.class);
        AtomicReference<StreamingChatResponseHandler> callback = new AtomicReference<>();
        doAnswer(inv -> { callback.set(inv.getArgument(1)); return null; })
                .when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        CancelToken token = new CancelToken();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        token.onCancel(() -> completion.complete(null));
        var handler = mock(StreamingChatResponseHandler.class);
        try (var task = Telemetry.task("session", "hi")) {
            ModelTracing.stream(model, request(), handler, "model", token, completion);
            token.cancel();
            callback.get().onCompleteResponse(response());
            callback.get().onError(new RuntimeException("late"));
        }
        verifyNoInteractions(handler);
        assertEquals(1, count(span("task"), "llm_call_count"));
        assertEquals("cancelled", string(span("llm.stream"), "langfuse.observation.metadata.status"));
    }

    @Test void retryIsSiblingAndFailureDoesNotMakeRecoveredTaskFail() throws Exception {
        StreamingChatModel model = mock(StreamingChatModel.class);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(inv -> {
            StreamingChatResponseHandler callback = inv.getArgument(1);
            if (attempts.incrementAndGet() == 1) callback.onError(new RuntimeException("429"));
            else callback.onCompleteResponse(response());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        CompletableFuture<Void> completion = new CompletableFuture<>();
        try (var executor = Executors.newSingleThreadExecutor();
             var task = Telemetry.task("session", "hi");
             var agent = Telemetry.observation("agent.main", "agent", "hi");
             var scope = agent.activate()) {
            StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
                public void onPartialResponse(String text) {}
                public void onCompleteResponse(ChatResponse response) { completion.complete(null); }
                public void onError(Throwable error) {
                    executor.submit(Telemetry.wrap(() ->
                            ModelTracing.stream(model, request(), this, "model", null, completion)));
                }
            };
            ModelTracing.stream(model, request(), handler, "model", null, completion);
            completion.get(3, TimeUnit.SECONDS);
        }
        var generations = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("llm.stream")).toList();
        assertEquals(2, generations.size());
        generations.forEach(s -> assertEquals(span("agent.main").getSpanId(), s.getParentSpanId()));
        assertEquals(2, count(span("task"), "llm_call_count"));
        assertEquals(1, count(span("task"), "error_count"));
        assertNotEquals(StatusCode.ERROR, span("task").getStatus().getStatusCode());
    }

    @Test void missingUsageIsUnknownAndPayloadDoesNotLeakSecrets() {
        String secret = "test-only-private-credential";
        Telemetry.registerSecret(secret);
        try (var task = Telemetry.task("session", "api_key=" + secret)) {
            ModelTracing.sync("memory", "model", secret, () ->
                    ChatResponse.builder().aiMessage(AiMessage.from("password=hidden")).build());
        }
        String all = exporter.getFinishedSpanItems().toString();
        assertFalse(all.contains(secret));
        assertFalse(all.contains("password=hidden"));
        SpanData llm = span("llm.memory");
        assertNull(string(llm, "langfuse.observation.usage_details"));
        assertEquals("false", string(llm, "langfuse.observation.metadata.usage_available"));
        assertEquals(1, count(span("task"), "llm_logical_call_count"));
    }

    @Test void releasedUnsubmittedBackgroundDoesNotLeakTask() {
        try (var task = Telemetry.task("session", "hi")) {
            var background = Telemetry.background(() -> fail("should not run"));
            background.close();
            background.close();
        }
        assertEquals(1, exporter.getFinishedSpanItems().size());
    }

    @Test void toolPipelinePropagatesContextAndRecordsBlockedAndReturnedErrors() {
        var registry = new com.thoughtcoding.tool.ToolRegistry(null);
        registry.register(new com.thoughtcoding.tool.BaseTool("read", "test") {
            public com.thoughtcoding.model.ToolResult execute(String input) {
                try (var child = Telemetry.observation("inside.worker", "span", input)) {
                    return com.thoughtcoding.model.ToolResult.error("synthetic failure", 5);
                }
            }
        });
        var hooks = new com.thoughtcoding.hook.HookRegistry();
        var pipeline = new com.thoughtcoding.core.ToolExecutionPipeline(null, hooks, registry);
        try (var task = Telemetry.task("session", "tool test")) {
            var call = new com.thoughtcoding.model.ToolCall("read", Map.of(), null, false, 0, false, "call-1");
            assertFalse(pipeline.execute(call, null, List.of()).result().isSuccess());
            hooks.register(com.thoughtcoding.hook.HookType.PRE_TOOL_USE,
                    context -> com.thoughtcoding.hook.HookResult.block("denied"));
            assertTrue(pipeline.execute(call, null, List.of()).isBlocked());
        }
        var tools = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("tool.read")).toList();
        assertEquals(2, tools.size());
        assertEquals(tools.getFirst().getSpanId(), span("inside.worker").getParentSpanId());
        assertEquals(StatusCode.ERROR, tools.getFirst().getStatus().getStatusCode());
        assertEquals("blocked", string(tools.getLast(), "langfuse.observation.metadata.status"));
        assertEquals(2, count(span("task"), "tool_call_count"));
        assertEquals(1, count(span("task"), "error_count"));
    }

    @Test void disabledTracingDoesNotExportOrChangeReturnValue() {
        Telemetry.shutdown();
        try (var task = Telemetry.task("session", "hi")) {
            assertEquals("hello", ModelTracing.sync("memory", "model", "hi",
                    TelemetryTest::response).aiMessage().text());
        }
        assertFalse(Telemetry.enabled());
    }
    private static ChatRequest request() {
        return ChatRequest.builder().messages(UserMessage.from("hi")).build();
    }
    private static ChatResponse response() {
        return ChatResponse.builder().aiMessage(AiMessage.from("hello"))
                .tokenUsage(new TokenUsage(12, 3)).build();
    }
}
