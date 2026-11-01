package com.thoughtcoding.eval;
import com.thoughtcoding.tool.*;
import com.thoughtcoding.model.ToolResult;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistryOwnerAcceptanceTest {
    BaseTool tool(String name) {
        return new BaseTool(name,"fixture") { public ToolResult execute(String input) { return ToolResult.success("ok",0); } };
    }
    Object invoke(ToolRegistry registry, String name, Class<?>[] types, Object... args) throws Exception {
        Method method;
        try { method=ToolRegistry.class.getMethod(name,types); }
        catch(NoSuchMethodException e) { fail("Required owner lifecycle API missing: "+name); return null; }
        return method.invoke(registry,args);
    }
    boolean register(ToolRegistry registry,String owner,BaseTool tool) throws Exception {
        return (Boolean)invoke(registry,"register",new Class[]{String.class,BaseTool.class},owner,tool);
    }
    @Test void ownerCollisionCannotReplaceApplicationTool() throws Exception {
        var registry=new ToolRegistry(null); var first=tool("shared"); registry.register(first);
        assertFalse(register(registry,"mcp:a",tool("shared"))); assertSame(first,registry.getTool("shared"));
        assertEquals("application",invoke(registry,"ownerOf",new Class[]{String.class},"shared"));
    }
    @Test void sameOwnerCanRefreshButOtherOwnerCannot() throws Exception {
        var registry=new ToolRegistry(null); var next=tool("fetch");
        assertTrue(register(registry,"mcp:a",tool("fetch")));
        assertTrue(register(registry,"mcp:a",next));
        assertFalse(register(registry,"mcp:b",tool("fetch"))); assertSame(next,registry.getTool("fetch"));
    }
    @Test void ownerAndPrefixRemovalAreSelective() throws Exception {
        var registry=new ToolRegistry(null); registry.register(tool("built_in"));
        register(registry,"mcp:a",tool("a")); register(registry,"mcp:b",tool("b"));
        assertEquals(2,invoke(registry,"countOwnersWithPrefix",new Class[]{String.class},"mcp:"));
        assertEquals(1,invoke(registry,"unregisterOwner",new Class[]{String.class},"mcp:a"));
        assertNull(registry.getTool("a")); assertNotNull(registry.getTool("b"));
        assertEquals(1,invoke(registry,"unregisterOwnersWithPrefix",new Class[]{String.class},"mcp:"));
        assertNotNull(registry.getTool("built_in"));
        assertEquals(1,invoke(registry,"size",new Class[]{}));
    }
}
