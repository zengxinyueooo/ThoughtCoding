package com.thoughtcoding.service;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCallRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 microCompact：最后一条 assistant 之后的工具结果视为未读，不占「最近 3 条全文」名额。
 * 断言都走 {@link ContextManager#getContextForAI}，并确认入参历史原文不变。
 */
class MicroCompactTest {

    @Test
    void unreadBatchStaysFullWhileOlderReadResultsUseTheRecentQuota() {
        List<ChatMessage> history = new ArrayList<>();
        history.addAll(pairedTools("read", 10));
        history.addAll(pairedTools("bash", 5));
        List<String> original = contents(history);

        List<ChatMessage> managed = manager().getContextForAI(history);

        assertHistoryUntouched(original, history);
        assertIdentityFields(history, managed);
        for (int i = 0; i < 7; i++) {
            assertEquals("[Previous: used read]", contentOf(managed, "read-" + i),
                    "older read results still compact");
        }
        for (int i = 7; i < 10; i++) {
            assertEquals(longResult("read-" + i), contentOf(managed, "read-" + i),
                    "the newest 3 read results keep their full text");
        }
        for (int i = 0; i < 5; i++) {
            assertEquals(longResult("bash-" + i), contentOf(managed, "bash-" + i),
                    "unread results after the last assistant stay full and do not take the quota");
        }
    }

    @Test
    void currentBatchWithNoReadResultsStaysFull() {
        List<ChatMessage> history = pairedTools("bash", 4);
        List<String> original = contents(history);

        List<ChatMessage> managed = manager().getContextForAI(history);

        assertHistoryUntouched(original, history);
        assertIdentityFields(history, managed);
        for (int i = 0; i < 4; i++) {
            assertEquals(longResult("bash-" + i), contentOf(managed, "bash-" + i),
                    "a batch the model has not read yet must not be replaced");
        }
    }

    @Test
    void resultsBecomeReadOnceALaterAssistantExists() {
        List<ChatMessage> history = pairedTools("read", 4);
        history.add(new ChatMessage("assistant", "已读完这批结果"));
        List<String> original = contents(history);

        List<ChatMessage> managed = manager().getContextForAI(history);

        assertHistoryUntouched(original, history);
        assertIdentityFields(history, managed);
        assertEquals("[Previous: used read]", contentOf(managed, "read-0"));
        for (int i = 1; i < 4; i++) {
            assertEquals(longResult("read-" + i), contentOf(managed, "read-" + i));
        }
    }

    @Test
    void userOrSystemAfterTheBatchDoesNotMakeThoseResultsRead() {
        for (ChatMessage trailing : List.of(
                new ChatMessage("user", "verification succeeded"),
                new ChatMessage("system", "max tool rounds reached"))) {
            List<ChatMessage> history = pairedTools("bash", 4);
            history.add(trailing);
            List<String> original = contents(history);

            List<ChatMessage> managed = manager().getContextForAI(history);

            assertHistoryUntouched(original, history);
            assertIdentityFields(history, managed);
            for (int i = 0; i < 4; i++) {
                assertEquals(longResult("bash-" + i), contentOf(managed, "bash-" + i),
                        "trailing " + trailing.getRole() + " must not move the unread boundary");
            }
            assertEquals(trailing.getRole(), managed.get(managed.size() - 1).getRole());
            assertEquals(trailing.getContent(), managed.get(managed.size() - 1).getContent());
        }
    }

    @Test
    void shortAndPersistedReadResultsAreNotReplaced() {
        String shortBody = "s".repeat(100);
        String persistedBody = "<persisted-output path=\"/tmp/out.txt\" bytes=\"120\" tool=\"grep\">\n"
                + "p".repeat(120)
                + "\n...(truncated, full content persisted to disk)\n</persisted-output>";
        assertTrue(persistedBody.length() > 100);
        assertTrue(persistedBody.startsWith("<persisted-output "));

        List<ToolCallRef> calls = new ArrayList<>();
        calls.add(new ToolCallRef("old", "read", "{}"));
        calls.add(new ToolCallRef("short", "glob", "{}"));
        calls.add(new ToolCallRef("persisted", "grep", "{}"));
        calls.add(new ToolCallRef("blank", "bash", "{}"));
        calls.add(new ToolCallRef("keep-0", "edit", "{}"));
        calls.add(new ToolCallRef("keep-1", "write", "{}"));
        calls.add(new ToolCallRef("keep-2", "todo", "{}"));

        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.assistantWithToolCalls("", calls));
        history.add(ChatMessage.toolResult("old", "read", longResult("old")));
        history.add(ChatMessage.toolResult("short", "glob", shortBody));
        history.add(ChatMessage.toolResult("persisted", "grep", persistedBody));
        ChatMessage blank = ChatMessage.toolResult("blank", "bash", "unused");
        blank.setContent(null);
        history.add(blank);
        history.add(ChatMessage.toolResult("keep-0", "edit", longResult("keep-0")));
        history.add(ChatMessage.toolResult("keep-1", "write", longResult("keep-1")));
        history.add(ChatMessage.toolResult("keep-2", "todo", longResult("keep-2")));
        history.add(new ChatMessage("assistant", "已读完"));
        List<String> original = contents(history);

        List<ChatMessage> managed = manager().getContextForAI(history);

        assertHistoryUntouched(original, history);
        assertIdentityFields(history, managed);
        assertEquals("[Previous: used read]", contentOf(managed, "old"),
                "a long read result outside the recent quota is still replaced");
        assertEquals(shortBody, contentOf(managed, "short"));
        assertEquals(persistedBody, contentOf(managed, "persisted"));
        assertNull(contentOf(managed, "blank"));
        assertEquals(longResult("keep-0"), contentOf(managed, "keep-0"));
        assertEquals(longResult("keep-1"), contentOf(managed, "keep-1"));
        assertEquals(longResult("keep-2"), contentOf(managed, "keep-2"));
    }

    private static ContextManager manager() {
        AppConfig config = new AppConfig();
        config.getAi().setKeepRecentToolResults(3);
        config.getAi().setMaxMessages(500);
        config.getAi().setMaxContextTokens(200_000);
        config.getAi().setMaxToolResultBytes(2_000_000);
        return new ContextManager(config, null, null, null);
    }

    /** assistant 发起 count 个调用，随后紧跟等量的长工具结果。 */
    private static List<ChatMessage> pairedTools(String toolName, int count) {
        List<ToolCallRef> calls = new ArrayList<>();
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            calls.add(new ToolCallRef(toolName + "-" + i, toolName, "{}"));
        }
        messages.add(ChatMessage.assistantWithToolCalls("", calls));
        for (int i = 0; i < count; i++) {
            String id = toolName + "-" + i;
            messages.add(ChatMessage.toolResult(id, toolName, longResult(id)));
        }
        return messages;
    }

    private static String longResult(String marker) {
        return marker + ":" + "x".repeat(120);
    }

    private static List<String> contents(List<ChatMessage> messages) {
        return messages.stream().map(ChatMessage::getContent).toList();
    }

    private static void assertHistoryUntouched(List<String> original, List<ChatMessage> history) {
        assertEquals(original, contents(history), "getContextForAI must not mutate the caller's history");
    }

    private static void assertIdentityFields(List<ChatMessage> history, List<ChatMessage> managed) {
        assertEquals(history.size(), managed.size(), "microCompact must not drop or add messages");
        for (int i = 0; i < history.size(); i++) {
            assertEquals(history.get(i).getRole(), managed.get(i).getRole());
            assertEquals(history.get(i).getToolCallId(), managed.get(i).getToolCallId());
            assertEquals(history.get(i).getToolName(), managed.get(i).getToolName());
        }
    }

    private static String contentOf(List<ChatMessage> messages, String toolCallId) {
        for (ChatMessage message : messages) {
            if (toolCallId.equals(message.getToolCallId())) {
                return message.getContent();
            }
        }
        throw new AssertionError("missing tool result " + toolCallId);
    }
}
