package com.thoughtcoding.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void metadataRoundTripsWhenYamlNeedsQuoting() {
        MemoryStore store = MemoryStore.load(tempDir, 20);
        Path path = store.write("editor: preferences", "user",
                "Use: tabs\nand preserve # comments", "Keep the exact formatting.");

        assertNotNull(path);
        MemoryStore reloaded = MemoryStore.load(tempDir, 20);
        MemoryStore.Memory memory = reloaded.list().get(0);
        assertEquals("editor: preferences", memory.name());
        assertEquals("Use: tabs\nand preserve # comments", memory.description());
        assertEquals("Keep the exact formatting.", memory.body());
    }

    @Test
    void replaceAllPersistsTheSameCollectionAfterReload() {
        MemoryStore store = MemoryStore.load(tempDir, 20);
        store.write("old", "project", "old fact", "old body");

        boolean replaced = store.replaceAll(List.of(
                new MemoryStore.Memory("", "first", "first: summary", "user", "first body"),
                new MemoryStore.Memory("", "second", "second summary", "project", "second body")));

        assertTrue(replaced);
        MemoryStore reloaded = MemoryStore.load(tempDir, 20);
        assertEquals(List.of("first", "second"),
                reloaded.list().stream().map(MemoryStore.Memory::name).toList());
        assertTrue(Files.notExists(tempDir.resolve("old.md")));
    }

    @Test
    void indexHonorsConfiguredEntryLimit() {
        MemoryStore store = MemoryStore.load(tempDir, 1);
        store.write("first", "user", "one", "body");
        store.write("second", "user", "two", "body");

        assertTrue(store.index().contains("first"));
        assertTrue(!store.index().contains("second"));
    }

    @Test
    void startupIgnoresTemporaryAndUnrelatedFiles() throws Exception {
        Files.writeString(tempDir.resolve("orphan.md.tmp"), "must not load");
        Files.writeString(tempDir.resolve("notes.txt"), "must not load");
        Files.writeString(tempDir.resolve("valid.md"), "valid body");

        MemoryStore store = MemoryStore.load(tempDir, 20);

        assertEquals(1, store.size());
        assertEquals("valid.md", store.list().get(0).filename());
    }

    @Test
    void collidingNamesDoNotOverwriteEachOther() {
        MemoryStore store = MemoryStore.load(tempDir, 20);

        assertTrue(store.replaceAll(List.of(
                new MemoryStore.Memory("", "foo bar", "one", "user", "first"),
                new MemoryStore.Memory("", "foo-bar", "two", "user", "second"))));

        MemoryStore reloaded = MemoryStore.load(tempDir, 20);
        assertEquals(2, reloaded.size());
        assertEquals(Set.of("first", "second"),
                reloaded.list().stream().map(MemoryStore.Memory::body).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void deleteByNameAndByFilenameRemovesEntryAndRebuildsIndex() {
        MemoryStore store = MemoryStore.load(tempDir, 20);
        store.write("user-preference", "user", "pref summary", "pref body");
        store.write("project-fact", "project", "fact summary", "fact body");
        assertTrue(store.index().contains("user-preference"));

        assertTrue(store.delete("user-preference"));           // 按 name 删
        assertTrue(store.delete("project-fact.md"));           // 按文件名删
        assertFalse(store.delete("nonexistent"));
        assertTrue(store.isEmpty());
        assertTrue(store.index().isEmpty());
        assertTrue(Files.notExists(tempDir.resolve("user-preference.md")));

        // 删除要真正落盘：重载后不应复活
        MemoryStore reloaded = MemoryStore.load(tempDir, 20);
        assertTrue(reloaded.isEmpty());
    }
}
