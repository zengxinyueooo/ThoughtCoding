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
class WriteLineWidthTest {
 @TempDir Path root;
 ToolResult write(String path,Object content) throws Exception {
  Sandbox.init(root.toString());
  return new WriteTool(new AppConfig()).execute(new ObjectMapper().writeValueAsString(Map.of("path",path,"content",content)));
 }
 @Test void invalidCannotOverwrite() throws Exception {
  Files.writeString(root.resolve("old.txt"),"preserve");
  assertFalse(write("old.txt","x".repeat(257)).isSuccess());
  assertEquals("preserve",Files.readString(root.resolve("old.txt")));
 }
 @Test void invalidCannotCreateParents() throws Exception {
  assertFalse(write("new/sub/file.txt","x".repeat(257)).isSuccess());
  assertFalse(Files.exists(root.resolve("new")));
 }
 @Test void validBoundaryAndCompatibility() throws Exception {
  String content="x".repeat(256) + "\r\n" + "y".repeat(256);
  assertTrue(write("nested/file.txt",content).isSuccess());
  assertEquals(content,Files.readString(root.resolve("nested/file.txt")));
  assertTrue(write("nested/file.txt","replacement").isSuccess());
  assertEquals("replacement",Files.readString(root.resolve("nested/file.txt")));
  assertTrue(write("empty.txt", "").isSuccess());
 }
}
