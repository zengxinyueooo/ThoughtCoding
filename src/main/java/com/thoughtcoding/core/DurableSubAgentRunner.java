package com.thoughtcoding.core;

import com.thoughtcoding.security.Sandbox;
import com.thoughtcoding.task.ExecutionCheckpoint;
import com.thoughtcoding.task.TaskService;
import com.thoughtcoding.task.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Owns the child lease through execution, worktree finalization and result publication. */
public final class DurableSubAgentRunner {
    private final ThoughtCodingContext context;
    public DurableSubAgentRunner(ThoughtCodingContext context) { this.context = context; }

    public String run(String taskId, String sessionId, CancelToken token) {
        TaskService service = context.getTaskService();
        ExecutionCheckpoint saved = service.store().load(taskId);
        if (!sessionId.equals(saved.task().sessionId()) || saved.task().parentTaskId() == null)
            throw new IllegalArgumentException("不是当前会话的子任务");
        if (saved.phase() == ExecutionCheckpoint.Phase.FINISHED && saved.metadata().containsKey("resultId"))
            return saved.metadata().get("result");
        Path workspace = Path.of(saved.workspace());
        if (!Files.isDirectory(workspace)) throw new IllegalStateException("子任务工作目录已丢失: " + workspace);
        try (var scope = Sandbox.enterWorkspace(workspace);
             var task = service.open(taskId, sessionId, true)) {
            if (saved.metadata().containsKey("resultId"))
                task.metadata(Map.of("deliverToInbox", "true"));
            String label = saved.metadata().getOrDefault("label", "SubAgent");
            java.util.function.Function<WorktreeManager.TaskRecord, String> action = artifact -> {
                if (artifact != null && !task.checkpoint().metadata().containsKey("worktreeTaskId")) {
                    task.relocate(Sandbox.workspaceRoot().toString(), Map.of("worktreeTaskId", artifact.id(),
                            "branch", artifact.branch(), "baseCommit", artifact.baseCommit(), "repository", artifact.repository()));
                }
                try {
                    return new SubAgent(context).resume(label, token, task);
                } catch (com.thoughtcoding.task.TaskPersistenceException failure) {
                    throw failure;
                } catch (RuntimeException failure) {
                    task.status(token != null && token.isCancelled()
                            ? (token.isPauseRequested() ? TaskStatus.PAUSED : TaskStatus.CANCELLED) : TaskStatus.FAILED,
                            failure.getMessage());
                    return "子任务执行失败: " + failure.getMessage();
                }
            };
            String result;
            if ("true".equals(saved.metadata().get("isolated"))) {
                WorktreeManager manager = new WorktreeManager(Sandbox.baseWorkspaceRoot());
                String artifactId = saved.metadata().get("worktreeTaskId");
                WorktreeManager.RunResult outcome = artifactId == null
                        ? manager.runDurable(label, taskId, action) : manager.resumeManaged(artifactId, action);
                Map<String, String> metadata = new HashMap<>();
                metadata.put("worktreeTaskId", outcome.outcome().taskId());
                metadata.put("artifactStatus", outcome.outcome().status().name());
                if (outcome.outcome().commit() != null) metadata.put("commit", outcome.outcome().commit());
                task.metadata(metadata);
                result = outcome.combinedOutput();
            } else result = action.apply(null);
            String published = "[子任务 " + taskId + "，状态 " + task.state().status() + "]\n" + result;
            task.metadata(Map.of("resultId", taskId + ":" + task.state().runId(), "result", published));
            return published;
        }
    }
}
