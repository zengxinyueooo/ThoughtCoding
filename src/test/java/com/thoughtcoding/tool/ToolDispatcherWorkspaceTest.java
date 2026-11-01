package com.thoughtcoding.tool;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ToolCall;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.security.Sandbox;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolDispatcherWorkspaceTest {
    @TempDir Path directory;

    @Test void concurrentDispatchRetainsEachWorkspaceAndRestoresCaller() throws Exception {
        Path original = Sandbox.workspaceRoot();
        Path first = Files.createDirectory(directory.resolve("first")).toRealPath();
        Path second = Files.createDirectory(directory.resolve("second")).toRealPath();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ToolRegistry registry = new ToolRegistry(new AppConfig());
        registry.register(new BaseTool("workspace_probe", "test") {
            public ToolResult execute(String input) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    return ToolResult.success(Sandbox.workspaceRoot().toString(), 0);
                } catch (Exception e) { throw new RuntimeException(e); }
            }
            public JsonObjectSchema inputSchema() { return null; }
        });
        ToolDispatcher dispatcher = new ToolDispatcher(registry);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> a = pool.submit(() -> dispatchAt(dispatcher, first));
            Future<String> b = pool.submit(() -> dispatchAt(dispatcher, second));
            assertEquals(first.toString(), a.get(10, TimeUnit.SECONDS));
            assertEquals(second.toString(), b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(original, Sandbox.workspaceRoot());
    }

    private String dispatchAt(ToolDispatcher dispatcher, Path root) {
        Path previous = Sandbox.workspaceRoot();
        try (var ignored = Sandbox.enterWorkspace(root)) {
            ToolResult result = dispatcher.dispatch(new ToolCall("workspace_probe", Map.of(), null, false, 0, false, "id"));
            assertTrue(result.isSuccess(), result.getError());
            return result.getOutput();
        } finally {
            assertEquals(previous, Sandbox.workspaceRoot());
        }
    }
}
