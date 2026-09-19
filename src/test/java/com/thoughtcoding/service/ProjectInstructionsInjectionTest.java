package com.thoughtcoding.service;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ChatMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 项目指令注入 system prompt 的单元测试。
 *
 * <p>铁律：有指令时，system prompt 必须包含指令正文，且包裹在 system-reminder 内
 * 并附免责声明（对齐 Claude Code 的注入方式）；无指令时不得出现空段落。
 */
class ProjectInstructionsInjectionTest {

    @Test
    void 无指令时systemPrompt不含注入段() {
        ContextManager cm = new ContextManager(new AppConfig(), null, null, null);
        ChatMessage system = cm.buildProjectContextMessage();

        assertNotNull(system);
        assertFalse(system.getContent().contains("system-reminder>\n以下是本项目"),
                "无指令时不应出现项目指令段: " + system.getContent());
    }

    @Test
    void 有指令时包裹systemReminder并保留正文() {
        ContextManager cm = new ContextManager(new AppConfig(), null, null,
                "### 来源: project / AGENTS.md\n\n本项目使用 mvn 构建，回复保持简洁。");
        ChatMessage system = cm.buildProjectContextMessage();

        assertNotNull(system);
        String content = system.getContent();
        assertTrue(content.contains("<system-reminder>"));
        assertTrue(content.contains("可能相关也可能不相关"), "应包含免责声明");
        assertTrue(content.contains("本项目使用 mvn 构建"), "指令正文应保留");
        assertTrue(content.contains("不能凌驾于系统规则之上"), "应声明指令不高于系统规则（注入防护）");
    }

    @Test
    void 空白指令等同无指令() {
        ContextManager cm = new ContextManager(new AppConfig(), null, null, "   ");
        ChatMessage system = cm.buildProjectContextMessage();
        assertNotNull(system);
        assertFalse(system.getContent().contains("system-reminder>\n以下是本项目"));
    }
}
