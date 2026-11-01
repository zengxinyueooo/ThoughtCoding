package com.thoughtcoding.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.tools.file.EditTool;
import com.thoughtcoding.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EditCrlfAcceptanceTest {
    @TempDir Path directory;

    private ToolResult edit(Path file, String oldText, String newText, boolean all) throws Exception {
        return new EditTool(null).execute(new ObjectMapper().writeValueAsString(Map.of(
            "path", file.toString(), "old_string", oldText, "new_string", newText, "replace_all", all)));
    }

    @Test void replacesLfPatternInCrlfFile() throws Exception {
        Path file = directory.resolve("sample.txt");
        Files.writeString(file, "head\r\nfirst\r\nsecond\r\ntail\r\n");
        assertTrue(edit(file, "first\nsecond", "changed", false).isSuccess());
        assertEquals("head\nchanged\ntail\n", Files.readString(file).replace("\r\n", "\n"));
    }

    @Test void preservesLfReplacement() throws Exception {
        Path file = directory.resolve("sample.txt");
        Files.writeString(file, "a\nb\nc\n");
        assertTrue(edit(file, "a\nb", "x", false).isSuccess());
        assertEquals("x\nc\n", Files.readString(file));
    }

    @Test void missingPatternDoesNotWrite() throws Exception {
        Path file = directory.resolve("sample.txt");
        Files.writeString(file, "a\r\nb\r\n");
        byte[] before = Files.readAllBytes(file);
        assertFalse(edit(file, "absent", "x", false).isSuccess());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test void rejectsAmbiguousMatchWithoutWriting() throws Exception {
        Path file = directory.resolve("sample.txt");
        Files.writeString(file, "a\nb\na\nb\n");
        byte[] before = Files.readAllBytes(file);
        assertFalse(edit(file, "a\nb", "x", false).isSuccess());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test void replacesAllWhenExplicit() throws Exception {
        Path file = directory.resolve("sample.txt");
        Files.writeString(file, "a\r\nb\r\na\r\nb\r\n");
        assertTrue(edit(file, "a\nb", "x", true).isSuccess());
        assertEquals("x\nx\n", Files.readString(file).replace("\r\n", "\n"));
    }
}
