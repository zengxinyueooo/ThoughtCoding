package com.thoughtcoding.service;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCallRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextBudgetTest {

    @Test
    void reservedRequestOverheadReducesTheHistoryBudget() {
        AppConfig config = new AppConfig();
        config.getAi().setMaxContextTokens(1_000);
        config.getAi().setMaxMessages(100);
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            history.add(new ChatMessage("user", "message-" + i + " " + "x".repeat(400)));
        }
        List<String> original = history.stream().map(ChatMessage::getContent).toList();
        ContextManager manager = new ContextManager(config, null, null, null);

        List<ChatMessage> managed = manager.getContextForAI(history, 500);

        int tokens = managed.stream().mapToInt(m -> ContextManager.estimateTokens(m.getContent())).sum();
        assertTrue(tokens <= 500, "history should fit the budget left after fixed request overhead");
        assertEquals(original, history.stream().map(ChatMessage::getContent).toList(),
                "context management must not mutate persisted history");
    }

    @Test
    void aSingleHugeLatestMessageIsTailTruncatedAsTheLastDefense() {
        AppConfig config = new AppConfig();
        config.getAi().setMaxContextTokens(600);
        ContextManager manager = new ContextManager(config, null, null, null);
        ChatMessage latest = new ChatMessage("user", "BEGIN" + "x".repeat(8_000) + "LATEST");

        List<ChatMessage> managed = manager.getContextForAI(List.of(latest), 300);

        assertEquals(1, managed.size());
        assertTrue(managed.get(0).getContent().contains("LATEST"));
        assertTrue(ContextManager.estimateTokens(managed.get(0).getContent()) <= 300);
        assertTrue(latest.getContent().startsWith("BEGIN"), "source message must remain unchanged");
    }

    @Test
    void hardLimitKeepsTheLatestToolCallPairedWithItsResult() {
        AppConfig config = new AppConfig();
        config.getAi().setMaxContextTokens(800);
        config.getAi().setMaxMessages(100);
        ContextManager manager = new ContextManager(config, null, null, null);
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            history.add(new ChatMessage("user", "old-" + i + " " + "x".repeat(300)));
        }
        history.add(ChatMessage.assistantWithToolCalls("", List.of(
                new ToolCallRef("call-1", "bash", "{\"command\":\"mvn test\"}"))));
        history.add(ChatMessage.toolResult("call-1", "bash", "result " + "y".repeat(4_000)));

        List<ChatMessage> managed = manager.getContextForAI(history, 300);

        assertTrue(managed.stream().anyMatch(m -> m.hasToolCalls()
                && "call-1".equals(m.getToolCalls().get(0).getId())));
        assertTrue(managed.stream().anyMatch(m -> m.isToolMessage()
                && "call-1".equals(m.getToolCallId())));
        assertTrue(managed.stream().mapToInt(m -> ContextManager.estimateTokens(m.getContent())).sum() <= 500);
    }

    @Test
    void configuredToolResultBudgetIsNotOverwrittenByContextBudget() {
        AppConfig config = new AppConfig();
        config.getAi().setMaxContextTokens(10_000);
        config.getAi().setMaxToolResultBytes(100_000);
        config.getAi().setPerResultPersistBytes(100);
        ContextManager manager = new ContextManager(config, null, null, null);
        ChatMessage assistant = ChatMessage.assistantWithToolCalls("", List.of(
                new ToolCallRef("call-1", "read", "{\"file_path\":\"large.txt\"}")));
        ChatMessage tool = ChatMessage.toolResult("call-1", "read", "z".repeat(6_000));

        List<ChatMessage> managed = manager.getContextForAI(List.of(assistant, tool));

        ChatMessage managedTool = managed.stream().filter(ChatMessage::isToolMessage).findFirst().orElseThrow();
        assertEquals(6_000, managedTool.getContent().length());
        assertTrue(!managedTool.getContent().startsWith("<persisted-output"));
    }
}
