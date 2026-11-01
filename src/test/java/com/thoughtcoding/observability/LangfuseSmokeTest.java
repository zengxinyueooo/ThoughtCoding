package com.thoughtcoding.observability;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: exports synthetic data only; no model API call or workspace content. */
@EnabledIfEnvironmentVariable(named = "THOUGHTCODING_LANGFUSE_SMOKE", matches = "true")
class LangfuseSmokeTest {
    @Test void exportsAndReadsBackSyntheticTrace() throws Exception {
        Telemetry.initialize();
        assertTrue(Telemetry.enabled(), "Tracing configuration was not accepted");
        String traceId;
        try (var task = Telemetry.task("thoughtcoding-tracing-smoke", "Synthetic tracing connectivity check");
             var main = Telemetry.observation("agent.main", "agent", "Synthetic check");
             var scope = main.activate()) {
            traceId = task.traceId();
            ModelTracing.sync("smoke", "synthetic-no-model-call", "ping", () ->
                    ChatResponse.builder().aiMessage(AiMessage.from("pong"))
                            .tokenUsage(new TokenUsage(1, 1)).build());
            try (var tool = Telemetry.observation("tool.smoke", "tool", Map.of("synthetic", true))) {
                tool.output("ok");
                tool.metadata("success", true);
            }
            main.output("Synthetic check complete");
            task.output("Synthetic check complete");
        } finally {
            Telemetry.shutdown();
        }
        String base = System.getenv("LANGFUSE_BASE_URL").replaceAll("/+$", "");
        String auth = Base64.getEncoder().encodeToString(
                (System.getenv("LANGFUSE_PUBLIC_KEY") + ":" + System.getenv("LANGFUSE_SECRET_KEY"))
                        .getBytes(StandardCharsets.UTF_8));
        String from = java.net.URLEncoder.encode(java.time.Instant.now().minusSeconds(300).toString(),
                StandardCharsets.UTF_8);
        String to = java.net.URLEncoder.encode(java.time.Instant.now().plusSeconds(60).toString(),
                StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create(base
                        + "/api/public/v2/observations?traceId=" + traceId
                        + "&fields=core,basic,usage&fromStartTime=" + from + "&toStartTime=" + to))
                .header("Authorization", "Basic " + auth).timeout(Duration.ofSeconds(10)).GET().build();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            int status = 0;
            for (int i = 0; i < 10; i++) {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                status = response.statusCode();
                if (status == 200) {
                    var rows = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(response.body()).path("data");
                    if (rows.size() >= 4) {
                        for (var row : rows) assertEquals(traceId, row.path("traceId").asText());
                        System.out.println("Langfuse synthetic trace verified: " + traceId
                                + " (" + rows.size() + " observations)");
                        return;
                    }
                }
                if (status != 200 && status != 404 && status != 429) break;
                Thread.sleep(2000);
            }
            fail("Langfuse trace read-back returned HTTP " + status + " (credentials/body omitted)");
        }
    }
}
