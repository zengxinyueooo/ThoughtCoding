package com.thoughtcoding.task;

import java.nio.file.Path;
import java.util.List;

/** Application facade: SQLite persistence plus independent, long-lived execution leases. */
public final class TaskStore implements TaskStorage {
    private final SqliteTaskStore storage;
    private final TaskLeaseManager leases;

    /** root is the legacy tasks directory; the database lives alongside it. */
    public TaskStore(Path root) {
        Path legacy = root.toAbsolutePath().normalize();
        leases = new TaskLeaseManager(legacy);
        storage = new SqliteTaskStore(legacy.resolveSibling("state.db"));
        new JsonTaskStore(legacy).migrateTo(storage);
    }

    public Path databasePath() { return storage.databasePath(); }
    public TaskLeaseManager.Lease lock(String taskId) { return leases.lock(taskId); }
    public ExecutionCheckpoint load(String taskId) { return storage.load(taskId); }
    public boolean exists(String taskId) { return storage.exists(taskId); }
    public void commit(ExecutionCheckpoint checkpoint, long expectedVersion) { storage.commit(checkpoint, expectedVersion); }
    public List<TaskState> list(String sessionId) { return storage.list(sessionId); }
    public List<Long> checkpoints(String taskId) { return storage.checkpoints(taskId); }
}
