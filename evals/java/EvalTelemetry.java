package com.thoughtcoding.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Evaluation-only dual export. Records original span IDs and counters, no raw prompts or secrets. */
public final class EvalTelemetry {
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("Expected isolated evidence file path");
        Path file=Path.of(args[0]);
        if(Files.exists(file)) throw new IllegalArgumentException("Self-test output must be new");
        Telemetry.install(SdkTracerProvider.builder().addSpanProcessor(
            SimpleSpanProcessor.create(new EvidenceExporter(file))).build());
        try(var task=Telemetry.task("eval-backup-self-test","synthetic")) {
            try(var generation=Telemetry.observation("llm.test","generation",Map.of())) {
                generation.attribute("langfuse.observation.usage_details","{\"input\":2,\"output\":1,\"total\":3}");
            }
            try(var tool=Telemetry.observation("tool.test","tool",Map.of())) {
                tool.metadata("success",true);
            }
        }
        Telemetry.shutdown();
        var json=new ObjectMapper();
        var rows=Files.readAllLines(file).stream().map(line->{
            try {return json.readValue(line,Map.class);} catch(Exception e){throw new RuntimeException(e);}
        }).toList();
        if(rows.size()!=3) throw new AssertionError("Missing local span evidence");
        Map root=rows.stream().filter(r->"task".equals(r.get("name"))).findFirst().orElseThrow();
        Map meta=(Map)root.get("metadata");
        if(!Long.valueOf(1).equals(((Number)meta.get("llm_call_count")).longValue())
            || !Long.valueOf(1).equals(((Number)meta.get("tool_call_count")).longValue()))
            throw new AssertionError("Local root counters differ");
        for(Map row:rows) if(!root.get("traceId").equals(row.get("traceId")))
            throw new AssertionError("Trace identity was lost");
        Map generation=rows.stream().filter(r->"GENERATION".equals(r.get("type"))).findFirst().orElseThrow();
        if(((Number)((Map)generation.get("usageDetails")).get("total")).intValue()!=3
            || !root.get("id").equals(generation.get("parentObservationId")))
            throw new AssertionError("Usage or original parent span ID was lost");
        System.out.println("Original local span identity, usage and counters verified; no network calls");
    }
    public static void initialize(Path evidence) {
        if (!Boolean.parseBoolean(System.getenv("THOUGHTCODING_TRACING_ENABLED"))) return;
        String pub=Objects.requireNonNull(System.getenv("LANGFUSE_PUBLIC_KEY"));
        String secret=Objects.requireNonNull(System.getenv("LANGFUSE_SECRET_KEY"));
        String base=Objects.requireNonNull(System.getenv("LANGFUSE_BASE_URL")).replaceAll("/+$", "");
        var uri=java.net.URI.create(base);
        if (!Set.of("https","http").contains(uri.getScheme()) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("Invalid tracing base URL");
        Telemetry.registerSecret(pub); Telemetry.registerSecret(secret);
        String auth=Base64.getEncoder().encodeToString((pub+":"+secret).getBytes(StandardCharsets.UTF_8));
        var remote=OtlpHttpSpanExporter.builder().setEndpoint(base+"/api/public/otel/v1/traces")
            .addHeader("Authorization","Basic "+auth).addHeader("x-langfuse-ingestion-version","4")
            .setTimeout(Duration.ofSeconds(5)).build();
        var sdk=SdkTracerProvider.builder().setResource(Resource.create(
                Attributes.builder().put("service.name","thoughtcoding").build()))
            .addSpanProcessor(SimpleSpanProcessor.create(new EvidenceExporter(evidence)))
            .addSpanProcessor(BatchSpanProcessor.builder(remote).setMaxQueueSize(2048)
                .setMaxExportBatchSize(256).setScheduleDelay(Duration.ofSeconds(2)).build()).build();
        Telemetry.install(sdk);
        Runtime.getRuntime().addShutdownHook(new Thread(Telemetry::shutdown,"eval-tracing-shutdown"));
    }

    static final class EvidenceExporter implements SpanExporter {
        private final Path file;
        private final ObjectMapper json=new ObjectMapper();
        EvidenceExporter(Path file) { this.file=file; }
        public synchronized CompletableResultCode export(Collection<SpanData> spans) {
            try {
                Files.createDirectories(file.getParent());
                for (SpanData span : spans) {
                    Map<String,Object> attrs=new HashMap<>();
                    span.getAttributes().forEach((key,value)->attrs.put(key.getKey(),value));
                    Map<String,Object> meta=new LinkedHashMap<>();
                    String prefix="langfuse.observation.metadata.";
                    attrs.forEach((key,value)-> { if(key.startsWith(prefix)) meta.put(key.substring(prefix.length()),value); });
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("id",span.getSpanId()); row.put("traceId",span.getTraceId());
                    row.put("parentObservationId",span.getParentSpanContext().isValid()?span.getParentSpanId():null);
                    row.put("name",span.getName());
                    row.put("type",String.valueOf(attrs.getOrDefault("langfuse.observation.type","span")).toUpperCase(Locale.ROOT));
                    row.put("sessionId",attrs.get("langfuse.session.id")); row.put("metadata",meta);
                    row.put("startTime",Instant.ofEpochSecond(0,span.getStartEpochNanos()).toString());
                    row.put("endTime",Instant.ofEpochSecond(0,span.getEndEpochNanos()).toString());
                    row.put("level",span.getStatus().getStatusCode()==StatusCode.ERROR?"ERROR":"DEFAULT");
                    row.put("model",attrs.get("langfuse.observation.model.name"));
                    Object usage=attrs.get("langfuse.observation.usage_details");
                    row.put("usageDetails",usage==null?Map.of():json.readValue(usage.toString(),Map.class));
                    row.put("evidence_source","local_otel_span_backup");
                    Files.writeString(file,json.writeValueAsString(row)+"\n",StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                }
                return CompletableResultCode.ofSuccess();
            } catch(Exception ignored) {
                System.err.println("Local evaluation span evidence could not be persisted");
                return CompletableResultCode.ofFailure();
            }
        }
        public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }
}
