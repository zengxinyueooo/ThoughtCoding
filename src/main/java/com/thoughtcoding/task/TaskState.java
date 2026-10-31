package com.thoughtcoding.task;

import java.util.List;

/** User objective and lifecycle; worktree artifact status remains separate. */
public record TaskState(String taskId, String sessionId, String parentTaskId, String objective,
                        TaskStatus status, String stopReason, List<TaskStep> steps,
                        String runId, long version, String createdAt, String updatedAt) {
    public TaskState {
        steps = List.copyOf(steps);
    }
}
