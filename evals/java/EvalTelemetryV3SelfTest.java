package com.thoughtcoding.observability;

import io.opentelemetry.api.common.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import java.nio.file.*;
import java.util.*;

/** Executable no-network assertions against the actual OTel SDK and filesystem. */
public class EvalTelemetryV3SelfTest {
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static Map<String,Object> read(Path file) throws Exception {
        return EvalTelemetryV3.JSON.readValue(file.toFile(), Map.class);
    }
    static Map<String,Object> span(Map<String,Object> body) {
        Map resource = (Map) ((List) body.get("resourceSpans")).getFirst();
        Map scope = (Map) ((List) resource.get("scopeSpans")).getFirst();
        return (Map) ((List) scope.get("spans")).getFirst();
    }
    static Map<String,Object> attrs(Map<String,Object> span) {
        Map<String,Object> attrs = new HashMap<>();
        for (Object raw : (List) span.get("attributes")) {
            Map value = (Map) raw; attrs.put((String) value.get("key"), value.get("value"));
        }
        return attrs;
    }
    static final class Capture implements SpanExporter {
        final List<SpanData> spans = new ArrayList<>();
        public CompletableResultCode export(Collection<SpanData> values) { spans.addAll(values); return CompletableResultCode.ofSuccess(); }
        public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectory(root);
        Path rich = root.resolve("rich"); Files.createDirectory(rich);
        var exporter = new EvalTelemetryV3.DurableExporter(rich.resolve("spans.jsonl"));
        var capture = new Capture();
        var sdk = SdkTracerProvider.builder()
                .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "v3-self-test"), "https://example.invalid/resource-schema"))
                .setSpanLimits(SpanLimits.builder().setMaxNumberOfEvents(1).setMaxNumberOfLinks(1).build())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .addSpanProcessor(SimpleSpanProcessor.create(capture)).build();
        var tracer = sdk.tracerBuilder("v3-self-test").setInstrumentationVersion("3")
                .setSchemaUrl("https://example.invalid/scope-schema").build();
        Span parent = tracer.spanBuilder("parent").setNoParent().startSpan();
        SpanContext remote = SpanContext.createFromRemoteParent("11223344556677889900112233445566", "1122334455667788",
                TraceFlags.getSampled(), TraceState.builder().put("vendor", "state").build());
        Span child = tracer.spanBuilder("rich-child").setParent(Context.root().with(parent)).setSpanKind(SpanKind.CLIENT)
                .addLink(remote, Attributes.of(AttributeKey.stringKey("link-attribute"), "preserved"))
                .addLink(remote).startSpan();
        Telemetry.registerSecret("synthetic-secret-never-upload");
        child.setAttribute("langfuse.observation.input", "[{\"role\":\"user\",\"content\":\"synthetic-secret-never-upload\"}]");
        child.setAttribute("langfuse.observation.output", "[{\"role\":\"assistant\",\"content\":\"ok\"}]");
        child.setAttribute("langfuse.observation.usage_details", "{\"input\":2,\"output\":1,\"total\":3}");
        child.setAttribute("langfuse.observation.model.name", "synthetic-no-model");
        child.setAttribute("langfuse.observation.type", "generation");
        child.setAttribute("a-long", 9007199254740993L);
        child.setAttribute("a-flag", true);
        child.setAttribute("a-double", 1.25);
        child.setAttribute(AttributeKey.stringArrayKey("a-strings"), List.of("a", "b"));
        child.setAttribute(AttributeKey.longArrayKey("a-longs"), List.of(1L, 2L));
        child.setAttribute(AttributeKey.booleanArrayKey("a-bools"), List.of(true, false));
        child.setAttribute(AttributeKey.doubleArrayKey("a-doubles"), List.of(1.0, 2.0));
        child.addEvent("retained-event", Attributes.of(AttributeKey.longKey("n"), 4L));
        child.addEvent("discarded-event");
        child.setStatus(StatusCode.ERROR, "synthetic failure");
        child.end(); parent.end(); sdk.shutdown().join(5, java.util.concurrent.TimeUnit.SECONDS);
        exporter.complete(true);
        Map receipt = read(rich.resolve("otlp-spool-completion.json"));
        require(Boolean.TRUE.equals(receipt.get("complete")), "complete receipt missing");
        require(((Number) receipt.get("exported_span_count")).intValue() == 2, "span count");
        require(((Number) receipt.get("summary_span_count")).intValue() == 2, "summary count");
        SpanData source = capture.spans.stream().filter(s -> s.getName().equals("rich-child")).findFirst().orElseThrow();
        Path payloadFile = rich.resolve("otlp-spool/" + source.getTraceId() + "-" + source.getSpanId() + ".json");
        Map<String,Object> body = read(payloadFile), value = span(body), attributes = attrs(value);
        require(source.getParentSpanId().equals(value.get("parentSpanId")), "parent id");
        require(source.getTraceId().equals(value.get("traceId")), "trace id");
        require(Long.toString(source.getStartEpochNanos()).equals(value.get("startTimeUnixNano")), "timestamp precision");
        require(Long.toString(source.getEndEpochNanos()).equals(value.get("endTimeUnixNano")), "end precision");
        require(((Map) attributes.get("a-long")).get("intValue").equals("9007199254740993"), "int64 precision");
        require(((Map) attributes.get("a-flag")).get("boolValue").equals(true), "bool type");
        require(((Map) attributes.get("a-double")).get("doubleValue").equals(1.25), "double type");
        for (String name : List.of("a-strings", "a-longs", "a-bools", "a-doubles"))
            require(((Map) attributes.get(name)).containsKey("arrayValue"), "array type");
        require(((Map) attributes.get("langfuse.observation.input")).get("stringValue").toString().contains("[REDACTED]"), "redaction");
        require(!Files.readString(payloadFile).contains("synthetic-secret-never-upload"), "secret leaked");
        require(((Map) attributes.get("langfuse.observation.output")).containsKey("stringValue"), "output missing");
        require(((Map) attributes.get("langfuse.observation.usage_details")).get("stringValue").toString().contains("\"total\":3"), "usage missing");
        require(((Number) value.get("kind")).intValue() == 3, "span kind");
        require(((Number) ((Map) value.get("status")).get("code")).intValue() == 2, "status");
        require(((Number) value.get("droppedEventsCount")).intValue() == 1, "dropped events");
        require(((Number) value.get("droppedLinksCount")).intValue() == 1, "dropped links");
        Map event = (Map) ((List) value.get("events")).getFirst();
        require(event.get("name").equals("retained-event") && ((List) event.get("attributes")).size() == 1, "event data");
        Map link = (Map) ((List) value.get("links")).getFirst();
        require(link.get("traceId").equals(remote.getTraceId()) && link.get("traceState").equals("vendor=state"), "link context");
        require(((Number) link.get("flags")).intValue() == 769, "link flags");
        Map resource = (Map) ((List) body.get("resourceSpans")).getFirst();
        require(resource.get("schemaUrl").equals("https://example.invalid/resource-schema"), "resource schema");
        Map scope = (Map) ((List) resource.get("scopeSpans")).getFirst();
        require(scope.get("schemaUrl").equals("https://example.invalid/scope-schema"), "scope schema");
        require(((Map) scope.get("scope")).get("version").equals("3"), "scope version");
        for (Object item : (List) receipt.get("span_files")) {
            Map record = (Map) item;
            require(record.get("sha256").equals(EvalTelemetryV3.hash(Files.readAllBytes(rich.resolve((String) record.get("file"))))), "receipt hash");
        }
        try { new EvalTelemetryV3.DurableExporter(rich.resolve("spans.jsonl")); throw new AssertionError("reused run accepted"); }
        catch (FileAlreadyExistsException expected) { }

        Path broken = root.resolve("broken"); Files.createDirectory(broken);
        var brokenExporter = new EvalTelemetryV3.DurableExporter(broken.resolve("spans.jsonl"));
        Path conflict = broken.resolve("otlp-spool/" + source.getTraceId() + "-" + source.getSpanId() + ".json");
        Files.writeString(conflict, "existing original must survive");
        require(!brokenExporter.export(List.of(source)).isSuccess(), "persistence conflict accepted");
        require(Files.readString(conflict).equals("existing original must survive"), "original overwritten");
        require(!brokenExporter.export(List.of(capture.spans.getLast())).isSuccess(), "sticky failure lost");
        brokenExporter.shutdown();
        try { brokenExporter.complete(true); throw new AssertionError("failed evidence accepted"); }
        catch (java.io.IOException expected) { }
        require(Boolean.FALSE.equals(read(broken.resolve("otlp-spool-completion.json")).get("complete")), "failure receipt");

        Path telemetry = root.resolve("telemetry"); Files.createDirectory(telemetry);
        EvalTelemetryV3.initialize(telemetry.resolve("spans.jsonl"));
        try (var task = Telemetry.task("v3-self-test", "synthetic")) {
            try (var generation = Telemetry.observation("llm.test", "generation", List.of(Map.of("role", "user", "content", "synthetic")))) {
                generation.output(List.of(Map.of("role", "assistant", "content", "synthetic")));
            }
        }
        EvalTelemetryV3.finish(false);
        Map abnormal = read(telemetry.resolve("otlp-spool-completion.json"));
        require(Boolean.TRUE.equals(abnormal.get("complete")) && Boolean.FALSE.equals(abnormal.get("agent_completed")), "abnormal completion not distinguished");
        System.out.println("v3 durable-span self-tests passed: full IO/types/IDs/timing/events/links/resource/scope/usage/redaction/hash; failure closed; restart refused; zero network/model calls");
    }
}
