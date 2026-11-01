package com.thoughtcoding.eval;
import com.thoughtcoding.tool.*;
import com.thoughtcoding.model.ToolResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistryAcceptanceTest {
    private BaseTool tool(String name) {
        return new BaseTool(name, "test") {
            public ToolResult execute(String input) { return ToolResult.success("ok", 0); }
        };
    }
    @Test void nullRegistrationDoesNotThrow() {
        ToolRegistry registry = new ToolRegistry(null);
        assertDoesNotThrow(() -> registry.register(null));
    }
    @Test void blankNamesAreNotRegistered() {
        ToolRegistry registry = new ToolRegistry(null);
        registry.register(tool(""));
        registry.register(tool("   "));
        assertNull(registry.getTool(""));
        assertNull(registry.getTool("   "));
    }
    @Test void validToolCanStillBeRefreshed() {
        ToolRegistry registry = new ToolRegistry(null);
        BaseTool first = tool("valid"), second = tool("valid");
        registry.register(first);
        assertSame(first, registry.getTool("valid"));
        registry.register(second);
        assertSame(second, registry.getTool("valid"));
    }
}
