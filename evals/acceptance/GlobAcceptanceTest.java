package com.thoughtcoding.eval;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GlobAcceptanceTest {
    @TempDir Path directory;
    private ToolResult glob(String pattern) throws Exception {
        Class<?> type;
        // The historical reference also moved packages; acceptance semantics are identical.
        try { type = Class.forName("com.thoughtcoding.tools.search.GlobTool"); }
        catch (ClassNotFoundException oldPackageAbsent) { type = Class.forName("com.thoughtcoding.tools.GlobTool"); }
        Object tool = type.getConstructor(AppConfig.class).newInstance(new AppConfig());
        return (ToolResult) type.getMethod("execute", String.class).invoke(tool,
            new ObjectMapper().writeValueAsString(Map.of("path", directory.toString(), "pattern", pattern)));
    }
    @Test void recursivePatternIncludesRootAndNestedFiles() throws Exception {
        Files.writeString(directory.resolve("Root.java"), "root");
        Files.createDirectories(directory.resolve("src"));
        Files.writeString(directory.resolve("src/Nested.java"), "nested");
        ToolResult result = glob("**/*.java");
        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Root.java"));
        assertTrue(result.getOutput().contains("Nested.java"));
    }
    @Test void dependencyDirectoryIsSkipped() throws Exception {
        Files.createDirectories(directory.resolve("node_modules/pkg"));
        Files.writeString(directory.resolve("node_modules/pkg/Dependency.java"), "dependency");
        Files.writeString(directory.resolve("Own.java"), "own");
        ToolResult result = glob("**/*.java");
        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Own.java"));
        assertFalse(result.getOutput().contains("Dependency.java"));
    }
    @Test void simplePatternStillWorks() throws Exception {
        Files.writeString(directory.resolve("Plain.java"), "plain");
        assertTrue(glob("*.java").getOutput().contains("Plain.java"));
    }
}
