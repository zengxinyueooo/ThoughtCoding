package com.thoughtcoding.eval;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ToolResult;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BashTimeoutAcceptanceTest {
    private ToolResult run(String command, int timeout) throws Exception {
        Class<?> type;
        try { type = Class.forName("com.thoughtcoding.tools.BashTool"); }
        catch (ClassNotFoundException oldPackageAbsent) { type = Class.forName("com.thoughtcoding.tool.tools.BashTool"); }
        Object tool = type.getConstructor(AppConfig.class).newInstance(new AppConfig());
        return (ToolResult) type.getMethod("execute", String.class).invoke(tool,
            new ObjectMapper().writeValueAsString(Map.of("command", command, "timeout", timeout)));
    }
    @Test void silentCommandHonorsTimeout() throws Exception {
        String command = System.getProperty("os.name").toLowerCase().contains("win")
                ? "Start-Sleep -Seconds 8" : "sleep 8";
        long start = System.nanoTime();
        ToolResult result = run(command, 1);
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        assertFalse(result.isSuccess(), "silent long-running command must time out");
        assertTrue(seconds < 6, "timeout should return before the 8-second command ends: " + seconds);
    }
    @Test void normalOutputStillWorks() throws Exception {
        ToolResult result = run("echo eval-output", 10);
        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("eval-output"));
    }
}
