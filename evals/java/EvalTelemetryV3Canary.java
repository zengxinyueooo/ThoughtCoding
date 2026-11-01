package com.thoughtcoding.observability;

import io.opentelemetry.api.trace.Span;
import java.nio.file.*;
import java.util.*;

/** Synthetic transport check only: never invokes a model or a tool implementation. */
public final class EvalTelemetryV3Canary {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected new canary directory");
        if (!"offline-transport-v3-synthetic".equals(System.getenv("THOUGHTCODING_ENVIRONMENT")))
            throw new IllegalArgumentException("Canary requires explicitly synthetic environment");
        Path run = Path.of(args[0]).toAbsolutePath().normalize();
        if (Files.exists(run)) throw new FileAlreadyExistsException("Canary output must be new");
        Files.createDirectories(run);
        EvalTelemetryV3.initialize(run.resolve("spans.jsonl"));
        boolean complete = false;
        String traceId;
        try {
            try (var task = Telemetry.task("transport-v3-synthetic-" + UUID.randomUUID(),
                    "Synthetic transport verification; no model invocation, no benchmark task.")) {
                traceId = task.traceId();
                Span.current().setAttribute("langfuse.trace.name", "ThoughtCoding transport v3 synthetic check");
                Span.current().setAttribute("langfuse.observation.metadata.benchmark_excluded", true);
                Span.current().setAttribute("langfuse.observation.metadata.synthetic", true);
                Span.current().setAttribute("langfuse.observation.metadata.actual_model_requests", 0L);
                Span.current().setAttribute("langfuse.observation.metadata.actual_tool_executions", 0L);
                try (var generation = Telemetry.observation("synthetic.llm.transport_v3", "generation",
                        List.of(Map.of("role", "user", "content", "Synthetic display check: show this message.")))) {
                    generation.metadata("benchmark_excluded", true);
                    generation.metadata("synthetic", true);
                    generation.output(List.of(Map.of("role", "assistant", "content", "Synthetic response saved locally; no LLM was called.")));
                }
                try (var tool = Telemetry.observation("synthetic.tool.transport_v3", "tool",
                        Map.of("synthetic_operation", "transport-display-check"))) {
                    tool.metadata("benchmark_excluded", true);
                    tool.metadata("synthetic", true);
                    tool.metadata("success", true);
                    tool.output(Map.of("synthetic", true, "message", "No tool implementation was executed."));
                }
                task.output(List.of(Map.of("role", "assistant", "content",
                        "Synthetic transport verification complete. This trace is excluded from all benchmark and billing statistics.")));
            }
            complete = true;
        } finally { EvalTelemetryV3.finish(complete); }
        System.out.println("Synthetic local canary saved; trace_id=" + traceId + "; actual model requests=0; network requests=0");
    }
}
