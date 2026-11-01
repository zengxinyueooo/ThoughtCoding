package com.thoughtcoding.eval;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.security.Sandbox;
import com.thoughtcoding.tool.tools.WriteTool;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WriteLimitAcceptanceTest {
    @TempDir Path root;
    ToolResult write(String name, String content) throws Exception {
        Sandbox.init(root.toString());
        return new WriteTool(new AppConfig()).execute(new ObjectMapper().writeValueAsString(
            Map.of("path",name,"content",content)));
    }
    @Test void oversizedWriteCannotOverwriteExistingFile() throws Exception {
        Files.writeString(root.resolve("existing.txt"), "preserve");
        assertFalse(write("existing.txt", "x".repeat(2_000_001)).isSuccess());
        assertEquals("preserve", Files.readString(root.resolve("existing.txt")));
    }
    @Test void rejectedWriteDoesNotCreateParentOrFile() throws Exception {
        assertFalse(write("new/sub/file.txt", "x".repeat(2_000_001)).isSuccess());
        assertFalse(Files.exists(root.resolve("new")));
    }
    @Test void exactLimitAndOrdinaryNestedWritesWork() throws Exception {
        assertTrue(write("limit.txt", "x".repeat(2_000_000)).isSuccess());
        assertEquals(2_000_000, Files.size(root.resolve("limit.txt")));
        assertTrue(write("nested/small.txt", "中文").isSuccess());
        assertEquals("中文", Files.readString(root.resolve("nested/small.txt")));
    }
}
