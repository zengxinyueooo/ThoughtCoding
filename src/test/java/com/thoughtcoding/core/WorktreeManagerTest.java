package com.thoughtcoding.core;

import com.thoughtcoding.security.Sandbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorktreeManagerTest {

    @TempDir
    Path tempDir;

    private Path repository;
    private Path stateRoot;

    @BeforeEach
    void setUp() throws Exception {
        repository = tempDir.resolve("repo");
        stateRoot = tempDir.resolve("state");
        Files.createDirectories(repository);
        git(repository, "init", "--quiet");
        Files.writeString(repository.resolve("sample.txt"), "main\n", StandardCharsets.UTF_8);
        git(repository, "add", "sample.txt");
        git(repository,
                "-c", "user.name=Test",
                "-c", "user.email=test@example.com",
                "commit", "--quiet", "--no-gpg-sign", "-m", "initial");
        Sandbox.init(repository.toString());
    }

    @Test
    void shouldSaveChangesOnIsolatedBranchWithoutModifyingMainWorkspace() {
        WorktreeManager manager = manager();

        WorktreeManager.RunResult result = manager.run("修改示例", () -> {
            assertNotEquals(repository.toRealPath(), Sandbox.workspaceRoot().toRealPath());
            Files.writeString(Sandbox.resolve("sample.txt"), "subagent\n", StandardCharsets.UTF_8);
            Files.writeString(Sandbox.resolve("new-file.txt"), "new\n", StandardCharsets.UTF_8);
            return "任务完成";
        });

        assertEquals(WorktreeManager.Outcome.Status.SAVED, result.outcome().status());
        assertEquals("main\n", read(repository.resolve("sample.txt")), "主工作区不得被子代理直接修改");
        assertEquals("subagent\n", git(repository, "show",
                result.outcome().branch() + ":sample.txt").replace("\r\n", "\n"));
        assertEquals("new\n", git(repository, "show",
                result.outcome().branch() + ":new-file.txt").replace("\r\n", "\n"));
        assertTrue(result.combinedOutput().contains("git merge --no-ff"));
        assertTrue(git(repository, "status", "--porcelain").isBlank());
        assertEquals(1, manager.listTasks().size());
        assertTrue(manager.listTasks().get(0).worktree() == null,
                "成功快照后只保留分支，不应残留 worktree 目录");
    }

    @Test
    void durableWorktreeCanResumeAtSamePathAndKeepsArtifactIdentity() {
        WorktreeManager manager = manager();
        var first = manager.runManaged("durable", record -> {
            try { Files.writeString(Sandbox.resolve("sample.txt"), "first\n"); }
            catch (IOException e) { throw new IllegalStateException(e); }
            return "paused";
        }, true);
        Path retained = first.outcome().worktree();
        assertTrue(Files.isDirectory(retained));
        var second = manager.resumeManaged(first.outcome().taskId(), record -> {
            assertEquals(retained.toAbsolutePath().normalize(), Sandbox.workspaceRoot().toAbsolutePath().normalize());
            assertEquals("first\n", read(Sandbox.resolve("sample.txt")).replace("\r\n", "\n"));
            try { Files.writeString(Sandbox.resolve("sample.txt"), "second\n"); }
            catch (IOException e) { throw new IllegalStateException(e); }
            return "done";
        });
        assertEquals(first.outcome().taskId(), second.outcome().taskId());
        assertEquals(first.outcome().branch(), second.outcome().branch());
        assertNotEquals(first.outcome().commit(), second.outcome().commit());
        assertEquals("main\n", read(repository.resolve("sample.txt")).replace("\r\n", "\n"));
    }

    @Test
    void shouldRemoveTemporaryBranchWhenTaskProducesNoChanges() {
        WorktreeManager manager = manager();

        WorktreeManager.RunResult result = manager.run("只读检查", () -> {
            assertEquals("main\n", read(Sandbox.resolve("sample.txt")).replace("\r\n", "\n"));
            return "没有改动";
        });

        assertEquals(WorktreeManager.Outcome.Status.NO_CHANGES, result.outcome().status());
        assertFalse(git(repository, "branch", "--list", "thoughtcoding/subagent/*")
                .contains("thoughtcoding/subagent/"));
        assertEquals(1, git(repository, "worktree", "list", "--porcelain")
                .lines().filter(line -> line.startsWith("worktree ")).count());
    }

    @Test
    void shouldRejectExecutionWhenMainWorkspaceHasUncommittedChanges() throws Exception {
        Files.writeString(repository.resolve("sample.txt"), "uncommitted\n", StandardCharsets.UTF_8);
        WorktreeManager manager = manager();

        WorktreeManager.WorktreeException error = assertThrows(
                WorktreeManager.WorktreeException.class,
                () -> manager.run("危险任务", () -> "不应执行"));

        assertTrue(error.getMessage().contains("主工作区存在未提交文件"));
        assertFalse(git(repository, "branch", "--list", "thoughtcoding/subagent/*")
                .contains("thoughtcoding/subagent/"));
    }

    @Test
    void shouldPreserveBranchWhenSubAgentCreatesItsOwnCommit() throws Exception {
        WorktreeManager manager = manager();

        WorktreeManager.RunResult result = manager.run("自行提交", () -> {
            Files.writeString(Sandbox.resolve("sample.txt"), "committed-by-agent\n", StandardCharsets.UTF_8);
            git(Sandbox.workspaceRoot(), "add", "sample.txt");
            git(Sandbox.workspaceRoot(),
                    "-c", "user.name=Agent",
                    "-c", "user.email=agent@example.com",
                    "commit", "--quiet", "--no-gpg-sign", "-m", "agent commit");
            return "已提交";
        });

        assertEquals(WorktreeManager.Outcome.Status.SAVED, result.outcome().status());
        assertEquals("committed-by-agent\n", git(repository, "show",
                result.outcome().branch() + ":sample.txt").replace("\r\n", "\n"));
    }

    @Test
    void shouldIsolateWorkspaceAndBranchAcrossParallelSubAgents() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<WorktreeManager.RunResult>> tasks = List.of(
                    () -> runParallelChange("agent-a.txt", "A\n"),
                    () -> runParallelChange("agent-b.txt", "B\n"));
            List<Future<WorktreeManager.RunResult>> futures = executor.invokeAll(tasks);

            WorktreeManager.RunResult first = futures.get(0).get(30, TimeUnit.SECONDS);
            WorktreeManager.RunResult second = futures.get(1).get(30, TimeUnit.SECONDS);

            assertEquals(WorktreeManager.Outcome.Status.SAVED, first.outcome().status());
            assertEquals(WorktreeManager.Outcome.Status.SAVED, second.outcome().status());
            assertNotEquals(first.outcome().branch(), second.outcome().branch());
            assertEquals("A\n", git(repository, "show",
                    first.outcome().branch() + ":agent-a.txt").replace("\r\n", "\n"));
            assertEquals("B\n", git(repository, "show",
                    second.outcome().branch() + ":agent-b.txt").replace("\r\n", "\n"));
            assertFalse(Files.exists(repository.resolve("agent-a.txt")));
            assertFalse(Files.exists(repository.resolve("agent-b.txt")));
        }
    }

    @Test
    void shouldPreserveUnmergedTaskUnlessForceCleanupIsRequested() {
        WorktreeManager manager = manager();
        WorktreeManager.RunResult result = manager.run("待审查", () -> {
            Files.writeString(Sandbox.resolve("review.txt"), "review\n", StandardCharsets.UTF_8);
            return "完成";
        });

        WorktreeManager.CleanupReport safe = manager.cleanup(
                new WorktreeManager.CleanupOptions(result.outcome().taskId(), null, false));
        assertEquals(0, safe.cleaned());
        assertEquals(1, safe.skipped());
        assertFalse(git(repository, "branch", "--list", result.outcome().branch()).isBlank());

        WorktreeManager.CleanupReport forced = manager.cleanup(
                new WorktreeManager.CleanupOptions(result.outcome().taskId(), null, true));
        assertEquals(1, forced.cleaned());
        assertTrue(git(repository, "branch", "--list", result.outcome().branch()).isBlank());
        assertTrue(manager.listTasks().isEmpty());
    }

    @Test
    void shouldRejectForceCleanupWithoutSpecificTask() {
        WorktreeManager.WorktreeException error = assertThrows(
                WorktreeManager.WorktreeException.class,
                () -> manager().cleanup(new WorktreeManager.CleanupOptions(null, null, true)));
        assertTrue(error.getMessage().contains("拒绝批量强制删除"));
    }

    @Test
    void shouldCleanUpTaskAfterItsBranchIsMerged() {
        WorktreeManager manager = manager();
        WorktreeManager.RunResult result = manager.run("可合并", () -> {
            Files.writeString(Sandbox.resolve("merged.txt"), "merged\n", StandardCharsets.UTF_8);
            return "完成";
        });

        git(repository, "merge", "--ff-only", result.outcome().branch());
        List<WorktreeManager.TaskRecord> tasks = manager.listTasks();
        assertEquals(WorktreeManager.TaskStatus.MERGED, tasks.get(0).status());

        WorktreeManager.CleanupReport report = manager.cleanup(
                new WorktreeManager.CleanupOptions(null, null, false));
        assertEquals(1, report.cleaned());
        assertTrue(git(repository, "branch", "--list", result.outcome().branch()).isBlank());
        assertTrue(manager.listTasks().isEmpty());
    }

    @Test
    void shouldImportUntrackedLegacySubAgentBranchesDuringStartupAudit() {
        String branch = "thoughtcoding/subagent/legacy-task";
        git(repository, "branch", branch, "HEAD");

        WorktreeManager.AuditReport audit = manager().audit();

        assertEquals(1, audit.imported());
        List<WorktreeManager.TaskRecord> tasks = manager().listTasks();
        assertEquals(1, tasks.size());
        assertEquals("legacy-task", tasks.get(0).id());
        assertEquals(WorktreeManager.TaskStatus.MERGED, tasks.get(0).status());
    }

    @Test
    void shouldMarkInterruptedRunningTaskAsStaleDuringStartupAudit() throws Exception {
        WorktreeManager manager = manager();
        manager.run("模拟崩溃", () -> {
            Files.writeString(Sandbox.resolve("crash.txt"), "crash\n", StandardCharsets.UTF_8);
            return "完成";
        });
        Path index;
        try (var files = Files.walk(stateRoot)) {
            index = files.filter(path -> path.getFileName().toString().equals("tasks.json"))
                    .findFirst().orElseThrow();
        }
        String json = Files.readString(index, StandardCharsets.UTF_8)
                .replace("\"status\" : \"SAVED\"", "\"status\" : \"RUNNING\"");
        Files.writeString(index, json, StandardCharsets.UTF_8);

        WorktreeManager.AuditReport report = manager.audit();

        assertEquals(1, report.attention());
        assertEquals(WorktreeManager.TaskStatus.STALE, manager.listTasks().get(0).status());
    }

    @Test
    void shouldReportUnmergedBranchesOlderThanSevenDaysDuringStartupAudit() throws Exception {
        WorktreeManager manager = manager();
        manager.run("长期未合并", () -> {
            Files.writeString(Sandbox.resolve("aged.txt"), "aged\n", StandardCharsets.UTF_8);
            return "完成";
        });
        Path index;
        try (var files = Files.walk(stateRoot)) {
            index = files.filter(path -> path.getFileName().toString().equals("tasks.json"))
                    .findFirst().orElseThrow();
        }
        String json = Files.readString(index, StandardCharsets.UTF_8)
                .replaceFirst("\\\"createdAt\\\"\\s*:\\s*\\\"[^\\\"]+\\\"",
                        "\\\"createdAt\\\" : \\\"2000-01-01T00:00:00Z\\\"");
        Files.writeString(index, json, StandardCharsets.UTF_8);

        WorktreeManager.AuditReport report = manager.audit();

        assertEquals(1, report.aged());
        assertTrue(report.needsAttention());
    }

    @Test
    void durableRegistrationReusesArtifactAndRejectsConcurrentResume() {
        WorktreeManager manager = manager();
        var first = manager.runDurable("durable", "stable-child", record -> {
            assertThrows(IllegalStateException.class,
                    () -> manager.resumeManaged(record.id(), ignored -> "duplicate"));
            return "first";
        });
        var resumed = manager.runDurable("durable", "stable-child", record -> "second");
        assertEquals(first.outcome().taskId(), resumed.outcome().taskId());
        assertEquals(1, manager.listTasks().size());
    }

    private WorktreeManager.RunResult runParallelChange(String name, String content) {
        return manager().run(name, () -> {
            Path activeWorkspace = Sandbox.workspaceRoot();
            assertNotEquals(repository.toRealPath(), activeWorkspace.toRealPath());
            Files.writeString(activeWorkspace.resolve(name), content, StandardCharsets.UTF_8);
            return name;
        });
    }

    private WorktreeManager manager() {
        return new WorktreeManager(repository, stateRoot);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String git(Path directory, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(Arrays.asList(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Git 命令超时: " + command);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), "Git 命令失败: " + command + "\n" + output);
            return output;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
