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
import java.util.Map;

/**
 * write 工具：写入（覆盖）文件，自动创建父目录。
 */
public class WriteTool extends BaseTool {

    /** 单次写入上限（字符）：防模型失控输出或被诱导写巨型文件撑爆磁盘。 */
    static final int MAX_WRITE_CHARS = 2_000_000;

    private final com.thoughtcoding.tool.FileStateTracker stateTracker;

    public WriteTool(AppConfig appConfig) {
        this(appConfig, null);
    }

    public WriteTool(AppConfig appConfig, com.thoughtcoding.tool.FileStateTracker stateTracker) {
        super("write", "写入/覆盖文件，自动创建父目录。参数：path（必填）、content（必填，完整文件内容）。");
        this.stateTracker = stateTracker;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("path", "要写入的文件路径")
                .addStringProperty("content", "文件内容（覆盖写）")
                .required("path", "content")
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
            Object c = params.get("content");
            if (p == null) {
                return error("write 需要 'path' 字段", System.currentTimeMillis() - startTime);
            }
            if (c == null) {
                return error("write 需要 'content' 字段", System.currentTimeMillis() - startTime);
            }
            String content = c.toString();
            if (content.length() > MAX_WRITE_CHARS) {
                return error("写入内容过大: " + content.length() + " 字符（上限 " + MAX_WRITE_CHARS
                                + "）。请拆分为多次写入或缩小范围。",
                        System.currentTimeMillis() - startTime);
            }

            Path path = Sandbox.resolve(p.toString());

            // 覆盖已有文件时做 stale-read 校验：模型必须先读过当前版本才能覆盖
            if (stateTracker != null && Files.exists(path)) {
                com.thoughtcoding.tool.FileStateTracker.State st = stateTracker.checkStale(path);
                if (st == com.thoughtcoding.tool.FileStateTracker.State.NEVER_READ) {
                    return error("覆盖已有文件前必须先用 read 读取它：你从未读取过这个文件，"
                            + "直接覆盖可能销毁未知内容。请先 read 再 write。",
                            System.currentTimeMillis() - startTime);
                }
                if (st == com.thoughtcoding.tool.FileStateTracker.State.STALE) {
                    return error("文件在上次读取后被外部修改过，直接覆盖会销毁这些改动。请重新 read 确认后再写。",
                            System.currentTimeMillis() - startTime);
                }
            }

            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content);
            if (stateTracker != null) {
                stateTracker.recordRead(path); // 写入后的内容模型已知，登记新快照
            }

            return success("已写入: " + path + " (" + content.length() + " 字符)",
                    System.currentTimeMillis() - startTime);

        } catch (WorkspaceSecurityException e) {
            return error(e.getMessage(), System.currentTimeMillis() - startTime);
        } catch (IOException e) {
            return error("写入失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("write 参数解析失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
