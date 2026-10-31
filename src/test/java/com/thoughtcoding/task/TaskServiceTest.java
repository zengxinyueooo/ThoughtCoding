package com.thoughtcoding.task;

import com.thoughtcoding.model.ChatMessage;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.security.Sandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TaskServiceTest {
    @TempDir Path directory;

    @Test void persistsStepsAndExcludesConcurrentExecution() {
        try (var scope = Sandbox.enterWorkspace(directory)) {
            var service = new TaskService(new TaskStore(directory.resolve("tasks")));
            String id = service.create("session", null, "objective", List.of());
            try (var run = service.open(id, "session", false)) {
                run.updateSteps(List.of(new TaskStep("step", "test", "pending", "")));
                assertThrows(IllegalStateException.class, () -> service.open(id, "session", true));
            }
            assertEquals("step", service.store().load(id).task().steps().getFirst().stepId());
            service.recoverInterrupted("session");
            assertEquals(TaskStatus.INTERRUPTED, service.store().load(id).task().status());
        }
    }

    @Test void uncertainWriteCannotBeReplayedWithoutReconciliation() {
        try (var scope = Sandbox.enterWorkspace(directory)) {
            var service = new TaskService(new TaskStore(directory.resolve("tasks")));
            String id = service.create("session", null, "objective", List.of());
            String invocation;
            try (var run = service.open(id, "session", false)) {
                run.prepare(List.of(new ToolCall("bash", Map.of("command", "test"), null, false, 0, false, "call")),
                        List.of(new ChatMessage("user", "objective")));
                invocation = run.checkpoint().batch().getFirst();
                run.started(invocation);
            }
            assertThrows(IllegalStateException.class, () -> service.open(id, "session", true));
            service.resolve(id, "session", invocation, true, "checked externally");
            try (var run = service.open(id, "session", true)) {
                assertTrue(run.invocation(invocation).finished());
                assertTrue(run.invocation(invocation).outcome().result().isSuccess());
            }
        }
    }

    @Test void workspaceDriftRequiresExplicitAcceptance() throws Exception {
        try (var scope = Sandbox.enterWorkspace(directory)) {
            var service = new TaskService(new TaskStore(directory.resolve("tasks")));
            java.nio.file.Files.writeString(directory.resolve("file.txt"), "before");
            String id = service.create("session", null, "objective", List.of());
            try (var run = service.open(id, "session", false)) {
                run.prepare(List.of(new ToolCall("read", Map.of("path", "file.txt"), null, false, 0, false, "call")), List.of());
            }
            java.nio.file.Files.writeString(directory.resolve("file.txt"), "changed");
            assertThrows(IllegalStateException.class, () -> service.open(id, "session", true));
            service.acceptWorkspace(id, "session");
            try (var run = service.open(id, "session", true)) { assertEquals(TaskStatus.RUNNING, run.state().status()); }
        }
    }

    @Test void orphanGenerationIsNeverUsedAsLatestCheckpoint() throws Exception {
        try (var scope = Sandbox.enterWorkspace(directory)) {
            Path root = directory.resolve("tasks");
            var service = new TaskService(new TaskStore(root));
            String id = service.create("session", null, "objective", List.of());
            java.nio.file.Files.writeString(root.resolve(id).resolve("999-" + java.util.UUID.randomUUID() + ".json"), "{partial");
            assertEquals(1, new TaskStore(root).load(id).task().version());
            assertEquals(List.of(1L), service.store().checkpoints(id));
        }
    }

    @Test void childResultReceiptSurvivesRestartBeforeSessionFileSave() {
        try (var scope = Sandbox.enterWorkspace(directory)) {
            Path root = directory.resolve("tasks");
            var service = new TaskService(new TaskStore(root));
            String parent = service.create("session", null, "objective", List.of());
            String child;
            try (var run = service.open(parent, "session", false)) {
                child = service.createChild(run, "inv", "child", "child", true, false);
            }
            try (var run = service.open(child, "session", false)) {
                run.metadata(Map.of("resultId", "delivery-1", "result", "durable output"));
                run.status(TaskStatus.WAITING_INPUT, "RESPONSE_COMPLETE");
            }
            try (var run = service.open(parent, "session", true)) {
                run.receiveResults(new java.util.ArrayList<>());
            }
            var restarted = new TaskService(new TaskStore(root));
            assertTrue(restarted.pendingResults("session").isEmpty());
            assertTrue(restarted.store().load(parent).messages().getFirst().content().contains("durable output"));
            assertTrue(restarted.store().load(parent).receipts().contains("delivery-1"));
        }
    }

    @Test void locksExcludeAnotherJvm() throws Exception {
        Path root = directory.resolve("tasks");
        TaskStore store = new TaskStore(root);
        try (var lease = store.lock("locked-task")) {
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), LockProbe.class.getName(), root.toString())
                    .redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0, process.exitValue());
                assertTrue(new String(process.getInputStream().readAllBytes()).contains("LOCKED"));
            } finally { process.destroyForcibly(); }
        }
    }

    public static class LockProbe {
        public static void main(String[] args) {
            try (var lease = new TaskStore(Path.of(args[0])).lock("locked-task")) { System.exit(2); }
            catch (IllegalStateException expected) { System.out.println("LOCKED"); }
        }
    }
}
