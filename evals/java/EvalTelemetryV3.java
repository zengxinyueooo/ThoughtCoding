package com.thoughtcoding.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Evaluation v3 only: durable original OTLP spans, without a network exporter. */
public final class EvalTelemetryV3 {
    static final ObjectMapper JSON = new ObjectMapper();
    private static DurableExporter installed;
    private EvalTelemetryV3() {}

    public static synchronized void initialize(Path evidence) throws IOException {
        if (installed != null || Telemetry.enabled()) throw new IllegalStateException("Tracing already initialized");
        DurableExporter exporter = new DurableExporter(evidence);
        installed = exporter;
        Telemetry.install(SdkTracerProvider.builder()
                .setResource(Resource.create(Attributes.builder().put("service.name", "thoughtcoding").build()))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build());
    }

    /** Called from the versioned adapter's finally block, including Agent exceptions. */
    public static synchronized void finish(boolean agentCompleted) throws IOException {
        if (installed == null) return;
        DurableExporter exporter = installed;
        try {
            try { Telemetry.shutdown(); }
            catch (RuntimeException error) { exporter.fail(error); }
            exporter.complete(agentCompleted);
        } finally { installed = null; }
    }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    // Each run exclusively creates its spool directory. Never reuse an old directory.
    static void durableNewFile(Path path, byte[] bytes) throws IOException {
        if (Files.exists(path)) throw new FileAlreadyExistsException(path.toString());
        Path temporary = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        // No fallback to a non-atomic move: an unsupported FS invalidates the run.
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
    }

    static final class DurableExporter implements SpanExporter {
        final Path evidence, spool, receipt;
        final List<Map<String,Object>> files = new ArrayList<>();
        String failureType;
        int summaryCount;
        boolean shutdown, completed;

        DurableExporter(Path evidence) throws IOException {
            this.evidence = evidence.toAbsolutePath().normalize();
            this.spool = this.evidence.resolveSibling("otlp-spool");
            this.receipt = this.evidence.resolveSibling("otlp-spool-completion.json");
            Files.createDirectories(this.evidence.getParent());
            if (Files.exists(this.evidence) || Files.exists(receipt))
                throw new FileAlreadyExistsException("Run evidence already exists");
            Files.createDirectory(spool);
        }

        synchronized void fail(Throwable error) {
            if (failureType == null) failureType = error.getClass().getSimpleName();
        }

        public synchronized CompletableResultCode export(Collection<SpanData> spans) {
            if (failureType != null || shutdown || completed) return CompletableResultCode.ofFailure();
            try {
                for (SpanData span : spans) {
                    if (!span.hasEnded()) throw new IllegalArgumentException("Only completed spans may be persisted");
                    byte[] raw = JSON.writeValueAsBytes(payload(span));
                    String name = span.getTraceId() + "-" + span.getSpanId() + ".json";
                    durableNewFile(spool.resolve(name), raw);
                    files.add(Map.of("file", "otlp-spool/" + name, "trace_id", span.getTraceId(),
                            "span_id", span.getSpanId(), "sha256", hash(raw)));
                    byte[] summary = (JSON.writeValueAsString(summary(span)) + "\n").getBytes(StandardCharsets.UTF_8);
                    try (FileChannel channel = FileChannel.open(evidence, StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                        ByteBuffer buffer = ByteBuffer.wrap(summary);
                        while (buffer.hasRemaining()) channel.write(buffer);
                        channel.force(true);
                    }
                    summaryCount++;
                }
                return CompletableResultCode.ofSuccess();
            } catch (Exception error) {
                fail(error);
                System.err.println("Evaluation v3 span persistence failed; run evidence is incomplete.");
                return CompletableResultCode.ofFailure();
            }
        }

        public synchronized CompletableResultCode flush() {
            return failureType == null ? CompletableResultCode.ofSuccess() : CompletableResultCode.ofFailure();
        }
        public synchronized CompletableResultCode shutdown() { shutdown = true; return flush(); }

        synchronized void complete(boolean agentCompleted) throws IOException {
            if (completed) throw new IllegalStateException("Completion receipt already written");
            completed = true;
            boolean complete = failureType == null && shutdown && summaryCount == files.size();
            Map<String,Object> body = new LinkedHashMap<>();
            body.put("schema_version", 3);
            body.put("complete", complete);
            body.put("agent_completed", agentCompleted);
            body.put("exported_span_count", files.size());
            body.put("summary_span_count", summaryCount);
            body.put("span_files", files);
            body.put("failure_type", failureType);
            body.put("transport", "local_durable_otlp_no_network");
            durableNewFile(receipt, JSON.writeValueAsBytes(body));
            if (!complete) throw new IOException("Evaluation v3 tracing evidence is incomplete");
        }
    }

    static List<Map<String,Object>> attributes(Attributes attributes) {
        List<Map<String,Object>> result = new ArrayList<>();
        attributes.forEach((key, value) -> result.add(Map.of("key", key.getKey(), "value", any(value))));
        result.sort(Comparator.comparing(row -> String.valueOf(row.get("key"))));
        return result;
    }

    static Map<String,Object> any(Object value) {
        if (value instanceof Value<?> typed) {
            if (typed.getType() == ValueType.EMPTY) return Map.of();
            if (typed.getType() == ValueType.KEY_VALUE_LIST) {
                List<Map<String,Object>> items = new ArrayList<>();
                for (Object item : (List<?>) typed.getValue()) {
                    KeyValue pair = (KeyValue) item;
                    items.add(Map.of("key", pair.getKey(), "value", any(pair.getValue())));
                }
                return Map.of("kvlistValue", Map.of("values", items));
            }
            return any(typed.getValue());
        }
        if (value instanceof String text) return Map.of("stringValue", TracePayload.clean(text));
        if (value instanceof Boolean flag) return Map.of("boolValue", flag);
        if (value instanceof Long || value instanceof Integer) return Map.of("intValue", value.toString());
        if (value instanceof Double number) {
            Object encoded = Double.isNaN(number) ? "NaN" : number == Double.POSITIVE_INFINITY ? "Infinity"
                    : number == Double.NEGATIVE_INFINITY ? "-Infinity" : number;
            return Map.of("doubleValue", encoded);
        }
        if (value instanceof List<?> values) return Map.of("arrayValue", Map.of("values", values.stream().map(EvalTelemetryV3::any).toList()));
        if (value instanceof ByteBuffer buffer) {
            ByteBuffer copy = buffer.duplicate(); byte[] bytes = new byte[copy.remaining()]; copy.get(bytes);
            return Map.of("bytesValue", Base64.getEncoder().encodeToString(bytes));
        }
        throw new IllegalArgumentException("Unsupported OTel value type");
    }

    static String traceState(SpanContext context) {
        return String.join(",", context.getTraceState().asMap().entrySet().stream()
                .map(pair -> pair.getKey() + "=" + pair.getValue()).toList());
    }

    static Map<String,Object> payload(SpanData data) {
        Map<String,Object> span = new LinkedHashMap<>();
        span.put("traceId", data.getTraceId()); span.put("spanId", data.getSpanId());
        span.put("traceState", traceState(data.getSpanContext()));
        if (data.getParentSpanContext().isValid()) span.put("parentSpanId", data.getParentSpanId());
        span.put("flags", Byte.toUnsignedInt(data.getSpanContext().getTraceFlags().asByte()) | 0x100
                | (data.getParentSpanContext().isRemote() ? 0x200 : 0));
        span.put("name", TracePayload.clean(data.getName()));
        span.put("kind", data.getKind().ordinal() + 1);
        span.put("startTimeUnixNano", Long.toString(data.getStartEpochNanos()));
        span.put("endTimeUnixNano", Long.toString(data.getEndEpochNanos()));
        span.put("attributes", attributes(data.getAttributes()));
        span.put("droppedAttributesCount", data.getTotalAttributeCount() - data.getAttributes().size());
        span.put("droppedEventsCount", data.getTotalRecordedEvents() - data.getEvents().size());
        span.put("droppedLinksCount", data.getTotalRecordedLinks() - data.getLinks().size());
        span.put("events", data.getEvents().stream().map(event -> Map.of(
                "timeUnixNano", Long.toString(event.getEpochNanos()), "name", TracePayload.clean(event.getName()),
                "attributes", attributes(event.getAttributes()), "droppedAttributesCount", event.getDroppedAttributesCount())).toList());
        span.put("links", data.getLinks().stream().map(link -> Map.of(
                "traceId", link.getSpanContext().getTraceId(), "spanId", link.getSpanContext().getSpanId(),
                "traceState", traceState(link.getSpanContext()), "attributes", attributes(link.getAttributes()),
                "flags", Byte.toUnsignedInt(link.getSpanContext().getTraceFlags().asByte()) | 0x100
                        | (link.getSpanContext().isRemote() ? 0x200 : 0),
                "droppedAttributesCount", link.getTotalAttributeCount() - link.getAttributes().size())).toList());
        span.put("status", Map.of("code", switch (data.getStatus().getStatusCode()) { case UNSET -> 0; case OK -> 1; case ERROR -> 2; },
                "message", TracePayload.clean(data.getStatus().getDescription())));
        var info = data.getInstrumentationScopeInfo();
        Map<String,Object> scope = new LinkedHashMap<>();
        scope.put("name", info.getName());
        if (info.getVersion() != null) scope.put("version", info.getVersion());
        scope.put("attributes", attributes(info.getAttributes()));
        Map<String,Object> scopeSpans = new LinkedHashMap<>();
        scopeSpans.put("scope", scope); scopeSpans.put("spans", List.of(span));
        if (info.getSchemaUrl() != null) scopeSpans.put("schemaUrl", info.getSchemaUrl());
        Map<String,Object> resourceSpans = new LinkedHashMap<>();
        resourceSpans.put("resource", Map.of("attributes", attributes(data.getResource().getAttributes())));
        resourceSpans.put("scopeSpans", List.of(scopeSpans));
        if (data.getResource().getSchemaUrl() != null) resourceSpans.put("schemaUrl", data.getResource().getSchemaUrl());
        return Map.of("resourceSpans", List.of(resourceSpans));
    }

    static Map<String,Object> summary(SpanData span) throws IOException {
        Map<String,Object> attrs = new HashMap<>();
        span.getAttributes().forEach((key, value) -> attrs.put(key.getKey(), value));
        Map<String,Object> meta = new LinkedHashMap<>();
        String prefix = "langfuse.observation.metadata.";
        attrs.forEach((key, value) -> { if (key.startsWith(prefix)) meta.put(key.substring(prefix.length()), value); });
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", span.getSpanId()); row.put("traceId", span.getTraceId());
        row.put("parentObservationId", span.getParentSpanContext().isValid() ? span.getParentSpanId() : null);
        row.put("name", span.getName());
        row.put("type", String.valueOf(attrs.getOrDefault("langfuse.observation.type", "span")).toUpperCase(Locale.ROOT));
        row.put("sessionId", attrs.get("langfuse.session.id")); row.put("metadata", meta);
        row.put("startTime", Instant.ofEpochSecond(0, span.getStartEpochNanos()).toString());
        row.put("endTime", Instant.ofEpochSecond(0, span.getEndEpochNanos()).toString());
        row.put("level", span.getStatus().getStatusCode() == StatusCode.ERROR ? "ERROR" : "DEFAULT");
        row.put("model", attrs.get("langfuse.observation.model.name"));
        Object usage = attrs.get("langfuse.observation.usage_details");
        row.put("usageDetails", usage == null ? Map.of() : JSON.readValue(usage.toString(), Map.class));
        row.put("evidence_source", "local_otel_span_backup");
        return row;
    }
}
