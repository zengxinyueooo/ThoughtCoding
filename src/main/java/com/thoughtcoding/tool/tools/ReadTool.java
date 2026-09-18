package com.thoughtcoding.tool.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.exception.WorkspaceSecurityException;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import com.thoughtcoding.security.Sandbox;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * read 工具：按行读取文本文件，cat -n 风格行号输出，支持 offset/limit 分页。
 */
public class ReadTool extends BaseTool {
    private static final int DEFAULT_LIMIT = 2000;
    private final long maxFileSize;
    private final com.thoughtcoding.tool.FileStateTracker stateTracker;

    public ReadTool(AppConfig appConfig) {
        this(appConfig, null);
    }

    public ReadTool(AppConfig appConfig, com.thoughtcoding.tool.FileStateTracker stateTracker) {
        super("read", "读取文本文件，返回带行号的内容（cat -n 风格）。参数：path（必填）、offset（可选，起始行，1 起）、limit（可选，读取行数）用于分页。");
        Long m = appConfig.getTools().getRead().getMaxFileSize();
        this.maxFileSize = (m == null || m <= 0) ? 10485760L : m;
        this.stateTracker = stateTracker;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("path", "要读取的文件路径")
                .addIntegerProperty("offset", "起始行号（1 起，可选）")
                .addIntegerProperty("limit", "读取行数（可选）")
                .required("path")
                .additionalProperties(false)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> params = mapper.readValue(input, Map.class);

            Object p = params.get("path");
            if (p == null) {
                return error("read 需要 'path' 字段", System.currentTimeMillis() - startTime);
            }
            Path path = Sandbox.resolve(p.toString());

            if (!Files.exists(path)) {
                return error("文件不存在: " + p + "。不要猜测路径——先用 glob 确认实际文件名再读取。",
                        System.currentTimeMillis() - startTime);
            }
            if (Files.isDirectory(path)) {
                return error("这是目录不是文件: " + p + "。如需列出内容，请用 glob 工具。",
                        System.currentTimeMillis() - startTime);
            }
            if (Files.size(path) > maxFileSize) {
                return error("文件过大: " + Files.size(path) + " 字节（上限 " + maxFileSize
                                + "）。请用 bash 配合 sed/head 分段查看，或用 offset/limit 分页。",
                        System.currentTimeMillis() - startTime);
            }
            if (looksLikeBinary(path)) {
                return error("疑似二进制文件，不做文本读取: " + p,
                        System.currentTimeMillis() - startTime);
            }

            int offset = intParam(params.get("offset"), 1);
            if (offset < 1) offset = 1;
            int limit = intParam(params.get("limit"), DEFAULT_LIMIT);
            if (limit <= 0) limit = DEFAULT_LIMIT;

            List<String> lines;
            try {
                lines = Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.nio.charset.MalformedInputException e) {
                return error("文件不是有效的 UTF-8 文本（可能为 GBK 等本地编码或二进制）。"
                                + "可用 bash 执行 iconv 转码后再读取。",
                        System.currentTimeMillis() - startTime);
            }
            if (lines.isEmpty()) {
                return success("（空文件）", System.currentTimeMillis() - startTime);
            }
            int start = offset - 1;
            if (start >= lines.size()) {
                return error("offset 超出文件行数（共 " + lines.size() + " 行）",
                        System.currentTimeMillis() - startTime);
            }
            int end = Math.min(lines.size(), start + limit);

            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(String.format("%6d\t%s%n", i + 1, lines.get(i)));
            }
            if (end < lines.size()) {
                sb.append(String.format("... (还有 %d 行，用 offset=%d 继续)%n", lines.size() - end, end + 1));
            }
            if (stateTracker != null) {
                stateTracker.recordRead(path); // 登记模型刚看到的快照，供 edit/write 做一致性校验
            }
            return success(sb.toString(), System.currentTimeMillis() - startTime);

        } catch (WorkspaceSecurityException e) {
            return error(e.getMessage(), System.currentTimeMillis() - startTime);
        } catch (IOException e) {
            return error("读取失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("read 参数解析失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    /** 二进制嗅探：头部 8KB 内出现 NUL 字节即判定为二进制（文本文件几乎不会含 0x00）。 */
    private boolean looksLikeBinary(Path path) {
        try (var in = Files.newInputStream(path)) {
            byte[] head = in.readNBytes(8192);
            for (byte b : head) {
                if (b == 0) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false; // 嗅探失败不拦截，交给后续正常读取报错
        }
    }

    private int intParam(Object o, int def) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(o.toString().trim());
            } catch (NumberFormatException ignored) {
                // 用默认值
            }
        }
        return def;
    }
}
