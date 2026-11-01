package com.thoughtcoding.task;

import com.thoughtcoding.core.ToolExecutionPipeline;
import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.security.Sandbox;
import com.thoughtcoding.task.ExecutionCheckpoint.Invocation;
import com.thoughtcoding.task.ExecutionCheckpoint.InvocationStatus;
import com.thoughtcoding.task.ExecutionCheckpoint.Message;
import com.thoughtcoding.task.ExecutionCheckpoint.Phase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable task coordinator. Every execution owns an OS file lease and an explicit Run handle. */
public final class TaskService {
    private final TaskStore store;

    public TaskService(TaskStore store) { this.store = store; }
    public TaskStore store() { return store; }
    public TaskLeaseManager.Lease lockSession(String sessionId) {
        return store.lock("session-" + UUID.nameUUIDFromBytes(sessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** Register before scheduling. A stable invocation-derived ID makes dispatch crash-safe. */
    public String createChild(Run parent, String invocationId, String objective, String label,
                              boolean background, boolean isolated) {
        if (invocationId == null) throw new IllegalArgumentException("子任务缺少 invocationId");
        String id = UUID.nameUUIDFromBytes((parent.state().taskId() + ":" + invocationId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        try (var lease = store.lock(id)) {
            if (store.exists(id)) return id;
            String now = Instant.now().toString();
            TaskState state = new TaskState(id, parent.state().sessionId(), parent.state().taskId(), objective,
                    TaskStatus.PENDING, "QUEUED", List.of(), null, 1, now, now);
            Map<String, String> metadata = Map.of("parentInvocationId", invocationId, "label", label,
                    "background", Boolean.toString(background), "isolated", Boolean.toString(isolated),
                    "planMode", parent.checkpoint().metadata().getOrDefault("planMode", "false"));
            store.commit(new ExecutionCheckpoint(1, state, Sandbox.workspaceRoot().toString(), Map.of(),
                    Phase.BEFORE_MODEL, 0, 0, false, messages(List.of(new ChatMessage("user", objective))),
                    List.of(), List.of(), metadata, Set.of()), 0);
        }
        return id;
    }

    public ExecutionCheckpoint childFor(String parentId, String invocationId) {
        String id = UUID.nameUUIDFromBytes((parentId + ":" + invocationId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return store.exists(id) ? store.load(id) : null;
    }

    public List<ExecutionCheckpoint> pendingResults(String sessionId) {
        List<ExecutionCheckpoint> all = store.list(sessionId).stream().map(s -> store.load(s.taskId())).toList();
        Set<String> received = new HashSet<>();
        all.forEach(cp -> received.addAll(cp.receipts()));
        return all.stream().filter(cp -> cp.task().parentTaskId() != null
                        && ("true".equals(cp.metadata().get("background"))
                            || "true".equals(cp.metadata().get("deliverToInbox")))
                        && cp.metadata().containsKey("resultId")
                        && !received.contains(cp.metadata().get("resultId"))).toList();
    }

    public String create(String sessionId, String parentTaskId, String objective, List<ChatMessage> history) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        try (var ignored = store.lock(id)) {
            TaskState state = new TaskState(id, sessionId, parentTaskId, objective, TaskStatus.PENDING,
                    "CREATED", List.of(), null, 1, now, now);
            store.commit(new ExecutionCheckpoint(1, state, Sandbox.workspaceRoot().toString(), Map.of(),
                    Phase.BEFORE_MODEL, 0, 0, false, messages(history), List.of(), List.of(),
                    Map.of("planMode", Boolean.toString(com.thoughtcoding.security.PlanMode.isActive())), Set.of()), 0);
        }
        return id;
    }

    public Run open(String taskId, String sessionId, boolean resume) {
        var lease = store.lock(taskId);
        try {
            ExecutionCheckpoint cp = store.load(taskId);
            if (!sessionId.equals(cp.task().sessionId())) throw new IllegalArgumentException("任务不属于当前会话");
            if (cp.task().status().terminal()) throw new IllegalStateException("任务已经结束");
            if (!Sandbox.workspaceRoot().toString().equals(cp.workspace())) {
                throw new IllegalStateException("任务工作目录与当前工作目录不同");
            }
            if (resume) {
                if ("true".equals(cp.metadata().get("planMode")) && !com.thoughtcoding.security.PlanMode.isActive())
                    throw new IllegalStateException("该任务的检查点处于计划模式，请先进入 /plan 再恢复");
                for (var entry : cp.fileHashes().entrySet()) {
                    if (!entry.getValue().equals(hash(Path.of(entry.getKey())))) {
                        throw new IllegalStateException("恢复前发现文件已变化，请核验后使用 /task accept-workspace "
                                + taskId + ": " + entry.getKey());
                    }
                }
            }
            Run run = new Run(cp, lease);
            run.recoverUnfinished();
            if (run.hasUnknown()) {
                run.status(TaskStatus.WAITING_INPUT, "UNKNOWN_TOOL_RESULT");
                throw new IllegalStateException("工具结果不明；使用 /task show " + taskId
                        + " 查看，再用 /task resolve 确认结果，禁止自动重放");
            }
            run.start();
            return run;
        } catch (RuntimeException e) {
            lease.close();
            throw e;
        }
    }

    /** Only an acquired lease proves that a RUNNING task has lost its execution owner. */
    public void recoverInterrupted(String sessionId) {
        for (TaskState state : store.list(sessionId)) {
            if (state.status() != TaskStatus.RUNNING) continue;
            TaskLeaseManager.Lease lease;
            try { lease = store.lock(state.taskId()); }
            catch (IllegalStateException busy) { continue; }
            try (Run run = new Run(store.load(state.taskId()), lease)) {
                if (run.state().status() != TaskStatus.RUNNING) continue;
                run.recoverUnfinished();
                run.status(TaskStatus.INTERRUPTED, "PROCESS_INTERRUPTED");
            }
        }
    }

    public void finish(String taskId, String sessionId, TaskStatus status, String reason) {
        if (status != TaskStatus.SUCCEEDED && status != TaskStatus.CANCELLED)
            throw new IllegalArgumentException("只能显式完成或取消任务");
        try (var lease = store.lock(taskId); Run run = new Run(store.load(taskId), null)) {
            if (!sessionId.equals(run.state().sessionId())) throw new IllegalArgumentException("任务不属于当前会话");
            if (run.state().status().terminal()) throw new IllegalStateException("任务已经结束");
            if (status == TaskStatus.SUCCEEDED && (run.hasUnknown()
                    || run.checkpoint().invocations().stream().anyMatch(i -> !i.finished())
                    || run.state().steps().stream().anyMatch(s -> !s.status().equals("completed")))) {
                throw new IllegalStateException("仍有未完成步骤或未确认的工具结果，无法完成任务");
            }
            run.status(status, reason);
        }
    }

    /** Human reconciliation records an outcome; it never executes the uncertain operation again. */
    public void resolve(String taskId, String sessionId, String invocationId, boolean success, String evidence) {
        if (evidence == null || evidence.isBlank()) throw new IllegalArgumentException("必须填写核验结果");
        try (var lease = store.lock(taskId); Run run = new Run(store.load(taskId), null)) {
            if (!sessionId.equals(run.state().sessionId())) throw new IllegalArgumentException("任务不属于当前会话");
            if (run.state().status().terminal()) throw new IllegalStateException("任务已经结束");
            run.recoverUnfinished();
            Invocation invocation = run.invocation(invocationId);
            if (invocation.status() != InvocationStatus.UNKNOWN) throw new IllegalArgumentException("只能核验 UNKNOWN 调用");
            run.complete(invocationId, ToolExecutionPipeline.Outcome.executed(success
                    ? com.thoughtcoding.model.ToolResult.success("用户核验: " + evidence, 0)
                    : com.thoughtcoding.model.ToolResult.error("用户核验: " + evidence, 0)), false);
        }
    }

    public void acceptWorkspace(String taskId, String sessionId) {
        try (var lease = store.lock(taskId); Run run = new Run(store.load(taskId), null)) {
            if (!sessionId.equals(run.state().sessionId())) throw new IllegalArgumentException("任务不属于当前会话");
            if (run.state().status().terminal()) throw new IllegalStateException("任务已经结束");
            Map<String, String> hashes = new HashMap<>();
            run.cp.fileHashes().keySet().forEach(path -> hashes.put(path, hash(Path.of(path))));
            run.save(run.cp.phase(), run.cp.messages(), run.cp.invocations(), run.cp.batch(), hashes,
                    run.cp.iterations(), run.cp.stopContinuations(), run.cp.completionNudge(),
                    TaskStatus.PAUSED, "WORKSPACE_ACCEPTED", run.state().steps(), run.state().runId());
        }
    }

    private static List<Message> messages(List<ChatMessage> history) {
        return history.stream().map(Message::from).toList();
    }

    private static String hash(Path path) {
        try {
            if (!Files.exists(path)) return "MISSING";
            if (!Files.isRegularFile(path)) return "NOT_FILE";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("无法校验文件: " + path, e);
        }
    }

    public final class Run implements AutoCloseable {
        private ExecutionCheckpoint cp;
        private final TaskLeaseManager.Lease lease;
        private boolean closed;
        private Run(ExecutionCheckpoint cp, TaskLeaseManager.Lease lease) { this.cp = cp; this.lease = lease; }
        public synchronized ExecutionCheckpoint checkpoint() { return cp; }
        public synchronized TaskState state() { return cp.task(); }

        public synchronized void metadata(Map<String, String> updates) {
            Map<String, String> next = new HashMap<>(cp.metadata());
            next.putAll(updates);
            commitEnvelope(cp.workspace(), next, cp.receipts(), cp.messages());
        }

        public synchronized void relocate(String workspace, Map<String, String> updates) {
            if (!cp.invocations().isEmpty()) throw new IllegalStateException("执行后不能更换任务工作目录");
            Map<String, String> next = new HashMap<>(cp.metadata());
            next.putAll(updates);
            commitEnvelope(workspace, next, cp.receipts(), cp.messages());
        }

        /** Result and receipt messages commit together; a crash cannot consume without delivering. */
        public synchronized void receiveResults(List<ChatMessage> history) {
            Set<String> receipts = new HashSet<>(cp.receipts());
            List<ChatMessage> additions = new ArrayList<>();
            for (ExecutionCheckpoint child : pendingResults(state().sessionId())) {
                String id = child.metadata().get("resultId");
                if (receipts.add(id)) additions.add(new ChatMessage("user", "[后台子任务 "
                        + child.task().taskId() + " " + child.task().status() + "]\n"
                        + child.metadata().getOrDefault("result", "（无结论）")));
            }
            if (additions.isEmpty()) return;
            List<ChatMessage> combined = new ArrayList<>(history);
            combined.addAll(additions);
            commitEnvelope(cp.workspace(), cp.metadata(), receipts, messages(combined));
            history.addAll(additions);
        }

        private void commitEnvelope(String workspace, Map<String, String> metadata, Set<String> receipts,
                                    List<Message> history) {
            if (closed) throw new IllegalStateException("任务执行句柄已关闭");
            TaskState old = state();
            TaskState next = new TaskState(old.taskId(), old.sessionId(), old.parentTaskId(), old.objective(),
                    old.status(), old.stopReason(), old.steps(), old.runId(), old.version() + 1,
                    old.createdAt(), Instant.now().toString());
            ExecutionCheckpoint nextCp = new ExecutionCheckpoint(1, next, workspace, cp.fileHashes(), cp.phase(),
                    cp.iterations(), cp.stopContinuations(), cp.completionNudge(), history,
                    cp.invocations(), cp.batch(), metadata, receipts);
            store.commit(nextCp, old.version());
            cp = nextCp;
        }

        private synchronized void save(Phase phase, List<Message> history, List<Invocation> calls,
                                       List<String> batch, Map<String, String> hashes, int iterations,
                                       int continuations, boolean nudge, TaskStatus status, String reason,
                                       List<TaskStep> steps, String runId) {
            if (closed) throw new IllegalStateException("任务执行句柄已关闭");
            TaskState old = cp.task();
            TaskState next = new TaskState(old.taskId(), old.sessionId(), old.parentTaskId(), old.objective(),
                    status, reason, steps, runId, old.version() + 1, old.createdAt(), Instant.now().toString());
            ExecutionCheckpoint nextCp = new ExecutionCheckpoint(1, next, cp.workspace(), hashes, phase,
                    iterations, continuations, nudge, history, calls, batch, cp.metadata(), cp.receipts());
            store.commit(nextCp, old.version());
            cp = nextCp;
        }

        private void start() {
            if (cp.phase() != Phase.FINISHED && cp.metadata().containsKey("resultId")) {
                Map<String, String> next = new HashMap<>(cp.metadata());
                next.remove("resultId");
                next.remove("result");
                commitEnvelope(cp.workspace(), next, cp.receipts(), cp.messages());
            }
            save(cp.phase(), cp.messages(), cp.invocations(), cp.batch(), cp.fileHashes(), cp.iterations(),
                    "STOP_CONTINUATION_LIMIT".equals(state().stopReason()) ? 0 : cp.stopContinuations(),
                    cp.completionNudge(), TaskStatus.RUNNING, "",
                    state().steps(), UUID.randomUUID().toString());
        }

        public synchronized void status(TaskStatus status, String reason) {
            if (state().status().terminal() && state().status() != status)
                throw new IllegalStateException("不能修改已结束任务的状态");
            if (status == TaskStatus.PENDING) throw new IllegalStateException("执行中的任务不能退回待调度状态");
            save(cp.phase(), cp.messages(), cp.invocations(), cp.batch(), cp.fileHashes(), cp.iterations(),
                    cp.stopContinuations(), cp.completionNudge(), status, reason, state().steps(), state().runId());
        }

        public synchronized void boundary(Phase phase, List<ChatMessage> history, int iterations,
                                          int continuations, boolean nudge) {
            save(phase, messages(history), cp.invocations(), phase == Phase.TOOLS ? cp.batch() : List.of(),
                    cp.fileHashes(), iterations, continuations, nudge, state().status(), state().stopReason(),
                    state().steps(), state().runId());
        }

        public synchronized void prepare(List<ToolCall> batch, List<ChatMessage> history) {
            List<Invocation> calls = new ArrayList<>(cp.invocations());
            List<String> ids = new ArrayList<>();
            Map<String, String> hashes = new HashMap<>(cp.fileHashes());
            Set<String> providerIds = new HashSet<>();
            for (ToolCall call : batch) {
                if (call.getProviderCallId() == null || !providerIds.add(call.getProviderCallId())) {
                    throw new IllegalArgumentException("工具批次缺少唯一 provider call ID");
                }
                String id = UUID.randomUUID().toString();
                calls.add(new Invocation(id, call.getProviderCallId(), call.getToolName(), call.getParameters(),
                        InvocationStatus.PREPARED, false, null, null, 0, false));
                ids.add(id);
                watch(call, hashes);
            }
            save(Phase.TOOLS, messages(history), calls, ids, hashes, cp.iterations(), cp.stopContinuations(),
                    cp.completionNudge(), state().status(), "", state().steps(), state().runId());
        }

        private void watch(ToolCall call, Map<String, String> hashes) {
            if (!Set.of("read", "write", "edit").contains(call.getToolName()) || call.getParameters() == null) return;
            Object path = call.getParameters().get("path");
            if (path == null) path = call.getParameters().get("file_path");
            if (path != null) {
                Path resolved = Sandbox.resolve(path.toString());
                if (Sandbox.isWithinWorkspace(resolved)) hashes.put(resolved.toString(), hash(resolved));
            }
        }

        public synchronized Invocation invocation(String id) {
            return cp.invocations().stream().filter(i -> i.invocationId().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("工具调用不存在: " + id));
        }

        public synchronized void started(String id) { replace(invocation(id).withStatus(InvocationStatus.STARTED), cp.fileHashes()); }

        public synchronized void complete(String id, ToolExecutionPipeline.Outcome outcome, boolean uncertain) {
            Invocation old = invocation(id);
            Invocation next = uncertain ? old.withStatus(InvocationStatus.UNKNOWN) : old.withOutcome(outcome);
            Map<String, String> hashes = new HashMap<>(cp.fileHashes());
            if (!uncertain) {
                watch(old.toToolCall(), hashes);
                if ("bash".equals(old.toolName()) || "subAgent".equals(old.toolName()))
                    hashes.replaceAll((path, previous) -> hash(Path.of(path)));
            }
            replace(next, hashes);
        }

        private void replace(Invocation next, Map<String, String> hashes) {
            List<Invocation> calls = cp.invocations().stream()
                    .map(i -> i.invocationId().equals(next.invocationId()) ? next : i).toList();
            save(cp.phase(), cp.messages(), calls, cp.batch(), hashes, cp.iterations(), cp.stopContinuations(),
                    cp.completionNudge(), state().status(), state().stopReason(), state().steps(), state().runId());
        }

        private synchronized void recoverUnfinished() {
            for (Invocation i : List.copyOf(cp.invocations())) {
                if (i.status() == InvocationStatus.STARTED || i.status() == InvocationStatus.UNKNOWN) {
                    if ("subAgent".equals(i.toolName())) {
                        ExecutionCheckpoint child = childFor(state().taskId(), i.invocationId());
                        if (child != null && ("true".equals(child.metadata().get("background"))
                                || child.metadata().containsKey("resultId"))) {
                            String result = "true".equals(child.metadata().get("background"))
                                    ? "后台子任务已登记: " + child.task().taskId() + "，使用 /task show 查看或 /task resume 续跑。"
                                    : child.metadata().get("result");
                            replace(i.withOutcome(ToolExecutionPipeline.Outcome.executed(
                                    com.thoughtcoding.model.ToolResult.success(result, 0))), cp.fileHashes());
                            continue;
                        }
                    }
                    replace(i.withStatus(Set.of("read", "glob", "skill").contains(i.toolName())
                            ? InvocationStatus.PREPARED : InvocationStatus.UNKNOWN), cp.fileHashes());
                }
            }
        }

        public synchronized boolean hasUnknown() {
            return cp.invocations().stream().anyMatch(i -> i.status() == InvocationStatus.UNKNOWN);
        }

        public synchronized void updateSteps(List<TaskStep> steps) {
            if (steps.stream().filter(s -> s.status().equals("in_progress")).count() > 1
                    || steps.stream().map(TaskStep::stepId).distinct().count() != steps.size()) {
                throw new IllegalArgumentException("步骤 ID 必须唯一，同时最多一个步骤正在进行");
            }
            save(cp.phase(), cp.messages(), cp.invocations(), cp.batch(), cp.fileHashes(), cp.iterations(),
                    cp.stopContinuations(), cp.completionNudge(), state().status(), state().stopReason(), steps, state().runId());
        }

        public synchronized String modelSummary() {
            StringBuilder text = new StringBuilder("<task-state>\n任务 ID: ").append(state().taskId())
                    .append("\n目标: ").append(state().objective()).append("\n持久化步骤:\n");
            for (TaskStep s : state().steps()) text.append(s.stepId()).append(" [").append(s.status())
                    .append("] ").append(s.content()).append("\n");
            return text.append("请根据验收要求核验完成情况；需要补充信息时明确说明。\n</task-state>").toString();
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            if (lease != null) lease.close();
        }
    }
}
