package com.thoughtcoding.tool.tools;

import com.thoughtcoding.observability.Telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.core.SubAgent;
import com.thoughtcoding.core.ThoughtCodingContext;
import com.thoughtcoding.core.WorktreeManager;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.security.Sandbox;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * subAgent 工具：把一个复杂、可独立完成的多步子任务派发给【子Agent】处理。
 *
 * <p>子Agent在全新、隔离的对话历史里跑自己的 agentic 循环（可用除 subAgent 外的全部工具），
 * 跑完只把<b>最终结论</b>回传给主Agent，所有中间过程（读文件、跑命令、试错）都不进主上下文。
 * 因此它增加的是「把大任务外包出去、保持主上下文干净」的能力，而非新的执行能力。
 *
 * <p><b>关键约束</b>：
 * <ul>
 *   <li>子Agent看不到主对话历史 —— {@code prompt} 必须自包含，把背景/目标/约束/验收标准讲清楚；</li>
 *   <li>只返回最终结论，无法拿回中间步骤；</li>
 *   <li>子Agent内部的写/执行类工具照样走权限确认（安全不打折）；</li>
 *   <li>不会递归：{@code chatOnceForSubagent} 已把 subAgent 自身从子Agent可见的工具规格中过滤掉，
 *       模型根本拿不到这个工具，也就无从再派发子Agent。</li>
 * </ul>
 */
public class SubAgentTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ThoughtCodingContext context;

    public SubAgentTool(ThoughtCodingContext context) {
        super("subAgent",
                "把一个复杂、可独立完成的多步子任务派发给子Agent处理，让主对话保持干净。\n"
                + "何时使用：任务较大且可独立完成（如“在某模块实现并自测一个功能”“调研代码库某问题并给出结论”），"
                + "其大量中间步骤（读文件、跑命令、试错）不需要留在主对话里时。简单/单步任务直接自己做，无需使用。\n"
                + "注意：子Agent【看不到】当前对话历史，prompt 必须自包含（写清背景、目标、约束、期望的结论形式）；"
                + "子Agent只会回传【最终结论】，中间过程不保留。启用 Git Worktree 隔离时，"
                + "它的文件改动会保存到独立本地分支，不会自动写入或合并主工作区；"
                + "不要要求子Agent自行切换分支、创建 worktree 或执行合并。\n"
                + "参数：description（简短任务标签，用于展示）、prompt（交给子Agent的详细任务指令）。");
        this.context = context;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("description", "子Agent任务的简短标签（3-8 字，用于终端展示）")
                .addStringProperty("prompt",
                        "交给子Agent的详细任务指令。必须自包含——子Agent看不到主对话，"
                        + "请把背景、目标、约束、期望的结论形式都写清楚。")
                .addBooleanProperty("background",
                        "是否后台运行（默认 false）。true 时立即返回，子Agent在后台独立运行，"
                        + "结论将在完成后自动注入下一轮对话——适合不需要立刻用上结论的独立任务。")
                .required("description", "prompt")
                .additionalProperties(false)
                .build();
    }

    @Override
    public ToolResult execute(String input) {
        return execute(input, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(String input, com.thoughtcoding.core.CancelToken token) {
        return execute(input, token, null);
    }

    @Override
    public ToolResult execute(String input, com.thoughtcoding.core.CancelToken token,
                              com.thoughtcoding.task.TaskService.Run parent) {
        return execute(input, token, parent, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(String input, com.thoughtcoding.core.CancelToken token,
                              com.thoughtcoding.task.TaskService.Run parent, String invocationId) {
        long startTime = System.currentTimeMillis();
        try {
            Map<String, Object> params = MAPPER.readValue(input, Map.class);

            Object promptObj = params.get("prompt");
            String prompt = promptObj == null ? "" : promptObj.toString().trim();
            if (prompt.isEmpty()) {
                return error("subAgent 需要 'prompt' 字段（交给子Agent的详细任务指令）",
                        System.currentTimeMillis() - startTime);
            }

            Object descObj = params.get("description");
            String description = descObj == null ? "" : descObj.toString().trim();
            if (description.isEmpty()) {
                return error("subAgent 需要 'description' 字段（简短任务标签）",
                        System.currentTimeMillis() - startTime);
            }

            boolean background = Boolean.TRUE.equals(params.get("background"));

            if (parent != null && context.getTaskService() != null) {
                String id = context.getTaskService().createChild(parent, invocationId, prompt, description,
                        background, context.getAppConfig().getAi().isSubagentWorktreeIsolation());
                String session = parent.state().sessionId();
                var runner = new com.thoughtcoding.core.DurableSubAgentRunner(context);
                if (background) {
                    context.getSubAgentExecutor().startDurableBackground(id, description,
                            taskToken -> runner.run(id, session, taskToken));
                    return success("后台子任务已登记: " + id + "，结论将在后续对话注入。", System.currentTimeMillis() - startTime);
                }
                return success(runner.run(id, session, token), System.currentTimeMillis() - startTime);
            }

            if (background) {
                // 后台模式：任务用【自己的】token（跨回合存活，不随本回合取消），
                // 由 SubAgentExecutor 统一管理生命周期与结论注入。
                com.thoughtcoding.core.SubAgentExecutor.BackgroundTask task =
                        context.getSubAgentExecutor().startBackground(description,
                                taskToken -> runSubAgent(prompt, description, taskToken, true));
                return success("子Agent任务已启动（id: " + task.id() + "，标签: " + description
                                + "），正在后台独立运行。完成后结论会自动注入下一轮对话，无需等待。"
                                + "可继续处理其他任务。",
                        System.currentTimeMillis() - startTime);
            }

            // 前台模式：阻塞至子代理得出结论。批内并行由 AgentLoop 通过 SubAgentExecutor 编排。
            String conclusion = runSubAgent(prompt, description, token, false);
            return success(
                    conclusion == null || conclusion.isBlank()
                            ? "子Agent已结束，但未产出文本结论。" : conclusion,
                    System.currentTimeMillis() - startTime);
        } catch (com.thoughtcoding.task.TaskPersistenceException e) {
            throw e;
        } catch (com.thoughtcoding.core.CancelledException e) {
            return error("子Agent已被用户取消", System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            // 工具自身保留业务兜底；Dispatcher 还会提供统一异常边界，双重保证主轮次工具配对。
            return error("subAgent 执行失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private String runSubAgent(String prompt, String description,
                               com.thoughtcoding.core.CancelToken token, boolean background) {
        try (var trace = Telemetry.observation("agent.subagent", "agent", prompt);
             var scope = trace.activate()) {
            trace.metadata("label", description);
            trace.metadata("subagent_id", java.util.UUID.randomUUID().toString());
            trace.metadata("background", background);
            try {
                String result = runSubAgentInternal(prompt, description, token);
                trace.output(result);
                if (token != null && token.isCancelled()) trace.metadata("status", "cancelled");
                return result;
            } catch (RuntimeException error) { trace.error(error); throw error; }
        }
    }

    private String runSubAgentInternal(String prompt, String description,
                                      com.thoughtcoding.core.CancelToken token) {
        if (!context.getAppConfig().getAi().isSubagentWorktreeIsolation()) {
            return new SubAgent(context).run(prompt, description, token);
        }

        WorktreeManager manager = new WorktreeManager(Sandbox.baseWorkspaceRoot());
        WorktreeManager.RunResult result = manager.run(description,
                () -> new SubAgent(context).run(prompt, description, token));
        return result.combinedOutput();
    }
}
