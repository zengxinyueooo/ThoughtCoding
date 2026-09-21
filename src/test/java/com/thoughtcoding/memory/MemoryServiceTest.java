package com.thoughtcoding.memory;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void fallsBackToKeywordRecallWhenNoModelIsConfigured() {
        MemoryStore store = MemoryStore.load(tempDir, 20);
        store.write("formatting", "user", "preferred formatting style", "Use tabs for indentation.");
        AppConfig.MemoryConfig config = new AppConfig.MemoryConfig();
        MemoryService service = new MemoryService(new AppConfig(), store, config);

        String recalled = service.recall(List.of(new ChatMessage("user", "Apply my formatting preference")));

        assertTrue(recalled.contains("Use tabs for indentation."));
    }

    @Test
    void recallRespectsTheConfiguredCharacterBudget() {
        MemoryStore store = MemoryStore.load(tempDir, 20);
        store.write("formatting", "user", "preferred formatting style", "1234567890abcdefghij");
        AppConfig.MemoryConfig config = new AppConfig.MemoryConfig();
        config.setMaxInjectionChars(10);
        MemoryService service = new MemoryService(new AppConfig(), store, config);

        String recalled = service.recall(List.of(new ChatMessage("user", "Apply my formatting preference")));

        assertTrue(recalled.contains("1234567890"));
        assertFalse(recalled.contains("abcdefghij"));
        assertTrue(recalled.contains("已按上下文预算截断"));
    }

    @Test
    void keywordFallbackCanRecallChineseDescriptions() {
        MemoryStore store = MemoryStore.load(tempDir, 20);
        store.write("editor", "user", "用户偏好使用标签页", "编辑器使用标签页布局。");
        MemoryService service = new MemoryService(new AppConfig(), store, new AppConfig.MemoryConfig());

        String recalled = service.recall(List.of(new ChatMessage("user", "请继续使用标签页")));

        assertTrue(recalled.contains("编辑器使用标签页布局。"));
    }

    @Test
    void recallContextKeepsTheNewestPartWhenItExceedsBudget() {
        List<ChatMessage> history = List.of(
                new ChatMessage("user", "older-question"),
                new ChatMessage("assistant", "answer"),
                new ChatMessage("user", "newest-question"));

        String recent = MemoryService.recentUserText(history, 15);

        assertEquals("newest-question", recent);
        assertFalse(recent.contains("older"));
    }

    @Test
    void extractionContextKeepsLatestDialogueWhenItExceedsBudget() {
        List<ChatMessage> history = List.of(
                new ChatMessage("user", "old detail that should be trimmed"),
                new ChatMessage("assistant", "old response"),
                new ChatMessage("user", "LATEST_MARKER"));

        String recent = MemoryService.recentDialogue(history, 24);

        assertTrue(recent.contains("LATEST_MARKER"));
        assertFalse(recent.contains("old detail"));
    }
}
