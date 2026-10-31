package com.thoughtcoding.tool.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.thoughtcoding.task.TaskService;
import com.thoughtcoding.task.TaskStep;
import com.thoughtcoding.core.CancelToken;

/**
 * todo_write 工具：创建并维护一个结构化的待办清单，用于规划、跟踪多步骤任务的进度。
 *
 * <p>步骤通过显式 Run 句柄写入当前任务检查点；工具单例不持有会话或任务可变状态。
 */
public class TodoWriteTool extends BaseTool {

    /** 合法状态值。 */
    private static final List<String> STATUSES = List.of("pending", "in_progress", "completed");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public TodoWriteTool() {
        super("todo_write",
                "创建并维护一个结构化的待办清单，用于规划和跟踪多步骤任务的进度。\n"
                + "何时使用：任务需要 3 个及以上步骤、较复杂/非平凡；用户一次给出多项任务；或你刚开始一项较大的任务时。\n"
                + "用法：传入【完整】的 todos 列表（每次调用都要传全量，包含已完成项，而非只传增量）；"
                + "每项含 content（任务描述，祈使句）与 status（pending/in_progress/completed）。\n"
                + "规则：同一时刻最多只应有一个任务处于 in_progress；完成一项后立即标记为 completed，并把下一项置为 in_progress。\n"
                + "已有步骤更新时传 step_id 保持身份稳定，可传 evidence 记录完成证据。步骤持久化到当前任务。"
                + "本工具不执行计划中的实际操作；简单问题无需使用。");
    }

    @Override
    public JsonObjectSchema inputSchema() {
        JsonObjectSchema item = JsonObjectSchema.builder()
                .addStringProperty("step_id", "已有步骤的稳定 ID；新增步骤可以省略")
                .addStringProperty("content", "任务的简短描述（祈使句，如“实现登录接口”）")
                .addStringProperty("evidence", "完成证据，如测试结果或产物路径")
                .addEnumProperty("status", STATUSES, "任务状态：pending（待办）/ in_progress（进行中）/ completed（已完成）")
                .required("content", "status")
                .additionalProperties(false)
                .build();

        JsonArraySchema todosArray = JsonArraySchema.builder()
                .description("完整的任务清单。每次调用都要传【全量】列表（含已完成项），而不是增量。")
                .items(item)
                .build();

        return JsonObjectSchema.builder()
                .addProperty("todos", todosArray)
                .required("todos")
                .additionalProperties(false)
                .build();
    }

    @Override
    public ToolResult execute(String input) {
        return error("todo_write 需要任务执行上下文，请从 Agent 任务调用。");
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(String input, CancelToken token, TaskService.Run task) {
        if (task == null) return execute(input);
        long startTime = System.currentTimeMillis();
        try {
            Map<String, Object> params = MAPPER.readValue(input, Map.class);

            Object todosObj = params.get("todos");
            if (!(todosObj instanceof List)) {
                return error("todo_write 需要 'todos' 数组（完整任务清单）", System.currentTimeMillis() - startTime);
            }

            List<Object> raw = (List<Object>) todosObj;

            // 空列表 → 清空清单
            if (raw.isEmpty()) {
                task.updateSteps(List.of());
                return success("任务清单已清空。", System.currentTimeMillis() - startTime);
            }

            List<TaskStep> parsed = new ArrayList<>();
            List<TaskStep> existing = task.state().steps();
            for (Object o : raw) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> item = (Map<String, Object>) o;

                Object contentObj = item.get("content");
                String content = contentObj == null ? "" : contentObj.toString().trim();
                if (content.isEmpty()) {
                    continue; // 跳过无 content 的无效项
                }

                Object statusObj = item.get("status");
                String status = statusObj == null ? "" : statusObj.toString().trim().toLowerCase(Locale.ROOT);
                if (!STATUSES.contains(status)) {
                    status = "pending"; // 非法/缺失状态回退为 pending
                }

                Object idObj = item.get("step_id");
                String id = idObj == null ? "" : idObj.toString().trim();
                if (id.isEmpty()) {
                    id = existing.stream().filter(s -> s.content().equals(content)).map(TaskStep::stepId)
                            .findFirst().orElseGet(() -> java.util.UUID.randomUUID().toString());
                } else {
                    String suppliedId = id;
                    if (existing.stream().noneMatch(s -> s.stepId().equals(suppliedId))) {
                        return error("未知 step_id: " + id);
                    }
                }
                String evidence = item.get("evidence") == null ? "" : item.get("evidence").toString();
                if (evidence.isBlank()) {
                    String stableId = id;
                    evidence = existing.stream().filter(s -> s.stepId().equals(stableId))
                            .map(TaskStep::evidence).findFirst().orElse("");
                }
                parsed.add(new TaskStep(id, content, status, evidence));
            }

            if (parsed.isEmpty()) {
                return error("没有有效的任务项（每项需包含非空 content）", System.currentTimeMillis() - startTime);
            }

            // 整体替换会话内清单
            task.updateSteps(parsed);

            return success(render(parsed), System.currentTimeMillis() - startTime);

        } catch (com.thoughtcoding.task.TaskPersistenceException e) {
            throw e;
        } catch (Exception e) {
            return error("todo_write 参数解析失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    /** 把当前清单渲染为带图标的进度视图（既用于终端显示，也回喂给模型确认状态）。 */
    private String render(List<TaskStep> todos) {
        long done = todos.stream().filter(t -> "completed".equals(t.status())).count();

        StringBuilder sb = new StringBuilder();
        sb.append("TodoList (").append(done).append('/').append(todos.size()).append(")\n");
        for (TaskStep t : todos) {
            sb.append("  ").append(icon(t.status())).append(' ').append(t.content())
                    .append(" [").append(t.stepId()).append("]").append('\n');
        }
        return sb.toString().trim();
    }

    private static String icon(String status) {
        return switch (status) {
            case "completed" -> "✓";
            case "in_progress" -> "▸";
            default -> "○";
        };
    }
}
