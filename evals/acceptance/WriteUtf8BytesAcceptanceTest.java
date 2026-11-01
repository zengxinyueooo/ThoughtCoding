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
class WriteUtf8BytesAcceptanceTest {
 @TempDir Path root;
 ToolResult write(String name, String content) throws Exception {
  Sandbox.init(root.toString());
  return new WriteTool(new AppConfig()).execute(new ObjectMapper().writeValueAsString(Map.of("path",name,"content",content)));
 }
 @Test void invalidInputCannotOverwrite() throws Exception {
  Files.writeString(root.resolve("old.txt"), "preserve");
  assertFalse(write("old.txt", "中".repeat(1366)).isSuccess());
  assertEquals("preserve", Files.readString(root.resolve("old.txt")));
 }
 @Test void invalidInputCannotCreateDirectories() throws Exception {
  assertFalse(write("new/sub/file.txt", "中".repeat(1366)).isSuccess());
  assertFalse(Files.exists(root.resolve("new")));
 }
 @Test void validBoundaryAndNestedWriteStillWork() throws Exception {
  assertTrue(write("nested/valid.txt", "x".repeat(4096)).isSuccess());
  assertEquals("x".repeat(4096), Files.readString(root.resolve("nested/valid.txt")));
  assertTrue(write("unicode.txt", "中".repeat(1365)).isSuccess());
 }
}
