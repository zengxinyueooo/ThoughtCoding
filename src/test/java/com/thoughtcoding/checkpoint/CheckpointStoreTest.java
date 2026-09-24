package com.thoughtcoding.checkpoint;

import com.thoughtcoding.security.Sandbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 检查点存储的单元测试。
 *
 * <p>铁律：
 * <ul>
 *   <li>快照-修改-恢复往返：内容精确还原</li>
 *   <li>「原文件不存在」的恢复语义是删除当前文件</li>
 *   <li>工作区外路径不快照、不恢复（双保险不变量）</li>
 *   <li>会话隔离：A 会话的检查点对 B 会话不可见；clearSession 只清自己</li>
 * </ul>
 */
class CheckpointStoreTest {

    @TempDir
    Path workspace;

    @TempDir
    Path storeRoot;

    @BeforeEach
    void pinWorkspace() {
        Sandbox.init(workspace.toString());
    }

    private CheckpointStore store() {
        return CheckpointStore.create(storeRoot);
    }

    @Test
    void 快照修改恢复往返() throws Exception {
        CheckpointStore store = store();
        Path file = workspace.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "原始内容 v1");

        CheckpointStore.Checkpoint cp = store.snapshot("s1", "src/App.java", "edit 前");
        assertNotNull(cp);
        assertTrue(cp.existed());
        assertEquals(file.toAbsolutePath().normalize().toString(), cp.originalPath());

        Files.writeString(file, "被模型改坏的内容 v2");
        assertTrue(store.restore("s1", cp.seq()));
        assertEquals("原始内容 v1", Files.readString(file));
    }

    @Test
    void 原文件不存在的恢复语义是删除() throws Exception {
        CheckpointStore store = store();
        CheckpointStore.Checkpoint cp = store.snapshot("s1", "new-file.txt", "write 前");
        assertNotNull(cp);
        assertFalse(cp.existed());

        Files.writeString(workspace.resolve("new-file.txt"), "模型新建的内容");
        assertTrue(store.restore("s1", cp.seq()));
        assertFalse(Files.exists(workspace.resolve("new-file.txt")));
    }

    @Test
    void 工作区外路径不快照() {
        CheckpointStore store = store();

        // storeRoot 在 workspace 之外
        assertNull(store.snapshot("s1", storeRoot.resolve("outside.txt").toString(), "write 前"));
        assertTrue(store.list("s1").isEmpty());
    }

    @Test
    void 多次快照序号递增且列表有序() throws Exception {
        CheckpointStore store = store();
        Files.writeString(workspace.resolve("a.txt"), "A");
        Files.writeString(workspace.resolve("b.txt"), "B");

        assertNotNull(store.snapshot("s1", "a.txt", "edit 前"));
        assertNotNull(store.snapshot("s1", "a.txt", "edit 前"));
        assertNotNull(store.snapshot("s1", "b.txt", "edit 前"));

        List<CheckpointStore.Checkpoint> list = store.list("s1");
        assertEquals(3, list.size());
        assertEquals(1, list.get(0).seq());
        assertEquals(3, list.get(2).seq());
    }

    @Test
    void 会话隔离与clearSession() throws Exception {
        CheckpointStore store = store();
        Files.writeString(workspace.resolve("a.txt"), "A");
        assertNotNull(store.snapshot("s1", "a.txt", "edit 前"));
        assertNotNull(store.snapshot("s2", "a.txt", "edit 前"));

        assertEquals(1, store.list("s1").size());
        store.clearSession("s1");
        assertTrue(store.list("s1").isEmpty());
        assertEquals(1, store.list("s2").size());
    }

    @Test
    void 恢复不存在的序号返回false() {
        assertFalse(store().restore("s1", 99));
    }

    @Test
    void clearAll清空全部会话() throws Exception {
        CheckpointStore store = store();
        Files.writeString(workspace.resolve("a.txt"), "A");
        assertNotNull(store.snapshot("s1", "a.txt", "edit 前"));
        assertNotNull(store.snapshot("s2", "a.txt", "edit 前"));

        store.clearAll();
        assertTrue(store.list("s1").isEmpty());
        assertTrue(store.list("s2").isEmpty());
        assertFalse(Files.exists(storeRoot.resolve("s1")));
    }
}
