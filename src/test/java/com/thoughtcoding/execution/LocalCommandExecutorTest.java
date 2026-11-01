package com.thoughtcoding.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LocalCommandExecutorTest {
    @TempDir Path directory;

    @Test void preservesUnicodeAndNonzeroShellExit() throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String command = windows ? "[Console]::Write('中文'); exit 7" : "printf '中文'; exit 7";
        var result = new LocalCommandExecutor().execute(command, directory, 10, null);
        assertEquals(7, result.exitCode());
        assertTrue(result.output().contains("中文"), result.output());
    }

    @Test void writesOnlyToSelectedWorkingDirectoryForRelativeCommand() throws Exception {
        Path workspace = Files.createDirectory(directory.resolve("workspace with spaces"));
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String command = windows ? "Set-Content -LiteralPath result.txt -Value done" : "printf done > result.txt";
        var result = new LocalCommandExecutor().execute(command, workspace, 10, null);
        assertEquals(0, result.exitCode(), result.output());
        assertEquals("done", Files.readString(workspace.resolve("result.txt")).trim());
        assertFalse(Files.exists(directory.resolve("result.txt")));
    }
}
