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
    void keywordScoringSelectsRelevantMemoryInLargerStore() {
        MemoryStore store = MemoryStore.load(tempDir, 20);
        store.write("tab-indent", "user", "缩进偏好", "用户偏好使用tab缩进。");
        store.write("commit-style", "feedback", "提交信息风格", "提交信息用中文详细描述。");
        store.write("db-config", "project", "数据库配置", "项目使用PostgreSQL。");
        store.write("test-cmd", "project", "测试命令", "用mvn test跑测试。");
        MemoryService service = new MemoryService(new AppConfig(), store, new AppConfig.MemoryConfig());

        String recalled = service.recall(List.of(new ChatMessage("user", "帮我按我的缩进偏好改这个文件")));

        assertTrue(recalled.contains("tab缩进"));
        assertFalse(recalled.contains("PostgreSQL"));
    }

    @Test
    void keywordScoringReturnsEmptyWhenNothingRelevant() {
        MemoryStore store = MemoryStore.load(tempDir, 20);
        store.write("tab-indent", "user", "缩进偏好", "用户偏好使用tab缩进。");
        store.write("commit-style", "feedback", "提交信息风格", "提交信息用中文详细描述。");
        store.write("db-config", "project", "数据库配置", "项目使用PostgreSQL。");
        store.write("test-cmd", "project", "测试命令", "用mvn test跑测试。");
        MemoryService service = new MemoryService(new AppConfig(), store, new AppConfig.MemoryConfig());

        String recalled = service.recall(List.of(new ChatMessage("user", "今天天气怎么样")));

        assertEquals("", recalled);
    }

    @Test
    void selectByKeywordRanksIndexFieldHitsAboveBodyHits() {
        // 第三条完全无关是必要条件：关键词必须只命中部分记忆（df<N），否则 IDF=ln(N/N)=0
        List<MemoryStore.Memory> memories = List.of(
                new MemoryStore.Memory("", "editor", "标签页布局", "user", "无关正文"),
                new MemoryStore.Memory("", "other", "无关描述", "user", "正文里提到标签页一次"),
                new MemoryStore.Memory("", "misc", "其他配置", "user", "完全无关"));

        List<Integer> ranked = MemoryService.selectByKeyword(memories, "请用标签页");

        assertEquals(List.of(0, 1), ranked);
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
