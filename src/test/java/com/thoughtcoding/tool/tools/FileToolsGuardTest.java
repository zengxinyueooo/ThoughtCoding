package com.thoughtcoding.tool.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.core.CancelToken;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.FileStateTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件工具防护链的集成测试：注入共享 {@link FileStateTracker} 后，
 * read → edit/write 的「先读后改 + stale-read 校验 + 大小限制 + 二进制嗅探」全链路。
 */
class FileToolsGuardTest {

    @TempDir
    Path dir;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 用 ObjectMapper 生成参数 JSON（Windows 路径的反斜杠需要正确转义）。 */
    private static String json(Map<String, Object> params) {
        try {
            return JSON.writeValueAsString(params);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String exec(com.thoughtcoding.tool.BaseTool tool, String json) {
        ToolResult r = tool.execute(json, new CancelToken());
        return r.isSuccess() ? r.getOutput() : r.getError();
    }

    // ═══════════════ stale-read 校验 ═══════════════

    @Test
    void 未read过的文件直接edit被拒绝() throws Exception {
        FileStateTracker tracker = new FileStateTracker();
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);

        EditTool edit = new EditTool(new AppConfig(), tracker);
        String msg = exec(edit, json(Map.of("path", file.toString(),
                "old_string", "hello", "new_string", "hi")));
        assertTrue(msg.contains("必须先用 read"), "应要求先读后改: " + msg);
    }

    @Test
    void 外部修改后edit被拒绝_重新read后恢复() throws Exception {
        FileStateTracker tracker = new FileStateTracker();
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "hello world", StandardCharsets.UTF_8);

        ReadTool read = new ReadTool(new AppConfig(), tracker);
        exec(read, json(Map.of("path", file.toString())));

        // 外部修改
        Thread.sleep(20);
        Files.writeString(file, "hello world (external edit)", StandardCharsets.UTF_8);

        EditTool edit = new EditTool(new AppConfig(), tracker);
        String msg = exec(edit, json(Map.of("path", file.toString(),
                "old_string", "hello", "new_string", "hi")));
        assertTrue(msg.contains("被外部修改"), "stale read 应被拦截: " + msg);

        // 重新 read 后认知对齐，edit 放行
        exec(read, json(Map.of("path", file.toString())));
        String ok = exec(edit, json(Map.of("path", file.toString(),
                "old_string", "hello", "new_string", "hi")));
        assertTrue(ok.contains("替换"), "重新读后应放行: " + ok);
    }

    @Test
    void 覆盖已存在文件前必须先read() throws Exception {
        FileStateTracker tracker = new FileStateTracker();
        Path file = dir.resolve("b.txt");
        Files.writeString(file, "precious data", StandardCharsets.UTF_8);

        WriteTool write = new WriteTool(new AppConfig(), tracker);
        String msg = exec(write, json(Map.of("path", file.toString(), "content", "overwrite")));
        assertTrue(msg.contains("必须先用 read"), "盲覆盖应被拦截: " + msg);
        // 文件内容未被破坏
        assertTrue(Files.readString(file).contains("precious"));
    }

    @Test
    void 新建文件不需要先read() throws Exception {
        FileStateTracker tracker = new FileStateTracker();
        WriteTool write = new WriteTool(new AppConfig(), tracker);
        String msg = exec(write, json(Map.of("path", dir.resolve("new.txt").toString(),
                "content", "fresh")));
        assertTrue(msg.contains("已写入"), "新建文件应放行: " + msg);
    }

    // ═══════════════ 大小与格式防护 ═══════════════

    @Test
    void 超大写入被拒绝() {
        WriteTool write = new WriteTool(new AppConfig(), new FileStateTracker());
        char[] big = new char[WriteTool.MAX_WRITE_CHARS + 1];
        java.util.Arrays.fill(big, 'x');
        String msg = exec(write, json(Map.of("path", dir.resolve("big.txt").toString(),
                "content", new String(big))));
        assertTrue(msg.contains("过大"), "应拒绝超大写入: " + msg);
    }

    @Test
    void 二进制文件被拒绝读取() throws Exception {
        Path file = dir.resolve("blob.bin");
        Files.write(file, new byte[]{0x00, 0x01, 0x00, 0x02, 'a', 'b'});
        ReadTool read = new ReadTool(new AppConfig(), new FileStateTracker());
        String msg = exec(read, json(Map.of("path", file.toString())));
        assertTrue(msg.contains("二进制"), "应识别二进制: " + msg);
    }

    @Test
    void 不存在的文件错误消息带行动指引() {
        ReadTool read = new ReadTool(new AppConfig(), new FileStateTracker());
        String msg = exec(read, json(Map.of("path", dir.resolve("no-such-file.txt").toString())));
        assertTrue(msg.contains("glob"), "错误应引导模型先 glob 确认: " + msg);
    }

    @Test
    void tracker为null时行为与旧版一致() throws Exception {
        // 兼容路径：未注入 tracker 的工具（旧构造器）不做 stale 校验
        Path file = dir.resolve("legacy.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        EditTool edit = new EditTool(new AppConfig());
        String ok = exec(edit, json(Map.of("path", file.toString(),
                "old_string", "hello", "new_string", "hi")));
        assertTrue(ok.contains("替换"), "无 tracker 时应保持旧行为: " + ok);
        assertFalse(ok.contains("必须先用 read"));
    }
}
