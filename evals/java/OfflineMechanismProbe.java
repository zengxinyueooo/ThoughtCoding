package com.thoughtcoding.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.core.ToolExecutionPipeline;
import com.thoughtcoding.hook.HookRegistry;
import com.thoughtcoding.memory.MemoryService;
import com.thoughtcoding.memory.MemoryStore;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.security.Sandbox;
import com.thoughtcoding.tool.ToolRegistry;
import com.thoughtcoding.tool.tools.WriteTool;
import java.nio.file.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Pattern;

/** Simulation adapter: real components, deterministic consumer, no LLM/remote configuration. */
public final class OfflineMechanismProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PROJECT = Pattern.compile("PROJECT ([a-z0-9_-]+)");
    private static final Pattern FACT = Pattern.compile("DECISION ([a-z_]+)=([A-Za-z0-9_]+)");

    // This consumer receives only request text and target project, never gold/expected values.
    static Map<String,String> consume(String text, String target) {
        Map<String,String> facts = new LinkedHashMap<>();
        String project = "";
        for (String line : text.split("\\R")) {
            var p = PROJECT.matcher(line.strip());
            if (p.matches()) project = p.group(1);
            var f = FACT.matcher(line.strip());
            if (f.matches() && target.equals(project)) facts.put(f.group(1), f.group(2));
        }
        return facts;
    }

    static String code(Map<String,String> facts) throws Exception {
        String method = facts.getOrDefault("method_name", "unknownApi");
        if (!method.matches("[A-Za-z_][A-Za-z0-9_]*")) method = "unknownApi";
        String error = JSON.writeValueAsString(facts.getOrDefault("error_code", "UNKNOWN"));
        String action = switch (facts.getOrDefault("normalization", "UNKNOWN")) {
            case "TRIM" -> "value.trim()";
            case "UPPER" -> "value.trim().toUpperCase(java.util.Locale.ROOT)";
            default -> "value";
        };
        return "public class Solution { public static String " + method + "(String value) { "
                + "if (value == null || value.isBlank()) return " + error + "; return " + action + "; } }\n";
    }

    public static void main(String[] args) throws Exception {
        JsonNode fixture = JSON.readTree(Path.of(args[0]).toFile());
        String variant = args[1];
        if (!Set.of("window10", "four_layer", "memory_off", "memory_on").contains(variant)) {
            throw new IllegalArgumentException("Unknown simulation variant");
        }
        Path root = Path.of(".").toAbsolutePath().normalize();
        Sandbox.init(root.toString());
        AppConfig config = new AppConfig();
        // No real model can be constructed: neither API keys nor the user's config are read.
        config.setModels(new LinkedHashMap<>());
        config.getAi().setMaxContextTokens(8192);
        config.getAi().setMaxMessages(50);
        MemoryStore store = null;
        boolean seedReloaded = false;
        if (variant.equals("memory_on")) {
            Path dir = root.resolve("simulation-memory");
            store = MemoryStore.load(dir, 200);
            for (JsonNode memory : fixture.path("memory_learning_events")) {
                if (store.write(memory.path("name").asText(), "project",
                        memory.path("description").asText(), memory.path("body").asText()) == null) {
                    throw new IllegalStateException("Fixture memory write failed");
                }
            }
            store = MemoryStore.load(dir, 200); // Test actual persistence across a fresh instance.
            seedReloaded = true;
        }
        ContextManager context = new ContextManager(config, null, store, null);
        List<ChatMessage> history = new ArrayList<>();
        for (JsonNode message : fixture.path("history")) {
            history.add(new ChatMessage(message.path("role").asText(), message.path("content").asText()));
        }
        String before = JSON.writeValueAsString(history);
        String recall = store == null ? "" : new MemoryService(config, store, config.getMemory()).recall(history);
        String reminder = ContextManager.buildRecallReminder(recall);
        ChatMessage systemMessage = context.buildProjectContextMessage();
        String system = systemMessage == null ? "" : systemMessage.getContent();
        // Keep paired system prompts identical despite isolated per-variant working paths.
        system = system.replace(root.toString(), "<offline-workspace>");
        int reserved = ContextManager.estimateTokens(system)
                + ContextManager.estimateTokens(reminder) + 256;
        Method estimate = ContextManager.class.getDeclaredMethod("estimateTotalTokens", List.class);
        estimate.setAccessible(true);
        List<ChatMessage> managed;
        if (variant.equals("window10")) {
            List<Integer> starts = new ArrayList<>();
            for (int i=0; i<history.size(); i++) if ("user".equals(history.get(i).getRole())) starts.add(i);
            int begin = starts.size() > 10 ? starts.get(starts.size()-10) : 0;
            managed = new ArrayList<>();
            for (int i=begin; i<history.size(); i++) managed.add(new ChatMessage(history.get(i)));
            while (managed.size() > 1 && (int)estimate.invoke(context, managed) > 8192-reserved) {
                managed.remove(0);
                if (!managed.isEmpty() && "assistant".equals(managed.getFirst().getRole())) managed.remove(0);
            }
        } else managed = context.getContextForAI(history, reserved);
        if (!before.equals(JSON.writeValueAsString(history))) throw new IllegalStateException("History mutated");
        int inputEstimate = (int)estimate.invoke(context, managed)
                + ContextManager.estimateTokens(system) + ContextManager.estimateTokens(reminder);
        if (inputEstimate + 256 > 8192) throw new IllegalStateException("Estimated request budget exceeded");
        StringBuilder request = new StringBuilder(system).append('\n');
        for (ChatMessage message : managed) request.append(message.getContent()).append('\n');
        if (reminder != null) request.append(reminder);
        Map<String,String> facts = consume(request.toString(), fixture.path("project").asText());
        String generated = code(facts);
        ToolRegistry registry = new ToolRegistry(config);
        registry.register(new WriteTool(config));
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(null, new HookRegistry(), registry);
        ToolCall call = new ToolCall("write", Map.of("path", "Solution.java", "content", generated),
                null, false, 0, false, "simulated-write-1");
        var outcome = pipeline.execute(call, null, history);
        Map<String,Object> output = new LinkedHashMap<>();
        output.put("variant", variant);
        output.put("consumer_facts", facts);
        output.put("request_text", request.toString());
        output.put("recall_text", recall);
        output.put("source_history_unchanged", true);
        output.put("memory_store_reloaded", seedReloaded);
        output.put("estimated_input_tokens", inputEstimate);
        output.put("estimated_output_tokens", ContextManager.estimateTokens(generated));
        output.put("history_before_messages", history.size());
        output.put("history_after_messages", managed.size());
        output.put("l4_summary_present", request.indexOf("[Conversation compressed.]") >= 0);
        output.put("summary_model_available", false);
        output.put("real_tool_calls", 1);
        output.put("real_tool_errors", outcome.result().isSuccess() ? 0 : 1);
        output.put("tool_error", outcome.result().getError());
        output.put("simulated_model_calls", 1);
        output.put("real_model_calls", 0);
        output.put("token_usage_actual", null);
        output.put("external_model_cost_usd", 0);
        output.put("subagent_count", 0);
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(), output);
    }
}
