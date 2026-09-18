package com.thoughtcoding.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 文件状态跟踪器的单元测试：stale-read 校验的状态机。
 *
 * <p>这是「agent 认知与现实一致性」防线的核心语义：
 * 读过且未变 → CLEAN；读过但被外部改 → STALE；从未读过 → NEVER_READ。
 */
class FileStateTrackerTest {

    @TempDir
    Path dir;

    @Test
    void 未登记的文件为NEVER_READ() {
        FileStateTracker tracker = new FileStateTracker();
        assertEquals(FileStateTracker.State.NEVER_READ,
                tracker.checkStale(dir.resolve("a.txt")));
    }

    @Test
    void 读后未变是CLEAN_外部修改后是STALE() throws Exception {
        FileStateTracker tracker = new FileStateTracker();
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);

        tracker.recordRead(file);
        assertEquals(FileStateTracker.State.CLEAN, tracker.checkStale(file));

        // 外部修改（内容变化 → mtime/size 变化）
        Thread.sleep(20); // 保证 mtime 可观测地前进
        Files.writeString(file, "hello world, modified by someone else", StandardCharsets.UTF_8);
        assertEquals(FileStateTracker.State.STALE, tracker.checkStale(file));
    }

    @Test
    void 重新登记后恢复CLEAN() throws Exception {
        FileStateTracker tracker = new FileStateTracker();
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "v1", StandardCharsets.UTF_8);
        tracker.recordRead(file);

        Thread.sleep(20);
        Files.writeString(file, "v2", StandardCharsets.UTF_8);
        assertEquals(FileStateTracker.State.STALE, tracker.checkStale(file));

        tracker.recordRead(file); // 模型重新 read：认知与现实重新对齐
        assertEquals(FileStateTracker.State.CLEAN, tracker.checkStale(file));
    }

    @Test
    void 文件被删除视为STALE() throws Exception {
        FileStateTracker tracker = new FileStateTracker();
        Path file = dir.resolve("gone.txt");
        Files.writeString(file, "x", StandardCharsets.UTF_8);
        tracker.recordRead(file);

        Files.delete(file);
        assertEquals(FileStateTracker.State.STALE, tracker.checkStale(file));
    }
}
