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
 * edit 工具：对文件做精确字符串替换。
 * old_string 未找到、或出现多次且未设 replace_all 时报错。
 * 注入 {@link com.thoughtcoding.tool.FileStateTracker} 后强制「先读后改」并拦截外部修改
 * （stale read 校验）；tracker 为 null 时跳过校验（测试/降级路径，行为与旧版一致）。
 */
public class EditTool extends BaseTool {

    private final com.thoughtcoding.tool.FileStateTracker stateTracker;

    public EditTool(AppConfig appConfig) {
        this(appConfig, null);
    }

    public EditTool(AppConfig appConfig, com.thoughtcoding.tool.FileStateTracker stateTracker) {
        super("edit", "对文件做精确字符串替换。old_string 必须是文件中的原文（不是 Read 工具输出的带行号的格式），将 old_string 替换为 new_string。old_string 必须在文件中唯一，否则需设 replace_all=true。参数：path、old_string、new_string（必填）、replace_all（可选）。");
        this.stateTracker = stateTracker;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("path", "要修改的文件路径")
                .addStringProperty("old_string", "被替换的原文本")
                .addStringProperty("new_string", "替换后的新文本")
                .addBooleanProperty("replace_all", "是否替换全部匹配（默认 false）")
                .required("path", "old_string", "new_string")
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
            Object oldObj = params.get("old_string");
            Object newObj = params.get("new_string");
            if (p == null || oldObj == null || newObj == null) {
                return error("edit 需要 'path'、'old_string'、'new_string' 字段",
                        System.currentTimeMillis() - startTime);
            }
            String oldString = oldObj.toString();
            String newString = newObj.toString();
            if (oldString.isEmpty()) {
                return error("old_string 不能为空", System.currentTimeMillis() - startTime);
            }
            boolean replaceAll = Boolean.TRUE.equals(params.get("replace_all"))
                    || "true".equalsIgnoreCase(String.valueOf(params.get("replace_all")));

            Path path = Sandbox.resolve(p.toString());
            if (!Files.exists(path) || Files.isDirectory(path)) {
                return error("文件不存在: " + p + "。不要猜测路径——先用 glob 确认实际文件名再编辑。",
                        System.currentTimeMillis() - startTime);
            }

            // stale-read 校验：模型认知与现实的一致性检查
            if (stateTracker != null) {
                com.thoughtcoding.tool.FileStateTracker.State st = stateTracker.checkStale(path);
                if (st == com.thoughtcoding.tool.FileStateTracker.State.NEVER_READ) {
                    return error("编辑前必须先用 read 读取该文件：防止基于猜测的内容破坏文件。请先 read 再 edit。",
                            System.currentTimeMillis() - startTime);
                }
                if (st == com.thoughtcoding.tool.FileStateTracker.State.STALE) {
                    return error("文件在上次读取后被外部修改过，当前内容可能与你看到的不同。请重新 read 后再编辑。",
                            System.currentTimeMillis() - startTime);
                }
            }

            String content = Files.readString(path).replace("\r\n", "\n");
            int count = countOccurrences(content, oldString);
            if (count == 0) {
                return error("未找到 old_string，未做修改", System.currentTimeMillis() - startTime);
            }
            if (count > 1 && !replaceAll) {
                return error("old_string 出现 " + count + " 次，不唯一；请补充上下文使其唯一，或设置 replace_all=true",
                        System.currentTimeMillis() - startTime);
            }

            String result;
            if (replaceAll) {
                result = content.replace(oldString, newString);
            } else {
                int idx = content.indexOf(oldString);
                result = content.substring(0, idx) + newString + content.substring(idx + oldString.length());
            }
            Files.writeString(path, result);
            if (stateTracker != null) {
                stateTracker.recordRead(path); // 编辑后的内容模型已知，登记新快照
            }

            return success("已在 " + path + " 替换 " + (replaceAll ? count : 1) + " 处",
                    System.currentTimeMillis() - startTime);

        } catch (WorkspaceSecurityException e) {
            return error(e.getMessage(), System.currentTimeMillis() - startTime);
        } catch (IOException e) {
            return error("编辑失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return error("edit 参数解析失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
