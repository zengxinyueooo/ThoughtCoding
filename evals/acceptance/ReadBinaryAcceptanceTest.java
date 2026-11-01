package com.thoughtcoding.eval;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.security.Sandbox;
import com.thoughtcoding.tool.tools.ReadTool;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReadBinaryAcceptanceTest {
    @TempDir Path root;
    private ToolResult read(String file, Map<String,Object> extra) throws Exception {
        Sandbox.init(root.toString());
        var args = new java.util.HashMap<String,Object>(extra); args.put("path", file);
        return new ReadTool(new AppConfig()).execute(new ObjectMapper().writeValueAsString(args));
    }
    @Test void nulInHeadIsRejectedEvenWithTextExtension() throws Exception {
        Files.write(root.resolve("text.txt"), new byte[]{65,66,0,67,10});
        assertFalse(read("text.txt", Map.of()).isSuccess());
    }
    @Test void extensionDoesNotDetermineBinaryContent() throws Exception {
        Files.writeString(root.resolve("sample.bin"), "第一行\nsecond\nthird\n");
        ToolResult result = read("sample.bin", Map.of("offset",2,"limit",1));
        assertTrue(result.isSuccess()); assertTrue(result.getOutput().contains("second"));
        assertFalse(result.getOutput().contains("第一行"));
    }
    @Test void emptyTextRemainsReadable() throws Exception {
        Files.writeString(root.resolve("empty.txt"), "");
        assertTrue(read("empty.txt", Map.of()).isSuccess());
    }
}
