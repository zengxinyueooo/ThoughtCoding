package com.thoughtcoding.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 项目指令加载器的单元测试：对齐 Claude Code 的回退语义。
 *
 * <p>铁律：每层内部 CLAUDE.md 严格优先于 AGENTS.md；两层独立决策；
 * 无文件时空内容不报错；超长文件截断并标注。
 */
class ProjectInstructionsLoaderTest {

    @TempDir
    Path dir;

    @Test
    void 同时存在时CLAUDE_md优先于AGENTS_md() throws Exception {
        Files.writeString(dir.resolve("CLAUDE.md"), "claude rules", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("AGENTS.md"), "agents rules", StandardCharsets.UTF_8);

        ProjectInstructionsLoader.Loaded loaded = ProjectInstructionsLoader.load(dir);

        assertTrue(loaded.content().contains("claude rules"));
        assertFalse(loaded.content().contains("agents rules"));
        assertTrue(loaded.sources().stream().anyMatch(s -> s.contains("CLAUDE.md")));
    }

    @Test
    void 只有AGENTS_md时回退读取() throws Exception {
        Files.writeString(dir.resolve("AGENTS.md"), "agents rules only", StandardCharsets.UTF_8);

        ProjectInstructionsLoader.Loaded loaded = ProjectInstructionsLoader.load(dir);

        assertTrue(loaded.content().contains("agents rules only"));
        assertTrue(loaded.sources().stream().anyMatch(s -> s.endsWith("AGENTS.md")));
    }

    @Test
    void 无任何指令文件时空内容不报错() {
        ProjectInstructionsLoader.Loaded loaded = ProjectInstructionsLoader.load(dir);

        assertTrue(loaded.isEmpty());
        assertTrue(loaded.sources().isEmpty());
    }

    @Test
    void 目录不存在时安全降级() {
        ProjectInstructionsLoader.Loaded loaded =
                ProjectInstructionsLoader.load(dir.resolve("no-such-dir"));

        assertTrue(loaded.isEmpty());
    }

    @Test
    void 全局层与项目层合并_全局在前() throws Exception {
        Path fakeHome = dir.resolve("home");
        Path globalDir = fakeHome.resolve(".thoughtcoding");
        Files.createDirectories(globalDir);
        Files.writeString(globalDir.resolve("AGENTS.md"), "GLOBAL PREF", StandardCharsets.UTF_8);

        Path project = dir.resolve("proj");
        Files.createDirectories(project);
        Files.writeString(project.resolve("CLAUDE.md"), "PROJECT RULES", StandardCharsets.UTF_8);

        // 临时重定向 user.home（恢复交给 try/finally）
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", fakeHome.toString());
            ProjectInstructionsLoader.Loaded loaded = ProjectInstructionsLoader.load(project);

            assertTrue(loaded.content().contains("GLOBAL PREF"));
            assertTrue(loaded.content().contains("PROJECT RULES"));
            assertTrue(loaded.content().indexOf("GLOBAL PREF") < loaded.content().indexOf("PROJECT RULES"),
                    "全局层应排在项目层之前（越近越具体）");
            assertEquals(2, loaded.sources().size());
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void 超长文件被截断并标注() throws Exception {
        StringBuilder big = new StringBuilder();
        while (big.length() < ProjectInstructionsLoader.MAX_FILE_BYTES / 2 + 100_000) {
            big.append("repeat me please ").append('x');
        }
        Files.writeString(dir.resolve("AGENTS.md"), big.toString(), StandardCharsets.UTF_8);

        ProjectInstructionsLoader.Loaded loaded = ProjectInstructionsLoader.load(dir);

        assertTrue(loaded.content().contains("已截断"));
        assertTrue(loaded.content().getBytes(StandardCharsets.UTF_8).length
                <= ProjectInstructionsLoader.MAX_FILE_BYTES + 200); // 截断标注本身有额外开销
    }

    @Test
    void 非法UTF8文件跳过并回退到AGENTS_md() throws Exception {
        Files.write(dir.resolve("CLAUDE.md"),
                new byte[]{(byte) 0xFF, (byte) 0xFE, 'x', 'y'}); // 非 UTF-8 字节流，readString 必抛
        Files.writeString(dir.resolve("AGENTS.md"), "fallback works", StandardCharsets.UTF_8);

        ProjectInstructionsLoader.Loaded loaded = ProjectInstructionsLoader.load(dir);
        // CLAUDE.md 解码失败 → 跳过该层 → 回退到 AGENTS.md，且绝不抛异常
        assertTrue(loaded.content().contains("fallback works"),
                "应回退到 AGENTS.md: " + loaded.content());
        assertTrue(loaded.sources().stream().noneMatch(s -> s.contains("CLAUDE.md")));
    }

    @Test
    void 空白指令文件视为未配置() throws Exception {
        Files.writeString(dir.resolve("AGENTS.md"), "   \n  ", StandardCharsets.UTF_8);
        ProjectInstructionsLoader.Loaded loaded = ProjectInstructionsLoader.load(dir);
        assertTrue(loaded.isEmpty());
    }
}
