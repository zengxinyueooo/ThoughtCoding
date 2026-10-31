package com.thoughtcoding.core;

import com.thoughtcoding.security.Sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 为 SubAgent 创建独立 Git worktree，并管理任务产物的完整生命周期。
 *
 * <p>隔离边界是“默认工作目录和内置文件工具”，不是操作系统安全沙箱。主工作区必须干净，
 * 这样 worktree 从当前 HEAD 派生时不会漏掉未提交代码。任务状态持久化在
 * {@code ~/.thoughtcoding/worktrees/<repo-key>/tasks.json}，成功分支和失败 worktree
 * 可通过 {@code /agents list}、{@code /agents cleanup} 审查和清理。
 */
public final class WorktreeManager {

    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long GIT_TIMEOUT_SECONDS = 60;
    private static final long OUTPUT_DRAIN_TIMEOUT_SECONDS = 5;
    private static final int OUTPUT_LIMIT = 6000;
    private static final int GIT_LOCK_RETRIES = 3;
    private static final int STALE_WARNING_DAYS = 7;
    private static final Set<String> ACTIVE_TASKS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Path workspace;
    private final Path stateRoot;

    public WorktreeManager(Path workspace) {
        this(workspace, defaultStateRoot());
    }

    /** 允许测试或嵌入方指定状态目录；生产默认使用用户目录而不是易被清理的系统临时目录。 */
    public WorktreeManager(Path workspace, Path stateRoot) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.stateRoot = stateRoot.toAbsolutePath().normalize();
    }

    public static Path defaultStateRoot() {
        return Path.of(System.getProperty("user.home"), ".thoughtcoding", "worktrees")
                .toAbsolutePath().normalize();
    }

    public boolean isGitRepository() {
        return gitResult(workspace, "rev-parse", "--is-inside-work-tree").exitCode() == 0;
    }

    /** 在隔离 worktree 中执行任务，并返回任务结论与可供合并的 Git 产物信息。 */
    public RunResult run(String label, Callable<String> action) {
        return runManaged(label, record -> {
            try { return action.call(); }
            catch (Exception e) { throw new WorktreeException("子任务执行失败", e); }
        }, false);
    }

    /** Durable tasks retain their directory so a paused run can resume against the same files. */
    public RunResult runManaged(String label, java.util.function.Function<TaskRecord, String> action,
                                boolean retainWorkspace) {
        return runManaged(label, action, retainWorkspace, null);
    }

    public RunResult runDurable(String label, String durableTaskId, java.util.function.Function<TaskRecord, String> action) {
        if (!durableTaskId.matches("[a-zA-Z0-9-]{1,80}")) throw new WorktreeException("非法任务 ID");
        String id = "task-" + durableTaskId;
        Repository repository = inspectRepository();
        WorktreeRegistry registry = registry(repository);
        boolean existing = registry.withLock(() -> registry.load().stream().anyMatch(t -> t.id().equals(id)));
        if (existing) return resumeManaged(id, action);
        if (Files.exists(registry.taskDirectory(id)))
            throw new WorktreeException("发现未登记的任务 worktree，请先 /agents list 巡检: " + id);
        return runManaged(label, action, true, id);
    }

    private RunResult runManaged(String label, java.util.function.Function<TaskRecord, String> action,
                                 boolean retainWorkspace, String durableId) {
        Repository repository = inspectRepository();
        WorktreeRegistry registry = registry(repository);

        String id = durableId != null ? durableId : ID_TIME.format(LocalDateTime.now()) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String branch = "thoughtcoding/subagent/" + id;
        Path worktreeRoot = registry.taskDirectory(id);
        String now = Instant.now().toString();
        TaskRecord running = new TaskRecord(id, sanitizeLabel(label), repository.root().toString(),
                worktreeRoot.toString(), branch, repository.baseCommit(), null,
                TaskStatus.RUNNING, now, now, "");

        var executionLease = registry.executionLease(id);
        ACTIVE_TASKS.add(id);
        try {
            registry.withLock(() -> {
                ensureClean(repository.root());
                validateManagedPath(repository.root(), worktreeRoot);
                try {
                    gitWithRetry(repository.root(), "worktree", "add", "--quiet", "-b", branch,
                            worktreeRoot.toString(), repository.baseCommit());
                    List<TaskRecord> records = registry.load();
                    upsert(records, running);
                    registry.save(records);
                } catch (Exception e) {
                    cleanupFailedCreation(repository.root(), worktreeRoot, branch);
                    throw new WorktreeException("创建 SubAgent worktree 失败: " + e.getMessage(), e);
                }
                return null;
            });
        } catch (RuntimeException e) {
            ACTIVE_TASKS.remove(id);
            executionLease.close();
            throw e;
        }

        Path scopedWorkspace = worktreeRoot.resolve(repository.workspaceRelativePath()).normalize();
        if (!scopedWorkspace.startsWith(worktreeRoot) || !Files.isDirectory(scopedWorkspace)) {
            Outcome outcome;
            try {
                outcome = preserve(registry, running,
                        "worktree 中找不到原工作目录对应位置: " + scopedWorkspace);
            } finally {
                ACTIVE_TASKS.remove(id);
                executionLease.close();
            }
            throw new WorktreeException(outcome.summary());
        }

        String conclusion = null;
        Throwable failure = null;
        try (Sandbox.WorkspaceScope ignored = Sandbox.enterWorkspace(scopedWorkspace)) {
            conclusion = action.apply(running);
        } catch (Throwable t) {
            failure = t;
        }

        Outcome outcome;
        try {
            outcome = registry.withLock(() -> finish(repository, registry, running, retainWorkspace));
        } finally {
            ACTIVE_TASKS.remove(id);
            executionLease.close();
        }

        if (failure != null) {
            if (failure instanceof com.thoughtcoding.task.TaskPersistenceException persistence) throw persistence;
            if (failure instanceof Error error) throw error;
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new WorktreeException("SubAgent 在隔离 worktree 中执行失败: "
                    + safeMessage(failure) + "\n\n" + outcome.summary(), failure);
        }
        return new RunResult(conclusion, outcome);
    }

    public RunResult resumeManaged(String taskId, java.util.function.Function<TaskRecord, String> action) {
        Repository inspected = inspectRepository();
        WorktreeRegistry registry = registry(inspected);
        var executionLease = registry.executionLease(taskId);
        if (!ACTIVE_TASKS.add(taskId)) { executionLease.close(); throw new WorktreeException("worktree 任务仍在运行"); }
        try {
            TaskRecord running = registry.withLock(() -> {
                TaskRecord record = registry.load().stream().filter(t -> t.id().equals(taskId)).findFirst()
                        .orElseThrow(() -> new WorktreeException("找不到 worktree 产物: " + taskId));
                if (record.worktree() == null) throw new WorktreeException("worktree 已清理，无法原地恢复");
                Path path = Path.of(record.worktree());
                validateManagedPath(inspected.root(), path);
                if (!Files.isDirectory(path)) throw new WorktreeException("worktree 目录已丢失");
                if (!record.branch().equals(git(path, "symbolic-ref", "--short", "HEAD").trim()))
                    throw new WorktreeException("worktree 分支已变化，请先核验");
                TaskRecord updated = record.withStatus(TaskStatus.RUNNING, "恢复执行");
                saveRecord(registry, updated);
                return updated;
            });
            Repository repository = new Repository(inspected.root(), inspected.workspaceRelativePath(), running.baseCommit());
            Path scoped = Path.of(running.worktree()).resolve(repository.workspaceRelativePath()).normalize();
            String conclusion;
            try (var scope = Sandbox.enterWorkspace(scoped)) {
                conclusion = action.apply(running);
            } catch (RuntimeException | Error e) {
                registry.withLock(() -> finish(repository, registry, running, true));
                throw e;
            }
            Outcome outcome = registry.withLock(() -> finish(repository, registry, running, true));
            return new RunResult(conclusion, outcome);
        } finally {
            ACTIVE_TASKS.remove(taskId);
            executionLease.close();
        }
    }

    /** 启动巡检：prune Git 残留、导入旧版分支/worktree、标记异常中断和已合并任务。 */
    public AuditReport audit() {
        Repository repository = inspectRepository();
        WorktreeRegistry registry = registry(repository);
        return registry.withLock(() -> auditLocked(repository, registry));
    }

    /** 返回当前仓库的持久化 SubAgent 任务，读取前会做一次非破坏性巡检。 */
    public List<TaskRecord> listTasks() {
        Repository repository = inspectRepository();
        WorktreeRegistry registry = registry(repository);
        return registry.withLock(() -> {
            AuditState state = reconcileLocked(repository, registry);
            registry.save(state.records());
            return state.records().stream()
                    .sorted(Comparator.comparing(TaskRecord::createdAt).reversed())
                    .toList();
        });
    }

    /** 按安全规则清理任务；未合并成果只有显式 force 才会删除。 */
    public CleanupReport cleanup(CleanupOptions options) {
        CleanupOptions actual = options == null ? new CleanupOptions(null, null, false) : options;
        if (actual.force() && (actual.taskId() == null || actual.taskId().isBlank())) {
            throw new WorktreeException("--force 必须指定单个任务 id，拒绝批量强制删除");
        }
        Repository repository = inspectRepository();
        WorktreeRegistry registry = registry(repository);
        return registry.withLock(() -> cleanupLocked(repository, registry, actual));
    }

    private AuditReport auditLocked(Repository repository, WorktreeRegistry registry) {
        AuditState state = reconcileLocked(repository, registry);
        registry.save(state.records());
        long attention = state.records().stream()
                .filter(t -> t.status() == TaskStatus.PRESERVED || t.status() == TaskStatus.STALE)
                .count();
        long merged = state.records().stream().filter(t -> t.status() == TaskStatus.MERGED).count();
        long aged = state.records().stream()
                .filter(t -> t.status() == TaskStatus.SAVED && t.ageDays() >= STALE_WARNING_DAYS)
                .count();
        return new AuditReport(state.records().size(), (int) attention, (int) merged,
                state.imported(), (int) aged);
    }

    private AuditState reconcileLocked(Repository repository, WorktreeRegistry registry) {
        gitWithRetry(repository.root(), "worktree", "prune");
        List<TaskRecord> records = registry.load();
        int imported = discoverHistoricalTasks(repository, records);
        List<TaskRecord> reconciled = new ArrayList<>(records.size());
        for (TaskRecord record : records) reconciled.add(reconcile(repository, record));
        return new AuditState(reconciled, imported);
    }

    private CleanupReport cleanupLocked(Repository repository, WorktreeRegistry registry,
                                        CleanupOptions options) {
        AuditState state = reconcileLocked(repository, registry);
        List<TaskRecord> records = new ArrayList<>(state.records());
        List<String> messages = new ArrayList<>();
        List<TaskRecord> candidates = selectCandidates(records, options, messages);
        int cleaned = 0;
        int skipped = 0;

        for (TaskRecord record : candidates) {
            if (ACTIVE_TASKS.contains(record.id())) {
                messages.add("跳过 " + record.id() + "：任务仍在运行，请先 stop 或等待完成");
                skipped++;
                continue;
            }

            boolean safe = options.force() || record.status() == TaskStatus.MERGED
                    || hasNoArtifacts(repository, record)
                    || isCleanNoChangeWorktree(record);
            if (!safe) {
                messages.add("跳过 " + record.id() + "：存在未合并成果；使用 --force 才会删除");
                skipped++;
                continue;
            }

            try (var executionLease = registry.executionLease(record.id())) {
                deleteArtifacts(repository, record);
                records.removeIf(t -> t.id().equals(record.id()));
                messages.add("已清理 " + record.id() + "（" + record.status() + "）");
                cleaned++;
            } catch (Exception e) {
                messages.add("清理失败 " + record.id() + "：" + e.getMessage());
                skipped++;
            }
        }

        registry.save(records);
        return new CleanupReport(cleaned, skipped, messages);
    }

    private List<TaskRecord> selectCandidates(List<TaskRecord> records, CleanupOptions options,
                                              List<String> messages) {
        List<TaskRecord> candidates = new ArrayList<>();
        if (options.taskId() != null && !options.taskId().isBlank()) {
            String query = options.taskId().trim();
            List<TaskRecord> matches = records.stream()
                    .filter(t -> t.id().equals(query) || t.id().startsWith(query))
                    .toList();
            if (matches.isEmpty()) {
                messages.add("没有找到任务: " + query);
                return candidates;
            }
            if (matches.size() > 1) {
                messages.add("任务前缀不唯一: " + query);
                return candidates;
            }
            candidates.add(matches.get(0));
        } else {
            candidates.addAll(records);
        }

        if (options.olderThanDays() != null) {
            Instant cutoff = Instant.now().minusSeconds(Math.max(0, options.olderThanDays()) * 86_400L);
            candidates.removeIf(task -> parseInstant(task.createdAt()).isAfter(cutoff));
        }
        return candidates;
    }

    private boolean hasNoArtifacts(Repository repository, TaskRecord record) {
        boolean worktreeMissing = record.worktree() == null || !Files.exists(Path.of(record.worktree()));
        boolean branchMissing = record.branch() == null || !branchExists(repository.root(), record.branch());
        return worktreeMissing && branchMissing;
    }

    private boolean isCleanNoChangeWorktree(TaskRecord record) {
        if (record.worktree() == null) return false;
        Path path = Path.of(record.worktree());
        if (!Files.isDirectory(path)) return false;
        try {
            String status = git(path, "status", "--porcelain", "--untracked-files=all").trim();
            String head = git(path, "rev-parse", "HEAD").trim();
            return status.isBlank() && head.equals(record.baseCommit());
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteArtifacts(Repository repository, TaskRecord record) {
        if (record.worktree() != null) {
            Path path = Path.of(record.worktree()).toAbsolutePath().normalize();
            if (Files.exists(path)) removeWorktree(repository.root(), path);
        }
        if (record.branch() != null && branchExists(repository.root(), record.branch())) {
            gitWithRetry(repository.root(), "branch", "-D", record.branch());
        }
        gitWithRetry(repository.root(), "worktree", "prune");
    }

    private Outcome finish(Repository repository, WorktreeRegistry registry, TaskRecord running) {
        return finish(repository, registry, running, false);
    }

    private Outcome finish(Repository repository, WorktreeRegistry registry, TaskRecord running, boolean retain) {
        Path worktreeRoot = Path.of(running.worktree());
        try {
            String status = git(worktreeRoot, "status", "--porcelain", "--untracked-files=all").trim();
            if (!status.isBlank()) {
                git(worktreeRoot, "add", "-A");
                git(worktreeRoot,
                        "-c", "user.name=ThoughtCoding SubAgent",
                        "-c", "user.email=subagent@thoughtcoding.local",
                        "commit", "--quiet", "--no-verify", "--no-gpg-sign",
                        "-m", "SubAgent: " + running.label());
            }

            String head = git(worktreeRoot, "rev-parse", "HEAD").trim();
            if (retain) {
                String files = git(worktreeRoot, "diff", "--name-status", repository.baseCommit() + ".." + head).trim();
                String stat = git(worktreeRoot, "diff", "--stat", repository.baseCommit() + ".." + head).trim();
                String note = "为任务恢复保留工作目录；验收后可通过 /agents cleanup 清理";
                saveRecord(registry, running.update(worktreeRoot.toString(), head, TaskStatus.PRESERVED, note));
                return Outcome.savedButPreserved(running.id(), running.branch(), head, worktreeRoot, files, stat, note);
            }
            if (head.equals(repository.baseCommit())) {
                removeWorktree(repository.root(), worktreeRoot);
                if (branchExists(repository.root(), running.branch())) {
                    gitWithRetry(repository.root(), "branch", "-D", running.branch());
                }
                removeRecord(registry, running.id());
                return Outcome.noChanges(running.id());
            }

            String files = git(worktreeRoot, "diff", "--name-status",
                    repository.baseCommit() + ".." + head).trim();
            String stat = git(worktreeRoot, "diff", "--stat",
                    repository.baseCommit() + ".." + head).trim();
            try {
                removeWorktree(repository.root(), worktreeRoot);
                TaskRecord saved = running.update(null, head, TaskStatus.SAVED,
                        "改动已保存，等待审查或合并");
                saveRecord(registry, saved);
                return Outcome.saved(running.id(), running.branch(), head, files, stat);
            } catch (Exception cleanupError) {
                TaskRecord preserved = running.update(worktreeRoot.toString(), head,
                        TaskStatus.PRESERVED,
                        "快照已保存，但临时目录清理失败: " + cleanupError.getMessage());
                saveRecord(registry, preserved);
                return Outcome.savedButPreserved(running.id(), running.branch(), head,
                        worktreeRoot, files, stat, preserved.note());
            }
        } catch (Exception snapshotError) {
            TaskRecord preserved = running.update(worktreeRoot.toString(), null,
                    TaskStatus.PRESERVED,
                    "无法自动保存/清理，请手动检查: " + snapshotError.getMessage());
            saveRecord(registry, preserved);
            return Outcome.preserved(running.id(), running.branch(), worktreeRoot, preserved.note());
        }
    }

    private Outcome preserve(WorktreeRegistry registry, TaskRecord running, String note) {
        return registry.withLock(() -> {
            TaskRecord preserved = running.update(running.worktree(), null, TaskStatus.PRESERVED, note);
            saveRecord(registry, preserved);
            return Outcome.preserved(running.id(), running.branch(), Path.of(running.worktree()), note);
        });
    }

    private TaskRecord reconcile(Repository repository, TaskRecord record) {
        if (record.status() == TaskStatus.RUNNING && ACTIVE_TASKS.contains(record.id())) return record;
        if (record.status() == TaskStatus.RUNNING) {
            try (var lease = registry(repository).executionLease(record.id())) {
                // Acquiring the OS lease proves there is no owner in another process.
            } catch (IllegalStateException activeElsewhere) { return record; }
        }

        boolean worktreeExists = record.worktree() != null && Files.isDirectory(Path.of(record.worktree()));
        boolean branchExists = record.branch() != null && branchExists(repository.root(), record.branch());
        boolean merged = record.commit() != null && isAncestor(repository.root(), record.commit(), "HEAD");

        if (record.status() == TaskStatus.RUNNING) {
            return record.withStatus(TaskStatus.STALE,
                    worktreeExists ? "上次运行未正常结束，worktree 已保留"
                            : "上次运行未正常结束，且 worktree 已不存在");
        }
        if (record.status() == TaskStatus.SAVED || record.status() == TaskStatus.MERGED) {
            if (merged) return record.withStatus(TaskStatus.MERGED, "提交已包含在当前 HEAD 中，可安全清理");
            if (branchExists) return record.withStatus(TaskStatus.SAVED, "分支尚未合并");
            return record.withStatus(TaskStatus.STALE, "本地分支已不存在，提交未包含在当前 HEAD 中");
        }
        if (record.status() == TaskStatus.PRESERVED && !worktreeExists && !branchExists) {
            return record.withStatus(TaskStatus.STALE, "保留的 worktree 和分支均已不存在");
        }
        return record;
    }

    /** 导入上一版本没有 tasks.json 的 thoughtcoding/subagent/* 分支和旧 %TEMP% worktree。 */
    private int discoverHistoricalTasks(Repository repository, List<TaskRecord> records) {
        Set<String> knownBranches = new HashSet<>();
        Set<String> knownIds = new HashSet<>();
        for (TaskRecord record : records) {
            if (record.branch() != null) knownBranches.add(record.branch());
            knownIds.add(record.id());
        }

        Map<String, String> worktrees = registeredWorktrees(repository.root());
        String refs = git(repository.root(), "for-each-ref",
                "--format=%(refname:short)|%(objectname)|%(creatordate:unix)",
                "refs/heads/thoughtcoding/subagent");
        int imported = 0;
        for (String line : refs.lines().toList()) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\|", -1);
            if (parts.length < 2 || knownBranches.contains(parts[0])) continue;
            String branch = parts[0];
            String commit = parts[1];
            String rawId = branch.substring(branch.lastIndexOf('/') + 1);
            String id = uniqueId(rawId, knownIds);
            String worktree = worktrees.get(branch);
            String createdAt = parts.length >= 3 ? epochToInstant(parts[2]) : Instant.now().toString();
            String base = parentOf(repository.root(), commit);
            TaskStatus status = worktree != null && Files.isDirectory(Path.of(worktree))
                    ? TaskStatus.PRESERVED : TaskStatus.SAVED;
            records.add(new TaskRecord(id, "历史 SubAgent", repository.root().toString(), worktree,
                    branch, base, commit, status, createdAt, Instant.now().toString(),
                    "从旧版 Git 分支/worktree 注册表导入"));
            knownIds.add(id);
            imported++;
        }
        return imported;
    }

    private Map<String, String> registeredWorktrees(Path repositoryRoot) {
        Map<String, String> out = new HashMap<>();
        String listing = git(repositoryRoot, "worktree", "list", "--porcelain");
        String path = null;
        for (String line : listing.lines().toList()) {
            if (line.startsWith("worktree ")) {
                path = line.substring("worktree ".length()).trim();
            } else if (line.startsWith("branch refs/heads/") && path != null) {
                out.put(line.substring("branch refs/heads/".length()).trim(), path);
            } else if (line.isBlank()) {
                path = null;
            }
        }
        return out;
    }

    private Repository inspectRepository() {
        String rootText = git(workspace, "rev-parse", "--show-toplevel").trim();
        String baseCommit = git(workspace, "rev-parse", "HEAD").trim();
        if (rootText.isBlank() || baseCommit.isBlank()) {
            throw new WorktreeException("当前目录不是包含有效 HEAD 的 Git 仓库: " + workspace);
        }
        Path root = Path.of(rootText).toAbsolutePath().normalize();
        if (!workspace.startsWith(root)) {
            throw new WorktreeException("当前 workspace 不在 Git 仓库根目录内: " + workspace);
        }
        return new Repository(root, root.relativize(workspace), baseCommit);
    }

    private WorktreeRegistry registry(Repository repository) {
        return new WorktreeRegistry(repository.root(), stateRoot);
    }

    private void ensureClean(Path repositoryRoot) {
        String status = git(repositoryRoot, "status", "--porcelain", "--untracked-files=all").trim();
        if (!status.isBlank()) {
            throw new WorktreeException(
                    "无法安全创建 SubAgent worktree：主工作区存在未提交文件。"
                    + "请先提交、暂存到其他分支或清理这些改动；否则新 worktree 不会包含它们。\n"
                    + truncate(status));
        }
    }

    private void removeWorktree(Path repositoryRoot, Path worktreeRoot) {
        validateManagedPath(repositoryRoot, worktreeRoot);
        gitWithRetry(repositoryRoot, "worktree", "remove", "--force", worktreeRoot.toString());
        gitWithRetry(repositoryRoot, "worktree", "prune");
    }

    private void validateManagedPath(Path repositoryRoot, Path worktreeRoot) {
        Path target = worktreeRoot.toAbsolutePath().normalize();
        Path current = new WorktreeRegistry(repositoryRoot, stateRoot).repositoryStateRoot()
                .toAbsolutePath().normalize();
        Path legacy = legacyStorageRoot(repositoryRoot);
        boolean validCurrent = target.startsWith(current) && !target.equals(current);
        boolean validLegacy = target.startsWith(legacy) && !target.equals(legacy);
        if (!validCurrent && !validLegacy) {
            throw new WorktreeException("拒绝操作不安全的 worktree 路径: " + target);
        }
    }

    private Path legacyStorageRoot(Path repositoryRoot) {
        String repoKey = Integer.toUnsignedString(
                repositoryRoot.toAbsolutePath().normalize().toString().toLowerCase().hashCode(), 36);
        return Path.of(System.getProperty("java.io.tmpdir"), "thoughtcoding-worktrees", repoKey)
                .toAbsolutePath().normalize();
    }

    private void cleanupFailedCreation(Path repositoryRoot, Path worktreeRoot, String branch) {
        try {
            if (Files.exists(worktreeRoot)) removeWorktree(repositoryRoot, worktreeRoot);
        } catch (Exception ignored) {
        }
        try {
            if (branchExists(repositoryRoot, branch)) gitWithRetry(repositoryRoot, "branch", "-D", branch);
        } catch (Exception ignored) {
        }
    }

    private boolean branchExists(Path repositoryRoot, String branch) {
        return gitResult(repositoryRoot, "show-ref", "--verify", "--quiet",
                "refs/heads/" + branch).exitCode() == 0;
    }

    private boolean isAncestor(Path repositoryRoot, String ancestor, String descendant) {
        return gitResult(repositoryRoot, "merge-base", "--is-ancestor", ancestor, descendant).exitCode() == 0;
    }

    private String parentOf(Path repositoryRoot, String commit) {
        CommandResult result = gitResult(repositoryRoot, "rev-parse", commit + "^");
        return result.exitCode() == 0 ? result.output().trim() : commit;
    }

    private String git(Path directory, String... args) {
        CommandResult result = gitResult(directory, args);
        if (result.exitCode() != 0) throw commandFailure(directory, result, args);
        return result.output();
    }

    private String gitWithRetry(Path directory, String... args) {
        WorktreeException last = null;
        for (int attempt = 1; attempt <= GIT_LOCK_RETRIES; attempt++) {
            CommandResult result = gitResult(directory, args);
            if (result.exitCode() == 0) return result.output();
            last = commandFailure(directory, result, args);
            if (attempt == GIT_LOCK_RETRIES || !isLockContention(result.output())) throw last;
            try {
                Thread.sleep(100L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WorktreeException("等待 Git 锁时被中断", e);
            }
        }
        throw last == null ? new WorktreeException("Git 命令失败") : last;
    }

    private CommandResult gitResult(Path directory, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(Arrays.asList(args));

        Process process = null;
        CompletableFuture<String> outputFuture = new CompletableFuture<>();
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process running = process;
            Thread.startVirtualThread(() -> {
                try {
                    outputFuture.complete(new String(
                            running.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
                } catch (IOException e) {
                    outputFuture.completeExceptionally(e);
                }
            });

            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                terminate(process, outputFuture);
                throw new WorktreeException("Git 命令超时: " + String.join(" ", command));
            }

            String output;
            try {
                output = outputFuture.get(OUTPUT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                terminate(process, outputFuture);
                throw new WorktreeException("Git 输出流读取超时: " + String.join(" ", command), e);
            } catch (ExecutionException e) {
                throw new WorktreeException("读取 Git 输出失败: " + safeMessage(e.getCause()), e.getCause());
            }
            return new CommandResult(process.exitValue(), output);
        } catch (IOException e) {
            throw new WorktreeException("无法启动 Git，请确认 git 已安装并在 PATH 中: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            if (process != null) terminate(process, outputFuture);
            Thread.currentThread().interrupt();
            throw new WorktreeException("Git 命令被中断", e);
        }
    }

    private void terminate(Process process, CompletableFuture<String> outputFuture) {
        try {
            process.destroyForcibly();
            process.getInputStream().close();
        } catch (Exception ignored) {
        }
        outputFuture.cancel(true);
    }

    private WorktreeException commandFailure(Path directory, CommandResult result, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(Arrays.asList(args));
        return new WorktreeException("Git 命令失败(" + result.exitCode() + "): "
                + String.join(" ", command)
                + (result.output().isBlank() ? "" : "\n" + truncate(result.output())));
    }

    private boolean isLockContention(String output) {
        String lower = output == null ? "" : output.toLowerCase();
        return lower.contains("could not lock") || lower.contains("unable to create")
                || lower.contains("another git process") || lower.contains("already locked")
                || lower.contains("index.lock");
    }

    private void saveRecord(WorktreeRegistry registry, TaskRecord record) {
        List<TaskRecord> records = registry.load();
        upsert(records, record);
        registry.save(records);
    }

    private void removeRecord(WorktreeRegistry registry, String id) {
        List<TaskRecord> records = registry.load();
        records.removeIf(record -> record.id().equals(id));
        registry.save(records);
    }

    private void upsert(List<TaskRecord> records, TaskRecord record) {
        records.removeIf(existing -> existing.id().equals(record.id()));
        records.add(record);
    }

    private static String uniqueId(String preferred, Set<String> known) {
        if (!known.contains(preferred)) return preferred;
        int suffix = 2;
        while (known.contains(preferred + "-" + suffix)) suffix++;
        return preferred + "-" + suffix;
    }

    private static String epochToInstant(String epoch) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(epoch.trim())).toString();
        } catch (Exception e) {
            return Instant.now().toString();
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            return Instant.EPOCH;
        }
    }

    private static String sanitizeLabel(String label) {
        if (label == null || label.isBlank()) return "task";
        String oneLine = label.replaceAll("[\\r\\n]+", " ").trim();
        return oneLine.length() <= 100 ? oneLine : oneLine.substring(0, 100);
    }

    private static String safeMessage(Throwable t) {
        return t == null || t.getMessage() == null || t.getMessage().isBlank()
                ? (t == null ? "未知错误" : t.getClass().getSimpleName()) : t.getMessage();
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= OUTPUT_LIMIT) return text;
        return text.substring(0, OUTPUT_LIMIT) + "\n...(输出已截断)";
    }

    private record Repository(Path root, Path workspaceRelativePath, String baseCommit) {}
    private record CommandResult(int exitCode, String output) {}
    private record AuditState(List<TaskRecord> records, int imported) {}

    public enum TaskStatus { RUNNING, SAVED, PRESERVED, MERGED, STALE }

    public record TaskRecord(String id, String label, String repository, String worktree,
                             String branch, String baseCommit, String commit, TaskStatus status,
                             String createdAt, String updatedAt, String note) {
        TaskRecord update(String newWorktree, String newCommit, TaskStatus newStatus, String newNote) {
            return new TaskRecord(id, label, repository, newWorktree, branch, baseCommit,
                    newCommit, newStatus, createdAt, Instant.now().toString(), newNote);
        }

        TaskRecord withStatus(TaskStatus newStatus, String newNote) {
            if (status == newStatus && java.util.Objects.equals(note, newNote)) return this;
            return update(worktree, commit, newStatus, newNote);
        }

        public long ageDays() {
            return Math.max(0, java.time.Duration.between(parseInstant(createdAt), Instant.now()).toDays());
        }

        public String createdLocal() {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .format(parseInstant(createdAt).atZone(ZoneId.systemDefault()));
        }
    }

    public record CleanupOptions(String taskId, Integer olderThanDays, boolean force) {}
    public record CleanupReport(int cleaned, int skipped, List<String> messages) {}

    public record AuditReport(int total, int attention, int merged, int imported, int aged) {
        public boolean needsAttention() {
            return attention > 0 || merged > 0 || imported > 0 || aged > 0;
        }
    }

    public record RunResult(String conclusion, Outcome outcome) {
        public String combinedOutput() {
            String text = conclusion == null || conclusion.isBlank()
                    ? "子Agent已结束，但未产出文本结论。" : conclusion;
            return text + "\n\n" + outcome.summary();
        }
    }

    public record Outcome(String taskId, Status status, String branch, String commit, Path worktree,
                          String files, String stat, String note) {
        public enum Status { NO_CHANGES, SAVED, PRESERVED }

        static Outcome noChanges(String id) {
            return new Outcome(id, Status.NO_CHANGES, null, null, null, null, null, null);
        }

        static Outcome saved(String id, String branch, String commit, String files, String stat) {
            return new Outcome(id, Status.SAVED, branch, commit, null, files, stat, null);
        }

        static Outcome savedButPreserved(String id, String branch, String commit, Path worktree,
                                         String files, String stat, String note) {
            return new Outcome(id, Status.PRESERVED, branch, commit, worktree, files, stat, note);
        }

        static Outcome preserved(String id, String branch, Path worktree, String note) {
            return new Outcome(id, Status.PRESERVED, branch, null, worktree, null, null, note);
        }

        public String summary() {
            return switch (status) {
                case NO_CHANGES -> "[Git Worktree 隔离]\n任务: " + taskId
                        + "\n未产生文件改动；临时 worktree 和分支已清理。";
                case SAVED -> "[Git Worktree 隔离]\n"
                        + "任务: " + taskId + "\n"
                        + "改动已保存到独立本地分支，主工作区未修改。\n"
                        + "分支: " + branch + "\n"
                        + "提交: " + commit + "\n"
                        + (files == null || files.isBlank() ? "" : "文件:\n" + truncate(files) + "\n")
                        + (stat == null || stat.isBlank() ? "" : "统计:\n" + truncate(stat) + "\n")
                        + "确认后可在主工作区执行: git merge --no-ff " + branch + "\n"
                        + "合并后可执行: /agents cleanup " + taskId;
                case PRESERVED -> "[Git Worktree 隔离]\n"
                        + "任务: " + taskId + "\n"
                        + "为避免丢失内容，临时 worktree 已保留，请手动检查。\n"
                        + (branch == null ? "" : "分支: " + branch + "\n")
                        + (commit == null ? "" : "提交: " + commit + "\n")
                        + (worktree == null ? "" : "目录: " + worktree + "\n")
                        + (files == null || files.isBlank() ? "" : "文件:\n" + truncate(files) + "\n")
                        + (note == null ? "" : "说明: " + note);
            };
        }
    }

    public static class WorktreeException extends RuntimeException {
        public WorktreeException(String message) {
            super(message);
        }

        public WorktreeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
