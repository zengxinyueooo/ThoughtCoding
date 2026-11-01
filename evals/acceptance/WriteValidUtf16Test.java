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
class WriteValidUtf16Test {
 @TempDir Path root;
 ToolResult write(String path,Object content) throws Exception {
  Sandbox.init(root.toString());
  return new WriteTool(new AppConfig()).execute(new ObjectMapper().writeValueAsString(Map.of("path",path,"content",content)));
 }
 @Test void invalidCannotOverwrite() throws Exception {
  Files.writeString(root.resolve("old.txt"),"preserve");
  assertFalse(write("old.txt",String.valueOf((char)0xD800)).isSuccess());
  assertEquals("preserve",Files.readString(root.resolve("old.txt")));
 }
 @Test void invalidCannotCreateParents() throws Exception {
  assertFalse(write("new/sub/file.txt",String.valueOf((char)0xD800)).isSuccess());
  assertFalse(Files.exists(root.resolve("new")));
 }
 @Test void validBoundaryAndCompatibility() throws Exception {
  String content="合法" + new String(Character.toChars(0x1F600));
  assertTrue(write("nested/file.txt",content).isSuccess());
  assertEquals(content,Files.readString(root.resolve("nested/file.txt")));
  assertTrue(write("nested/file.txt","replacement").isSuccess());
  assertEquals("replacement",Files.readString(root.resolve("nested/file.txt")));
  assertFalse(write("low/file.txt", String.valueOf((char)0xDC00)).isSuccess()); assertFalse(Files.exists(root.resolve("low")));
 }
}
