package com.thoughtcoding.publicchecks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.security.Sandbox;
import com.thoughtcoding.tool.tools.WriteTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Public compatibility checks for existing behavior; no size-limit acceptance oracle. */
class PublicWriteCompatibilityTest {
    @TempDir Path root;
    @Test void legalNestedWriteMustStillCreateItsParentDirectories() throws Exception {
        Sandbox.init(root.toString());
        var result = new WriteTool(new AppConfig()).execute(new ObjectMapper().writeValueAsString(
                Map.of("path", "nested/public/sample.txt", "content", "hello")));
        assertTrue(result.isSuccess(), "合法小文件写入仍需自动创建父目录；不要把仅针对超限输入的限制扩大到正常路径");
        assertEquals("hello", Files.readString(root.resolve("nested/public/sample.txt")));
    }
    @Test void legalOverwriteMustStillUpdateExistingFile() throws Exception {
        Sandbox.init(root.toString());
        Files.writeString(root.resolve("existing.txt"), "before");
        var result = new WriteTool(new AppConfig()).execute(new ObjectMapper().writeValueAsString(
                Map.of("path", "existing.txt", "content", "after")));
        assertTrue(result.isSuccess(), "合法内容应保持原有覆盖写入能力");
        assertEquals("after", Files.readString(root.resolve("existing.txt")));
    }
}
