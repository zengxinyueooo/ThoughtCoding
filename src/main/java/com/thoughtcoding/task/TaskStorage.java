package com.thoughtcoding.task;

import java.util.List;

/** Persistence contract; execution leases deliberately live outside database transactions. */
public interface TaskStorage {
    ExecutionCheckpoint load(String taskId);
    boolean exists(String taskId);
    void commit(ExecutionCheckpoint checkpoint, long expectedVersion);
    List<TaskState> list(String sessionId);
    List<Long> checkpoints(String taskId);
}
