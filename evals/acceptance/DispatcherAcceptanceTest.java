package com.thoughtcoding.eval;
import com.thoughtcoding.tool.*;
import com.thoughtcoding.model.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DispatcherAcceptanceTest {
    private ToolCall call(String name) { return new ToolCall(name, Map.of(), null, false, 0, false, "test-call"); }
    @Test void thrownExceptionBecomesFailure() {
        ToolRegistry registry = new ToolRegistry(null);
        registry.register(new BaseTool("broken", "test") {
            public ToolResult execute(String input) { throw new IllegalStateException("test-failure"); }
        });
        ToolResult result = assertDoesNotThrow(() -> new ToolDispatcher(registry).dispatch(call("broken")));
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("test-failure"));
    }
    @Test void nullResponseBecomesFailure() {
        ToolRegistry registry = new ToolRegistry(null);
        registry.register(new BaseTool("empty", "test") {
            public ToolResult execute(String input) { return null; }
        });
        ToolResult result = new ToolDispatcher(registry).dispatch(call("empty"));
        assertNotNull(result);
        assertFalse(result.isSuccess());
    }
    @Test void successfulResultAndUnknownToolStillWork() {
        ToolRegistry registry = new ToolRegistry(null);
        registry.register(new BaseTool("ok", "test") {
            public ToolResult execute(String input) { return ToolResult.success("answer", 0); }
        });
        ToolDispatcher dispatcher = new ToolDispatcher(registry);
        assertEquals("answer", dispatcher.dispatch(call("ok")).getOutput());
        assertFalse(dispatcher.dispatch(call("unknown")).isSuccess());
    }
}
