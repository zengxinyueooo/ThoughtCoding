package com.thoughtcoding.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 用户可配置的外部命令 Hook（config.yaml 的 hooks 段）——把生命周期事件桥接到任意 shell 命令，
 * 对齐 Claude Code settings.json 的声明式 Hook：自动格式化、通知、自定义校验等无需改代码。
 *
 * <p>配置语法：
 * <pre>
 *   hooks:
 *     PreToolUse:
 *       - matcher: "Write|Edit"      # 工具名正则；缺省匹配该时机全部触发
 *         command: "prettier --write ..."
 *         timeout: 30                # 秒，缺省 30
 *     Stop:
 *       - command: "say done"
 * </pre>
 *
 * <p>命令通过事件 JSON（stdin）接收上下文：{@code {event, tool, toolInput, prompt}}。
 * 退出码语义（对齐 Claude Code）：
 * <ul>
 *   <li>0 → 放行；UserPromptSubmit 时机的 stdout 作为附加上下文注入模型，其余时机展示给用户</li>
 *   <li>2 → 阻断，stderr 作为阻断原因回喂；STOP 时机转为强制续跑</li>
 *   <li>其他 / 超时 / 无法执行 → 降级放行并告警（用户扩展是便利层，不该卡死主对话）</li>
 * </ul>
 *
 * <p>安全边界：命令来自用户本地 config（非对话内容），不存在提示注入面；
 * fail-open 策略与内置权限门（fail-closed）相反——外部 Hook 故障最多丢失扩展功能，不能阻断工作流。
 */
public class ExternalCommandHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ExternalCommandHook.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int DISPLAY_LIMIT = 2000;

    private final String command;
    private final Pattern matcher; // 可空：不筛工具名，该时机全部触发
    private final int timeoutSeconds;

    public ExternalCommandHook(String command, String matcher, Integer timeoutSeconds) {
        this.command = command;
        this.matcher = compileMatcher(matcher);
        this.timeoutSeconds = timeoutSeconds != null && timeoutSeconds > 0
                ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
    }

    private static Pattern compileMatcher(String matcher) {
        if (matcher == null || matcher.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(matcher);
        } catch (Exception e) {
            log.warn("Hook matcher 非正则，按匹配全部处理: {} ({})", matcher, e.getMessage());
            return null;
        }
    }

    @Override
    public String name() {
        return "External(" + (command.length() > 40 ? command.substring(0, 40) + "…" : command) + ")";
    }

    @Override
    public HookResult execute(HookContext context) {
        // matcher 只对携带工具调用的事件有意义；UserPromptSubmit/Stop 恒通过
        if (matcher != null && context.getToolCall() != null) {
            String toolName = context.getToolCall().getToolName();
            if (toolName == null || !matcher.matcher(toolName).matches()) {
                return HookResult.proceed();
            }
        }

        ProcessOutput output = run(buildPayload(context));
        if (output == null) {
            return HookResult.proceed(); // 超时/无法执行 → 已告警，降级放行
        }

        if (output.exitCode() == 0) {
            String stdout = output.stdout().strip();
            if (!stdout.isEmpty()) {
                if (context.getType() == HookType.USER_PROMPT_SUBMIT) {
                    context.injectContext(stdout); // stdout 进入模型上下文（对齐 CC 的 UserPromptSubmit 语义）
                } else if (context.getUi() != null) {
                    context.getUi().displayInfo("[Hook] "
                            + (stdout.length() > DISPLAY_LIMIT ? stdout.substring(0, DISPLAY_LIMIT) + "…" : stdout));
                }
            }
            return HookResult.proceed();
        }

        if (output.exitCode() == 2) {
            String reason = output.stderr().isBlank() ? output.stdout() : output.stderr();
            String message = "⛔ Hook 命令阻断 (exit 2): " + (reason.isBlank() ? command : reason.strip());
            return context.getType() == HookType.STOP
                    ? HookResult.continueLoop(message)  // STOP 语义：不退出、强制续跑
                    : HookResult.block(message);
        }

        log.warn("Hook 命令退出码 {}（降级放行）: {}", output.exitCode(), command);
        return HookResult.proceed();
    }

    /** 事件上下文序列化为 JSON 经 stdin 传给命令。 */
    private String buildPayload(HookContext context) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", context.getType().name());
            ToolCall call = context.getToolCall();
            if (call != null) {
                payload.put("tool", call.getToolName());
                payload.put("toolInput", call.getParameters());
            }
            if (context.getPrompt() != null) {
                payload.put("prompt", context.getPrompt());
            }
            return JSON.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private record ProcessOutput(int exitCode, String stdout, String stderr) {}

    /**
     * 执行命令：stdin 写入事件 JSON，收集 stdout/stderr。
     * Windows 经 cmd /c、其余经 sh -c 执行（与 Bash 工具的平台策略一致）。
     * 超时或启动失败返回 null（调用方降级放行）。
     */
    private ProcessOutput run(String stdinJson) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        ProcessBuilder pb = windows
                ? new ProcessBuilder("cmd.exe", "/c", command)
                : new ProcessBuilder("sh", "-c", command);
        pb.redirectErrorStream(false);
        try {
            Process process = pb.start();
            // stdin 先写后关：命令可能不读 stdin（如 exit 0），写已关闭的管道会抛 IO——预期内，忽略
            try (OutputStream in = process.getOutputStream()) {
                in.write(stdinJson.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // 命令提前退出未消费 stdin
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("Hook 命令超时({}s)，已终止并降级放行: {}", timeoutSeconds, command);
                return null;
            }
            return new ProcessOutput(process.exitValue(), stdout, stderr);
        } catch (Exception e) {
            log.warn("Hook 命令无法执行（降级放行）: {} ({})", command, e.getMessage());
            return null;
        }
    }
}
