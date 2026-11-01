"""Prepare five never-run task contracts; does not execute a model."""
import difflib
import json
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[1]
BASE='2c2ac20f2942e98601c5a60bc0b423657a960ad8'
SOURCE='src/main/java/com/thoughtcoding/tool/tools/WriteTool.java'
CASES=[
 ('write-string-type','WriteStringTypeTest',
  'write 的 content 必须是真正的 JSON 字符串，数字、布尔、数组和对象都要返回失败，且在失败前不能覆盖文件或创建父目录。保留空字符串、Unicode、正常覆盖写入及自动创建父目录，只修改 WriteTool.java。',
  'if (!(c instanceof String)) { return error("content must be string", System.currentTimeMillis() - startTime); }',
  '123', '"正常内容"', 'assertFalse(write("invalid-array.txt", java.util.List.of("x")).isSuccess()); assertFalse(write("invalid-bool.txt", true).isSuccess());'),
 ('write-lf-budget','WriteLfBudgetTest',
  '为 write 增加 LF 字符数量限制：一份内容最多允许64个 U+000A，恰好64个允许，超过64个须失败，不能覆盖文件或创建父目录。不得改写换行内容；保留空文本、Unicode、覆盖与正常目录创建，只修改 WriteTool.java。',
  'if (c instanceof String s && s.chars().filter(ch -> ch == 10).count() > 64) { return error("too many LF", System.currentTimeMillis() - startTime); }',
  '"x\\n".repeat(65)', '"x\\n".repeat(64)', 'assertTrue(write("crlf.txt", "a\\r\\nb\\r\\n").isSuccess()); assertEquals("a\\r\\nb\\r\\n", Files.readString(root.resolve("crlf.txt")));'),
 ('write-valid-utf16','WriteValidUtf16Test',
  'write 在任何文件系统副作用前校验文本中的UTF-16代理项：拒绝孤立高代理项和孤立低代理项，失败不覆盖文件、不创建父目录；合法emoji代理对、普通Unicode及空文本必须正常写入，保留覆盖和自动建目录。只修改 WriteTool.java。',
  'if (c instanceof String s && !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(s)) { return error("unpaired surrogate", System.currentTimeMillis() - startTime); }',
  'String.valueOf((char)0xD800)', '"合法" + new String(Character.toChars(0x1F600))', 'assertFalse(write("low/file.txt", String.valueOf((char)0xDC00)).isSuccess()); assertFalse(Files.exists(root.resolve("low")));'),
 ('write-line-width','WriteLineWidthTest',
  '为 write 增加单行长度限制：按LF分行，CRLF中的CR属于行结束符不计长度，每行最多256个Java UTF-16字符，恰好256允许；任一行超过上限则失败且不能覆盖文件或创建父目录。内容不可改写；保留空文本、覆盖和正常建目录。只修改 WriteTool.java。',
  'if (c instanceof String s && java.util.Arrays.stream(s.split("\\\\r?\\\\n", -1)).anyMatch(line -> line.length() > 256)) { return error("line too wide", System.currentTimeMillis() - startTime); }',
  '"x".repeat(257)', '"x".repeat(256) + "\\r\\n" + "y".repeat(256)', 'assertTrue(write("empty.txt", "").isSuccess());'),
 ('write-control-text','WriteControlTextTest',
  'write 拒绝 U+0000至U+001F 的ASCII控制字符，但TAB、LF、CR三个例外必须允许；拒绝时不能覆盖文件或创建父目录。正常Unicode、emoji、空文本、覆盖和自动建目录必须保留，不转换内容。只修改 WriteTool.java。',
  'if (c instanceof String s && s.chars().anyMatch(ch -> ch < 32 && ch != 9 && ch != 10 && ch != 13)) { return error("forbidden control", System.currentTimeMillis() - startTime); }',
  '"bad" + (char)7', '"正常\\t文本\\r\\n"', 'assertTrue(write("empty.txt", "").isSuccess());'),
]


def main():
    source=subprocess.check_output(['git','show',BASE+':'+SOURCE],cwd=ROOT).decode('utf-8')
    for ident,cls,prompt,guard,rejected,valid,extra in CASES:
        task=dict(id=ident,kind='synthetic_unseen_requirement',base_commit=BASE,prompt=prompt,
            allowed_paths=[SOURCE],acceptance_file=cls+'.java',acceptance_class='com.thoughtcoding.eval.'+cls,
            timeout_seconds=900,repetitions=2,split='prospective_same_module_transfer',minimum_acceptance_tests=3,
            reference_patch='reference-patches/'+ident+'.patch',
            provenance='Agent-assisted synthetic requirement and test construction; calibrated with Java before first tested-Agent run; seen module, not cross-module generalization')
        paths=[ROOT/'evals/tasks'/f'{ident}.json',ROOT/'evals/acceptance'/f'{cls}.java',ROOT/'evals/reference-patches'/f'{ident}.patch',ROOT/'evals/reference-patches'/f'{ident}-bad.patch']
        assert not any(p.exists() for p in paths),'Never overwrite previously prepared cases'
        paths[0].write_text(json.dumps(task,ensure_ascii=False,indent=2),encoding='utf-8')
        java='''package com.thoughtcoding.eval;
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
class CLASS {
 @TempDir Path root;
 ToolResult write(String path,Object content) throws Exception {
  Sandbox.init(root.toString());
  return new WriteTool(new AppConfig()).execute(new ObjectMapper().writeValueAsString(Map.of("path",path,"content",content)));
 }
 @Test void invalidCannotOverwrite() throws Exception {
  Files.writeString(root.resolve("old.txt"),"preserve");
  assertFalse(write("old.txt",REJECTED).isSuccess());
  assertEquals("preserve",Files.readString(root.resolve("old.txt")));
 }
 @Test void invalidCannotCreateParents() throws Exception {
  assertFalse(write("new/sub/file.txt",REJECTED).isSuccess());
  assertFalse(Files.exists(root.resolve("new")));
 }
 @Test void validBoundaryAndCompatibility() throws Exception {
  String content=VALID;
  assertTrue(write("nested/file.txt",content).isSuccess());
  assertEquals(content,Files.readString(root.resolve("nested/file.txt")));
  assertTrue(write("nested/file.txt","replacement").isSuccess());
  assertEquals("replacement",Files.readString(root.resolve("nested/file.txt")));
  EXTRA
 }
}
'''.replace('CLASS',cls).replace('REJECTED',rejected).replace('VALID',valid).replace('EXTRA',extra)
        paths[1].write_text(java,encoding='utf-8')
        fixed=source.replace('String content = c.toString();',guard+'\n            String content = c.toString();')
        for path,text in [(paths[2],fixed),(paths[3],fixed.replace('Files.createDirectories(path.getParent());','// Known bad: normal directory creation removed.'))]:
            path.write_text(''.join(difflib.unified_diff(source.splitlines(True),text.splitlines(True),fromfile='a/'+SOURCE,tofile='b/'+SOURCE)),encoding='utf-8')
    print('Prepared 5 cases; zero Agent/model calls')


if __name__=='__main__':main()
