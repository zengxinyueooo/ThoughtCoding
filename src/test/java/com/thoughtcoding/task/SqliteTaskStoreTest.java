package com.thoughtcoding.task;

import com.thoughtcoding.model.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SqliteTaskStoreTest {
    @TempDir Path directory;
    private Path database() { return directory.resolve("state.db"); }
    private Path legacy() { return directory.resolve("tasks"); }

    private ExecutionCheckpoint cp(String id, long version) {
        return new ExecutionCheckpoint(1,
                new TaskState(id, "session", null, "objective", TaskStatus.PAUSED, "test",
                        List.of(new TaskStep("step", "verify", "completed", "test evidence")),
                        "run", version, "2026-01-01", "2026-01-02"),
                directory.toString(), Map.of(), ExecutionCheckpoint.Phase.BEFORE_MODEL, 2, 0, false,
                List.of(ExecutionCheckpoint.Message.from(new ChatMessage("user", "history"))), List.of(), List.of(),
                Map.of("resultId", "result", "result", "output"), Set.of("receipt"));
    }

    private String generation(ExecutionCheckpoint cp, boolean head) throws Exception {
        Path dir = legacy().resolve(cp.task().taskId());
        Files.createDirectories(dir);
        String name = cp.task().version() + "-" + UUID.randomUUID() + ".json";
        Files.writeString(dir.resolve(name), SqliteTaskStore.JSON.writeValueAsString(cp));
        if (head) Files.writeString(dir.resolve("HEAD"), name);
        return name;
    }

    @Test void snapshotsAndIndexedTaskStatePersistAcrossConnections() throws Exception {
        var store = new SqliteTaskStore(database());
        store.commit(cp("task", 1), 0);
        store.commit(cp("task", 2), 1);
        var restarted = new SqliteTaskStore(database());
        assertEquals(cp("task", 2), restarted.load("task"));
        assertEquals(List.of(1L, 2L), restarted.checkpoints("task"));
        assertEquals(List.of(cp("task", 2).task()), restarted.list("session"));
        assertTrue(restarted.list("other").isEmpty());
        try (var c = restarted.connect(); var s = c.createStatement()) {
            try (var r = s.executeQuery("PRAGMA integrity_check")) { assertTrue(r.next()); assertEquals("ok", r.getString(1)); }
            try (var r = s.executeQuery("PRAGMA foreign_key_check")) { assertFalse(r.next()); }
            try (var r = s.executeQuery("PRAGMA journal_mode")) { r.next(); assertEquals("wal", r.getString(1)); }
            try (var r = s.executeQuery("PRAGMA synchronous")) { r.next(); assertEquals(2, r.getInt(1)); }
        }
    }

    @Test void checkpointInsertFailureRollsBackAlreadyUpdatedTaskPointer() throws Exception {
        var store = new SqliteTaskStore(database());
        store.commit(cp("task", 1), 0);
        try (var c = store.connect(); var s = c.createStatement()) {
            s.execute("CREATE TRIGGER fail_snapshot BEFORE INSERT ON checkpoints WHEN NEW.version=2 BEGIN SELECT RAISE(ABORT,'injected failure'); END");
        }
        assertThrows(TaskPersistenceException.class, () -> store.commit(cp("task", 2), 1));
        assertEquals(cp("task", 1), store.load("task"));
        assertEquals(1, store.list("session").getFirst().version());
        assertEquals(List.of(1L), store.checkpoints("task"));
    }

    @Test void optimisticVersionAllowsExactlyOneCompetingWriter() throws Exception {
        var first = new SqliteTaskStore(database());
        var second = new SqliteTaskStore(database());
        first.commit(cp("task", 1), 0);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (var store : List.of(first, second)) results.add(executor.submit(() -> {
                start.await();
                try { store.commit(cp("task", 2), 1); return true; }
                catch (TaskPersistenceException conflict) { return false; }
            }));
            start.countDown();
            int successes = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        }
        assertEquals(List.of(1L, 2L), first.checkpoints("task"));
    }

    @Test void writerLockTimesOutAndReadersKeepSeeingCommittedState() throws Exception {
        var store = new SqliteTaskStore(database(), 100);
        store.commit(cp("task", 1), 0);
        try (var c = store.connect(); var s = c.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
            long start = System.nanoTime();
            assertThrows(TaskPersistenceException.class, () -> store.commit(cp("task", 2), 1));
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5);
            assertEquals(cp("task", 1), store.load("task"));
            s.execute("ROLLBACK");
        }
        store.commit(cp("task", 2), 1);
    }

    @Test void importsHistoryAndFencesOldWriterWithoutOverwritingNewerDatabaseState() throws Exception {
        generation(cp("task", 1), false);
        String head = generation(cp("task", 2), true);
        Files.writeString(legacy().resolve("task/999-" + UUID.randomUUID() + ".json"), "{unfinished");
        var store = new TaskStore(legacy());
        assertEquals(cp("task", 2), store.load("task"));
        assertEquals(List.of(1L, 2L), store.checkpoints("task"));
        assertEquals("SQLITE-MIGRATED:" + head, Files.readString(legacy().resolve("task/HEAD")));
        assertTrue(Files.exists(legacy().resolve("task").resolve(head)));
        store.commit(cp("task", 3), 2);
        try (var running = store.lock("task")) {
            assertEquals(cp("task", 3), new TaskStore(legacy()).load("task"));
        }
    }

    @Test void activeLegacyProcessBlocksMigrationBeforeFencing() throws Exception {
        String head = generation(cp("task", 1), true);
        try (var held = new TaskLeaseManager(legacy()).lock("task")) {
            assertThrows(TaskPersistenceException.class, () -> new TaskStore(legacy()));
            assertEquals(head, Files.readString(legacy().resolve("task/HEAD")));
        }
        assertEquals(cp("task", 1), new TaskStore(legacy()).load("task"));
    }

    @Test void failedImportIsAtomicAndCanResumeFromPendingFences() throws Exception {
        generation(cp("first", 1), true);
        generation(cp("second", 1), true);
        var store = new SqliteTaskStore(database());
        try (var c = store.connect(); var s = c.createStatement()) {
            s.execute("CREATE TRIGGER fail_import BEFORE INSERT ON checkpoints WHEN NEW.task_id='second' BEGIN SELECT RAISE(ABORT,'injected failure'); END");
        }
        assertThrows(TaskPersistenceException.class, () -> new TaskStore(legacy()));
        assertTrue(store.list(null).isEmpty());
        assertTrue(Files.readString(legacy().resolve("first/HEAD")).startsWith("SQLITE-PENDING:"));
        try (var c = store.connect(); var s = c.createStatement()) { s.execute("DROP TRIGGER fail_import"); }
        assertEquals(2, new TaskStore(legacy()).list(null).size());
        assertEquals(2, new TaskStore(legacy()).list(null).size());
    }

    @Test void crashAfterDatabaseImportBeforeFinalFenceIsIdempotent() throws Exception {
        String head = generation(cp("task", 1), true);
        new TaskStore(legacy());
        Files.writeString(legacy().resolve("task/HEAD"), "SQLITE-PENDING:" + head);
        assertEquals(List.of(1L), new TaskStore(legacy()).checkpoints("task"));
        assertEquals("SQLITE-MIGRATED:" + head, Files.readString(legacy().resolve("task/HEAD")));
    }

    @Test void malformedHistoryStopsMigrationWithoutChangingLegacyHead() throws Exception {
        String old = generation(cp("task", 1), false);
        String head = generation(cp("task", 2), true);
        Files.writeString(legacy().resolve("task").resolve(old), "{broken");
        assertThrows(TaskPersistenceException.class, () -> new TaskStore(legacy()));
        assertEquals(head, Files.readString(legacy().resolve("task/HEAD")));
    }

    @Test void completedMigrationNeverSilentlyFallsBackWhenDatabaseRecordIsMissing() throws Exception {
        generation(cp("task", 1), true);
        new TaskStore(legacy());
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + database()); var s = c.createStatement()) {
            s.execute("DELETE FROM legacy_imports");
        }
        assertThrows(TaskPersistenceException.class, () -> new TaskStore(legacy()));
    }

    @Test void rejectsFutureDatabaseSchemaWithoutResettingIt() throws Exception {
        new SqliteTaskStore(database());
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + database()); var s = c.createStatement()) { s.execute("PRAGMA user_version=99"); }
        assertThrows(TaskPersistenceException.class, () -> new SqliteTaskStore(database()));
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + database()); var s = c.createStatement(); var r = s.executeQuery("PRAGMA user_version")) {
            r.next(); assertEquals(99, r.getInt(1));
        }
    }

    @Test void processDeathRollsBackUncommittedVersionChange() throws Exception {
        var store = new SqliteTaskStore(database());
        store.commit(cp("task", 1), 0);
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), CrashWriter.class.getName(), database().toString())
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(23, process.exitValue(), new String(process.getInputStream().readAllBytes()));
        } finally { process.destroyForcibly(); }
        assertEquals(cp("task", 1), new SqliteTaskStore(database()).load("task"));
        store.commit(cp("task", 2), 1);
    }

    public static class CrashWriter {
        public static void main(String[] args) throws Exception {
            var c = DriverManager.getConnection("jdbc:sqlite:" + args[0]);
            var s = c.createStatement();
            s.execute("BEGIN IMMEDIATE");
            s.execute("UPDATE tasks SET current_version=999 WHERE task_id='task'");
            Runtime.getRuntime().halt(23);
        }
    }
}
