package com.thoughtcoding.tool.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.execution.CommandExecutor;
import com.thoughtcoding.security.Sandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BashToolExecutorTest {
    @TempDir Path directory;

    @Test void passesCommandVerbatimAndKeepsScopedWorkspace() throws Exception {
        String command = "printf '%s' 'a b'; echo 中文";
        BashTool tool = new BashTool(new AppConfig(), (cmd, workspace, timeout, token) -> {
            assertEquals(command, cmd);
            assertEquals(directory.toRealPath(), workspace);
            assertEquals(5, timeout);
            return new CommandExecutor.Result(0, "中文", false);
        });
        try (var ignored = Sandbox.enterWorkspace(directory)) {
            var result = tool.execute(new ObjectMapper().writeValueAsString(Map.of("command", command, "timeout", 5)));
            assertTrue(result.isSuccess());
            assertEquals("中文", result.getOutput());
        }
    }

    @Test void backendFailureNeverTriggersLocalRetry() {
        BashTool tool = new BashTool(new AppConfig(), (cmd, cwd, timeout, token) -> {
            throw new IllegalStateException("sandbox unavailable");
        });
        var result = tool.execute("{\"command\":\"echo should-not-run\"}");
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("sandbox unavailable"));
    }

    @Test void exitCodeAndTruncationRemainVisible() {
        BashTool tool = new BashTool(new AppConfig(), (cmd, cwd, timeout, token) ->
                new CommandExecutor.Result(7, "failure", true));
        var result = tool.execute("{\"command\":\"ignored\"}");
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("退出码 7"));
        assertTrue(result.getError().contains("输出已截断"));
    }
}
