package com.thoughtcoding.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCallRef;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.*;

/** Evaluation-only adapter: invokes the current production compression pipeline. */
public final class ContextProbe {
    private static final ObjectMapper JSON = new ObjectMapper();

    static List<Map<String,Object>> wire(List<ChatMessage> messages) {
        List<Map<String,Object>> out = new ArrayList<>();
        for (ChatMessage m : messages) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("role", m.getRole());
            row.put("content", m.getContent() == null ? "" : m.getContent());
            if (m.isToolMessage()) row.put("tool_call_id", m.getToolCallId());
            if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                List<Map<String,Object>> calls = new ArrayList<>();
                for (ToolCallRef c : m.getToolCalls()) {
                    calls.add(Map.of("id", c.getId(), "type", "function", "function",
                            Map.of("name", c.getName(), "arguments", c.getArguments())));
                }
                row.put("tool_calls", calls);
            }
            out.add(row);
        }
        return out;
    }

    static boolean validPairs(List<ChatMessage> messages) {
        Set<String> pending = new HashSet<>();
        for (ChatMessage m : messages) {
            if (m.isToolMessage()) {
                if (!pending.remove(m.getToolCallId())) return false;
            } else {
                if (!pending.isEmpty()) return false;
                if (m.getToolCalls() != null) for (ToolCallRef c : m.getToolCalls()) {
                    if (!pending.add(c.getId())) return false;
                }
            }
        }
        return pending.isEmpty();
    }

    static Map<String,Object> describe(List<ChatMessage> messages, JsonNode gold) throws Exception {
        List<Map<String,Object>> projected = wire(messages);
        String content = messages.stream().map(m -> m.getContent() == null ? "" : m.getContent())
                .reduce("", (a,b) -> a + "\n" + b);
        Map<String,Boolean> direct = new LinkedHashMap<>(), recoverable = new LinkedHashMap<>();
        StringBuilder accessible = new StringBuilder(content);
        Matcher paths = Pattern.compile("<persisted-output path=\"([^\"]+)\"").matcher(content);
        List<String> files = new ArrayList<>();
        while (paths.find()) {
            Path p = Path.of(paths.group(1)).toAbsolutePath().normalize();
            Path root = Path.of(".").toAbsolutePath().normalize();
            if (!p.startsWith(root) || !Files.isRegularFile(p)) throw new IllegalStateException("Invalid persisted path");
            files.add(root.relativize(p).toString().replace('\\','/'));
            accessible.append('\n').append(Files.readString(p));
        }
        for (Iterator<Map.Entry<String,JsonNode>> it = gold.fields(); it.hasNext();) {
            Map.Entry<String,JsonNode> e = it.next();
            direct.put(e.getKey(), content.contains(e.getValue().asText()));
            recoverable.put(e.getKey(), accessible.toString().contains(e.getValue().asText()));
        }
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("messages", projected);
        out.put("message_count", messages.size());
        out.put("serialized_utf8_bytes", JSON.writeValueAsBytes(projected).length);
        out.put("estimated_content_tokens", messages.stream().mapToInt(m -> ContextManager.estimateTokens(m.getContent())).sum());
        out.put("direct_literal_retention", direct);
        out.put("retention_with_advertised_persisted_files", recoverable);
        out.put("persisted_files", files);
        out.put("pairs_valid", validPairs(messages));
        out.put("l4_summary_present", content.contains("[Conversation compressed.]"));
        out.put("l2_placeholder_present", content.contains("[Previous: used "));
        return out;
    }

    public static void main(String[] args) throws Exception {
        JsonNode fixture = JSON.readTree(Path.of(args[0]).toFile());
        AppConfig config = new ObjectMapper(new YAMLFactory()).readValue(Path.of(args[1]).toFile(), AppConfig.class);
        List<ChatMessage> full = new ArrayList<>();
        for (JsonNode n : fixture.get("history")) {
            ChatMessage m = new ChatMessage(n.get("role").asText(), n.path("content").asText(), fixture.get("id").asText());
            if (n.has("tool_call_id")) { m.setToolCallId(n.get("tool_call_id").asText()); m.setToolName("read"); }
            if (n.has("tool_calls")) {
                List<ToolCallRef> calls = new ArrayList<>();
                for (JsonNode c : n.get("tool_calls")) calls.add(new ToolCallRef(c.get("id").asText(),
                        c.get("function").get("name").asText(), c.get("function").get("arguments").asText()));
                m.setToolCalls(calls);
            }
            full.add(m);
        }
        String before = JSON.writeValueAsString(full);
        ContextManager manager = new ContextManager(config, null, null, null);
        List<ChatMessage> managed = manager.getContextForAI(full, 256);
        if (!before.equals(JSON.writeValueAsString(full))) throw new IllegalStateException("Source history mutated");
        List<ChatMessage> window = new ArrayList<>();
        for (int i = Math.max(0, full.size() - 20); i < full.size(); i++) window.add(new ChatMessage(full.get(i)));
        Method sanitize = ContextManager.class.getDeclaredMethod("sanitizeToolPairs", List.class);
        sanitize.setAccessible(true);
        @SuppressWarnings("unchecked") List<ChatMessage> cleaned = (List<ChatMessage>) sanitize.invoke(manager, window);
        // Optional equal-budget baseline. Drop oldest whole messages and clean pairs;
        // never give the baseline a larger history allowance than production.
        if (fixture.has("fair_history_budget")) {
            int budget = fixture.get("fair_history_budget").asInt();
            Method estimate = ContextManager.class.getDeclaredMethod("estimateTotalTokens", List.class);
            estimate.setAccessible(true);
            while (!cleaned.isEmpty() && (int) estimate.invoke(manager, cleaned) > budget) {
                cleaned.remove(0);
                @SuppressWarnings("unchecked") List<ChatMessage> next = (List<ChatMessage>) sanitize.invoke(manager, cleaned);
                cleaned = next;
            }
            if ((int) estimate.invoke(manager, managed) > budget) throw new IllegalStateException("Production exceeded fair budget");
        }
        JsonNode gold = fixture.get("gold");
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("id", fixture.get("id").asText());
        out.put("source_history_unchanged", true);
        out.put("full", describe(full, gold));
        out.put("window20", describe(cleaned, gold));
        out.put("four_layer", describe(managed, gold));
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(), out);
    }
}
