package com.thoughtcoding.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatPayloadTest {
    @Test void truncationPreservesRolesAndToolLinkage() throws Exception {
        var messages = List.of(
                Map.of("role", "user", "content", "x".repeat(40000)),
                Map.of("role", "assistant", "content", "", "tool_calls", List.of(
                        Map.of("id", "call-1", "type", "function", "function",
                                Map.of("name", "read", "arguments", "{}")))),
                Map.of("role", "tool", "tool_call_id", "call-1", "content", "y".repeat(40000)));
        String encoded = TracePayload.limited(messages, 32768);
        var data = new ObjectMapper().readTree(encoded);
        assertTrue(data.isArray());
        assertEquals("user", data.get(0).get("role").asText());
        assertEquals("call-1", data.get(1).get("tool_calls").get(0).get("id").asText());
        assertEquals("call-1", data.get(2).get("tool_call_id").asText());
        assertTrue(encoded.length() <= 32768);
        assertTrue(data.get(0).get("content").asText().contains("TRUNCATED"));
    }

    @Test void syncResponseHasAssistantRoleAndContent() throws Exception {
        var exporter = io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter.create();
        Telemetry.install(io.opentelemetry.sdk.trace.SdkTracerProvider.builder().addSpanProcessor(
                io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter)).build());
        try {
            try (var task = Telemetry.task("chat-shape-test", "hi")) {
                ModelTracing.sync("test", "test-model", "hello", () ->
                    dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from("world")).build());
            }
            var generation = exporter.getFinishedSpanItems().stream()
                    .filter(s -> s.getName().equals("llm.test")).findFirst().orElseThrow();
            var json = new ObjectMapper();
            var input = json.readTree(generation.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.observation.input")));
            var output = json.readTree(generation.getAttributes().get(
                    io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.observation.output")));
            assertEquals("user", input.get(0).get("role").asText());
            assertEquals("assistant", output.get(0).get("role").asText());
            assertEquals("world", output.get(0).get("content").asText());
        } finally { Telemetry.shutdown(); }
    }
}
