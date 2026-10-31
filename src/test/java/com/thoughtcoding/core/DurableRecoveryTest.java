package com.thoughtcoding.core;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.*;
import com.thoughtcoding.security.Sandbox;
import com.thoughtcoding.service.*;
import com.thoughtcoding.task.*;
import com.thoughtcoding.tool.*;
import com.thoughtcoding.ui.ThoughtCodingUI;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DurableRecoveryTest {
    @TempDir Path directory;
    TaskService tasks;
    AIService ai;
    ThoughtCodingUI ui;
    ThoughtCodingContext context;
    ToolRegistry tools;
    Sandbox.WorkspaceScope scope;

    @BeforeEach void setup() {
        scope = Sandbox.enterWorkspace(directory);
        tasks = new TaskService(new TaskStore(directory.resolve("tasks")));
        ai = mock(AIService.class);
        ui = mock(ThoughtCodingUI.class);
        tools = new ToolRegistry(null);
        ContextManager manager = mock(ContextManager.class);
        when(manager.buildSubagentSystemPrompt()).thenReturn("test");
        context = new ThoughtCodingContext.Builder().appConfig(new AppConfig()).aiService(ai)
                .ui(ui).toolRegistry(tools).taskService(tasks).contextManager(manager)
                .sessionService(mock(SessionService.class)).performanceMonitor(new PerformanceMonitor())
                .subAgentExecutor(new SubAgentExecutor(1, ui)).build();
    }

    @AfterEach void cleanup() { context.getSubAgentExecutor().shutdown(); scope.close(); }

    private AtomicInteger readTool() {
        AtomicInteger calls = new AtomicInteger();
        tools.register(new BaseTool("read", "test") {
            public ToolResult execute(String input) { calls.incrementAndGet(); return ToolResult.success("read result", 0); }
        });
        return calls;
    }

    @Test void finishedChildPublishesAfterCrashWithoutAnotherModelRequest() {
        String parent = tasks.create("session", null, "parent", List.of());
        String child;
        try (var run = tasks.open(parent, "session", false)) {
            child = tasks.createChild(run, "inv", "objective", "child", true, false);
        }
        try (var run = tasks.open(child, "session", false)) {
            run.boundary(ExecutionCheckpoint.Phase.FINISHED,
                    List.of(new ChatMessage("user", "objective"), new ChatMessage("assistant", "saved answer")),
                    0, 0, false);
        }
        assertTrue(new DurableSubAgentRunner(context).run(child, "session", new CancelToken()).contains("saved answer"));
        assertEquals(TaskStatus.WAITING_INPUT, tasks.store().load(child).task().status());
        assertEquals(1, tasks.pendingResults("session").size());
        verifyNoInteractions(ai);
    }

    @Test void failedChildModelCanResumeAndDeliverNewForegroundResult() {
        String parent = tasks.create("session", null, "parent", List.of());
        String child;
        try (var run = tasks.open(parent, "session", false)) {
            child = tasks.createChild(run, "inv", "objective", "child", false, false);
        }
        when(ai.chatOnceForSubagent(anyString(), anyList(), any(), any()))
                .thenReturn(SubagentTurn.failure("model unavailable"))
                .thenReturn(new SubagentTurn("recovered", List.of()));
        new DurableSubAgentRunner(context).run(child, "session", new CancelToken());
        assertEquals(TaskStatus.FAILED, tasks.store().load(child).task().status());
        assertEquals(ExecutionCheckpoint.Phase.BEFORE_MODEL, tasks.store().load(child).phase());
        assertTrue(tasks.pendingResults("session").isEmpty());
        assertTrue(new DurableSubAgentRunner(context).run(child, "session", new CancelToken()).contains("recovered"));
        assertEquals(TaskStatus.WAITING_INPUT, tasks.store().load(child).task().status());
        assertEquals(1, tasks.pendingResults("session").size());
    }

    @Test void rejectedJournalCommitPreventsToolExecution() {
        AtomicInteger calls = readTool();
        String id = tasks.create("session", null, "objective", List.of());
        try (var run = tasks.open(id, "session", false)) {
            ToolCall call = new ToolCall("read", Map.of(), null, false, 0, false, "a");
            run.prepare(List.of(call), List.of());
            // Advance the durable generation behind this run, forcing optimistic version rejection.
            var cp = run.checkpoint();
            var s = cp.task();
            var newer = new TaskState(s.taskId(), s.sessionId(), s.parentTaskId(), s.objective(),
                    s.status(), s.stopReason(), s.steps(), s.runId(), s.version() + 1, s.createdAt(), s.updatedAt());
            tasks.store().commit(new ExecutionCheckpoint(cp.schemaVersion(), newer, cp.workspace(), cp.fileHashes(),
                    cp.phase(), cp.iterations(), cp.stopContinuations(), cp.completionNudge(), cp.messages(),
                    cp.invocations(), cp.batch(), cp.metadata(), cp.receipts()), s.version());
            var pipeline = new ToolExecutionPipeline(context, new com.thoughtcoding.hook.HookRegistry(), tools, run);
            assertThrows(TaskPersistenceException.class, () -> pipeline.execute(call, new CancelToken(), List.of()));
            assertEquals(0, calls.get());
        }
    }

    private List<ChatMessage> batchHistory() {
        return new ArrayList<>(List.of(new ChatMessage("user", "objective"),
                ChatMessage.assistantWithToolCalls("", List.of(new ToolCallRef("a", "read", "{}"),
                        new ToolCallRef("b", "read", "{\"offset\":1}")))));
    }

    private void interruptedBatch(String id) {
        try (var run = tasks.open(id, "session", false)) {
            run.prepare(List.of(new ToolCall("read", Map.of(), null, false, 0, false, "a"),
                    new ToolCall("read", Map.of("offset", 1), null, false, 0, false, "b")), batchHistory());
            String first = run.checkpoint().batch().getFirst();
            run.started(first);
            run.complete(first, ToolExecutionPipeline.Outcome.executed(ToolResult.success("already done", 0)), false);
            // Simulate a crash after the result commit but before appending its history message.
        }
    }

    @Test void mainResumesPartialBatchWithoutReexecutingCompletedCall() {
        AtomicInteger calls = readTool();
        String id = tasks.create("session", null, "objective", List.of());
        interruptedBatch(id);
        when(ai.streamingChat(isNull(), anyList(), anyString(), any(), nullable(String.class))).thenAnswer(invocation -> {
            List<ChatMessage> history = invocation.getArgument(1);
            assertEquals(1, history.stream().filter(m -> m.isToolMessage() && "a".equals(m.getToolCallId())).count());
            assertEquals(1, history.stream().filter(m -> m.isToolMessage() && "b".equals(m.getToolCallId())).count());
            history.add(new ChatMessage("assistant", "finished"));
            return history;
        });
        AgentLoop loop = new AgentLoop(context, "session", "test-model");
        loop.resumeTask(id, new CancelToken());
        assertEquals(1, calls.get());
        assertEquals(TaskStatus.WAITING_INPUT, tasks.store().load(id).task().status());
        assertEquals(1, loop.getHistory().stream().filter(ChatMessage::isUserMessage).count());
    }

    @Test void childResumesOwnHistoryAndPublishesPersistentResult() {
        AtomicInteger calls = readTool();
        String parent = tasks.create("session", null, "parent", List.of());
        String child;
        try (var run = tasks.open(parent, "session", false)) {
            child = tasks.createChild(run, "invocation", "objective", "child", true, false);
        }
        interruptedBatch(child);
        when(ai.chatOnceForSubagent(anyString(), anyList(), any(), any())).thenAnswer(invocation -> {
            List<ChatMessage> history = invocation.getArgument(1);
            assertEquals(2, history.stream().filter(ChatMessage::isToolMessage).count());
            assertTrue(history.stream().noneMatch(m -> "parent".equals(m.getContent())));
            return new SubagentTurn("child result", List.of());
        });
        String result = new DurableSubAgentRunner(context).run(child, "session", new CancelToken());
        assertTrue(result.contains("child result"));
        assertEquals(1, calls.get());
        TaskService restarted = new TaskService(new TaskStore(directory.resolve("tasks")));
        assertEquals(1, restarted.pendingResults("session").size());
        try (var run = restarted.open(parent, "session", true)) {
            List<ChatMessage> history = new ArrayList<>();
            run.receiveResults(history);
            assertEquals(1, history.size());
            run.receiveResults(history);
            assertEquals(1, history.size());
        }
        assertTrue(new TaskService(new TaskStore(directory.resolve("tasks"))).pendingResults("session").isEmpty());
        assertTrue(restarted.pendingResults("other-session").isEmpty());
    }

    @Test void childRegistrationIsIdempotentAndPrecedesScheduling() {
        String parent = tasks.create("session", null, "parent", List.of());
        try (var run = tasks.open(parent, "session", false)) {
            String first = tasks.createChild(run, "inv", "goal", "child", true, false);
            String second = tasks.createChild(run, "inv", "goal", "child", true, false);
            assertEquals(first, second);
            assertEquals(TaskStatus.PENDING, tasks.store().load(first).task().status());
        }
        assertEquals(2, tasks.store().list("session").size());
    }

    @Test void parentRecoversRegisteredBackgroundDispatchWithoutSpawningAgain() {
        String parent = tasks.create("session", null, "parent", List.of());
        String invocation;
        try (var run = tasks.open(parent, "session", false)) {
            run.prepare(List.of(new ToolCall("subAgent", Map.of("background", true), null, false, 0, false, "c")), List.of());
            invocation = run.checkpoint().batch().getFirst();
            run.started(invocation);
            tasks.createChild(run, invocation, "goal", "child", true, false);
        }
        try (var run = tasks.open(parent, "session", true)) {
            assertTrue(run.invocation(invocation).finished());
            assertTrue(run.invocation(invocation).outcome().result().getOutput().contains("后台子任务已登记"));
        }
    }

    @Test void failedModelDoesNotPersistPartialResponseOrDuplicatePromptOnResume() {
        when(ai.streamingChat(isNull(), anyList(), anyString(), any(), nullable(String.class))).thenAnswer(invocation -> {
            List<ChatMessage> history = invocation.getArgument(1);
            history.add(new ChatMessage("assistant", "partial"));
            throw new IllegalStateException("network failure");
        });
        AgentLoop loop = new AgentLoop(context, "session", "model");
        loop.processInput("objective");
        String id = loop.getActiveTaskId();
        assertEquals(TaskStatus.FAILED, tasks.store().load(id).task().status());
        assertTrue(loop.getHistory().stream().noneMatch(m -> "partial".equals(m.getContent())));
        doAnswer(invocation -> {
            List<ChatMessage> history = invocation.getArgument(1);
            history.add(new ChatMessage("assistant", "done"));
            return history;
        }).when(ai).streamingChat(isNull(), anyList(), anyString(), any(), nullable(String.class));
        loop.resumeTask(id, new CancelToken());
        assertEquals(1, loop.getHistory().stream().filter(m -> "objective".equals(m.getContent())).count());
        assertEquals(TaskStatus.WAITING_INPUT, tasks.store().load(id).task().status());
    }
}
