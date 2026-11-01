package com.thoughtcoding.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Business-facing tracing facade. No global OTel registration, no network on business threads.
 * Task ownership is carried in Context, never in a shared "current task" field.
 */
public final class Telemetry {
    private static final ContextKey<TaskState> TASK = ContextKey.named("thoughtcoding-task");
    private static final Set<TaskState> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final Set<Observation> OBSERVATIONS = ConcurrentHashMap.newKeySet();
    private static volatile Tracer tracer = OpenTelemetry.noop().getTracer("thoughtcoding");
    private static SdkTracerProvider provider;
    private static volatile boolean enabled;
    private static int payloadLimit = 32768;
    private Telemetry() {}

    public static synchronized void initialize() {
        if (provider != null || !Boolean.parseBoolean(
                System.getenv("THOUGHTCODING_TRACING_ENABLED"))) return;
        try {
            String publicKey = required("LANGFUSE_PUBLIC_KEY");
            String secretKey = required("LANGFUSE_SECRET_KEY");
            String base = required("LANGFUSE_BASE_URL").replaceAll("/+$", "");
            java.net.URI uri = java.net.URI.create(base);
            if (!Set.of("https", "http").contains(uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Invalid Langfuse URL");
            }
            TracePayload.registerSecret(publicKey);
            TracePayload.registerSecret(secretKey);
            String auth = Base64.getEncoder().encodeToString(
                    (publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
            var exporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(base + "/api/public/otel/v1/traces")
                    .addHeader("Authorization", "Basic " + auth)
                    .addHeader("x-langfuse-ingestion-version", "4")
                    .setTimeout(Duration.ofSeconds(5)).build();
            provider = SdkTracerProvider.builder()
                    .setResource(Resource.create(Attributes.of(
                            AttributeKey.stringKey("service.name"), "thoughtcoding")))
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                            .setMaxQueueSize(2048).setMaxExportBatchSize(256)
                            .setScheduleDelay(Duration.ofSeconds(2)).build())
                    .build();
            install(provider);
            Runtime.getRuntime().addShutdownHook(new Thread(Telemetry::shutdown, "tracing-shutdown"));
        } catch (Exception ignored) {
            System.err.println("Tracing disabled: check LANGFUSE_BASE_URL/PUBLIC_KEY/SECRET_KEY.");
        }
    }

    static synchronized void install(SdkTracerProvider sdk) {
        provider = sdk;
        tracer = OpenTelemetrySdk.builder().setTracerProvider(sdk).build().getTracer("thoughtcoding");
        enabled = true;
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name);
        return value.trim();
    }
    public static boolean enabled() { return enabled; }
    public static boolean hasTask() { return Context.current().get(TASK) != null; }
    public static void logicalCall() {
        TaskState state = Context.current().get(TASK);
        if (state != null) state.logicalLlm.incrementAndGet();
    }
    public static void registerSecret(String value) { TracePayload.registerSecret(value); }

    public static Task task(String session, String input) {
        Context base = Context.root();
        Span root = tracer.spanBuilder("task").setParent(base).startSpan();
        TaskState state = new TaskState(root, session);
        ACTIVE.add(state);
        decorate(root, state, "span");
        payload(root, "input", input);
        return new Task(state, base.with(root).with(TASK, state).makeCurrent());
    }
    public static Observation observation(String name, String type, Object input) {
        Context parent = Context.current();
        TaskState state = parent.get(TASK);
        Span span = tracer.spanBuilder(name).setParent(parent).startSpan();
        decorate(span, state, type);
        payload(span, "input", input);
        if (state != null) {
            if ("generation".equals(type)) state.llm.incrementAndGet();
            if ("tool".equals(type)) state.tools.incrementAndGet();
            if ("agent.subagent".equals(name)) state.subagents.incrementAndGet();
        }
        return new Observation(span, parent.with(span), state);
    }
    private static void decorate(Span span, TaskState state, String type) {
        span.setAttribute("langfuse.observation.type", type);
        span.setAttribute("langfuse.environment",
                System.getenv().getOrDefault("THOUGHTCODING_ENVIRONMENT", "local"));
        if (state != null) {
            span.setAttribute("langfuse.session.id", state.session);
            span.setAttribute("langfuse.trace.name", "ThoughtCoding task");
            span.setAttribute("langfuse.observation.metadata.task_id", state.id);
        }
    }
    private static void payload(Span span, String field, Object value) {
        if (span.isRecording()) span.setAttribute("langfuse.observation." + field,
                TracePayload.limited(value, payloadLimit));
    }
    public static void taskStatus(String status) {
        TaskState state = Context.current().get(TASK);
        if (state != null) state.status = status;
    }
    public static void currentStatus(String status) {
        Span.current().setAttribute("langfuse.observation.metadata.status", status);
        if ("error".equals(status) || "timeout".equals(status)) {
            Span.current().setStatus(StatusCode.ERROR);
        }
    }
    public static Runnable wrap(Runnable runnable) { return Context.current().wrap(runnable); }
    public static <T> Callable<T> wrap(Callable<T> callable) { return Context.current().wrap(callable); }

    /** Retain before submission so a fast foreground exit cannot finish the root prematurely. */
    public static Background background(Runnable runnable) {
        return new Background(Context.current(), runnable);
    }
    public static final class Background implements Runnable, AutoCloseable {
        private final Context context;
        private final TaskState state;
        private final Runnable action;
        private final AtomicBoolean released = new AtomicBoolean();
        private Background(Context context, Runnable action) {
            this.context = context;
            this.state = context.get(TASK);
            this.action = action;
            if (state != null) state.pending.incrementAndGet();
        }
        @Override public void run() {
            try (Scope ignored = context.makeCurrent()) { action.run(); }
            finally { close(); }
        }
        @Override public void close() {
            if (released.compareAndSet(false, true) && state != null) state.release();
        }
    }

    public static final class Observation implements AutoCloseable {
        private final Span span;
        private final Context context;
        private final TaskState state;
        private final long start = System.nanoTime();
        private final AtomicBoolean ended = new AtomicBoolean();
        private final AtomicBoolean failed = new AtomicBoolean();
        private final AtomicBoolean firstToken = new AtomicBoolean();
        private Observation(Span span, Context context, TaskState state) {
            this.span = span; this.context = context; this.state = state;
            OBSERVATIONS.add(this);
        }
        public Scope activate() { return context.makeCurrent(); }
        public void attribute(String name, String value) {
            if (value != null && !ended.get()) span.setAttribute(name, TracePayload.clean(value));
        }
        public void metadata(String key, Object value) {
            attribute("langfuse.observation.metadata." + key, String.valueOf(value));
        }
        public void output(Object output) { if (!ended.get()) payload(span, "output", output); }
        public void firstToken() {
            if (!ended.get() && firstToken.compareAndSet(false, true)) {
                attribute("langfuse.observation.completion_start_time", java.time.Instant.now().toString());
            }
        }
        public void error(String message) {
            if (ended.get() || !failed.compareAndSet(false, true)) return;
            span.setStatus(StatusCode.ERROR);
            attribute("langfuse.observation.level", "ERROR");
            attribute("langfuse.observation.status_message", TracePayload.limited(message, 2048));
            metadata("status", "error");
            if (state != null) {
                state.errors.incrementAndGet();
                state.firstError.compareAndSet(null, TracePayload.limited(message, 2048));
            }
        }
        public void error(Throwable error) {
            metadata("error_type", error.getClass().getName());
            error(error.getMessage());
        }
        @Override public void close() {
            if (ended.compareAndSet(false, true)) {
                span.setAttribute("langfuse.observation.metadata.latency_ms",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                span.end();
                OBSERVATIONS.remove(this);
            }
        }
    }
    public static final class Task implements AutoCloseable {
        private final TaskState state;
        private final Scope scope;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Task(TaskState state, Scope scope) { this.state = state; this.scope = scope; }
        public String traceId() { return state.root.getSpanContext().getTraceId(); }
        public void output(Object output) { payload(state.root, "output", output); }
        public void status(String status) { state.status = status; }
        public void error(Throwable error) {
            state.status = "error";
            state.errors.incrementAndGet();
            state.root.setAttribute("langfuse.observation.status_message",
                    TracePayload.limited(error.getMessage(), 2048));
        }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                state.root.setAttribute("langfuse.observation.metadata.foreground_latency_ms", state.elapsed());
                scope.close();
                state.release();
            }
        }
    }
    private static final class TaskState {
        final String id = UUID.randomUUID().toString();
        final String session;
        final Span root;
        final long start = System.nanoTime();
        final AtomicInteger pending = new AtomicInteger(1);
        final AtomicLong llm = new AtomicLong(), tools = new AtomicLong(),
                subagents = new AtomicLong(), errors = new AtomicLong(), logicalLlm = new AtomicLong();
        final AtomicReference<String> firstError = new AtomicReference<>();
        final AtomicBoolean ended = new AtomicBoolean();
        volatile String status = "success";
        TaskState(Span root, String session) {
            this.root = root; this.session = session == null ? "local" : session;
        }
        long elapsed() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
        void release() { if (pending.decrementAndGet() == 0) finish(); }
        void finish() {
            if (!ended.compareAndSet(false, true)) return;
            root.setAttribute("langfuse.observation.metadata.e2e_latency_ms", elapsed());
            root.setAttribute("langfuse.observation.metadata.llm_call_count", llm.get());
            root.setAttribute("langfuse.observation.metadata.llm_logical_call_count", logicalLlm.get());
            root.setAttribute("langfuse.observation.metadata.tool_call_count", tools.get());
            root.setAttribute("langfuse.observation.metadata.subagent_count", subagents.get());
            root.setAttribute("langfuse.observation.metadata.error_count", errors.get());
            if (firstError.get() != null) {
                root.setAttribute("langfuse.observation.metadata.first_error", firstError.get());
            }
            root.setAttribute("langfuse.observation.metadata.status", status);
            if ("error".equals(status) || "timeout".equals(status)) root.setStatus(StatusCode.ERROR);
            root.end();
            ACTIVE.remove(this);
        }
    }
    public static synchronized void shutdown() {
        if (provider == null) return;
        for (Observation observation : OBSERVATIONS) {
            observation.metadata("status", "shutdown");
            observation.close();
        }
        for (TaskState state : ACTIVE) {
            state.status = "shutdown";
            state.finish();
        }
        provider.forceFlush().join(5, TimeUnit.SECONDS);
        provider.shutdown().join(5, TimeUnit.SECONDS);
        provider = null;
        enabled = false;
        tracer = OpenTelemetry.noop().getTracer("thoughtcoding");
    }
}
