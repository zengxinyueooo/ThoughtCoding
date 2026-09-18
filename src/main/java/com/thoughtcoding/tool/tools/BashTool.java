package com.thoughtcoding.tool.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.model.ToolResult;
import com.thoughtcoding.security.Sandbox;
import com.thoughtcoding.tool.BaseTool;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * bash 工具：执行任意 shell 命令，返回合并后的 stdout/stderr。
 * 无命令白名单——安全由 AgentLoop 的“执行前确认”把关。
 */
public class BashTool extends BaseTool {
    private final int defaultTimeoutSeconds;
    private static final String SHELL =
            System.getProperty("os.name").toLowerCase().contains("win") ? "PowerShell" : "bash";

    /** stdout 收集上限（字符）：防 `yes` 类命令在超时窗口内撑爆内存。约对应数万 token。 */
    static final int MAX_OUTPUT_CHARS = 50_000;

    public BashTool(AppConfig appConfig) {
        super("bash", "执行任意 " + SHELL + " 命令，返回合并的 stdout/stderr。需要搜索文件内容时也用它（如 grep/rg）。参数：command（必填）、timeout（可选，秒）。");
        Integer t = appConfig.getTools().getBash().getTimeoutSeconds();
        this.defaultTimeoutSeconds = (t == null || t <= 0) ? 60 : t;
    }

    @Override
    public JsonObjectSchema inputSchema() {
        return JsonObjectSchema.builder()
                .addStringProperty("command", "要执行的 shell 命令")
                .addIntegerProperty("timeout", "超时秒数（可选）")
                .required("command")
                .additionalProperties(false)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(String input) {
        return execute(input, null);
    }

    @Override
    public ToolResult execute(String input, com.thoughtcoding.core.CancelToken token) {
        long startTime = System.currentTimeMillis();
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> params = mapper.readValue(input, Map.class);

            Object cmdObj = params.get("command");
            if (cmdObj == null || cmdObj.toString().trim().isEmpty()) {
                return error("bash 需要 'command' 字段", System.currentTimeMillis() - startTime);
            }
            String command = cmdObj.toString();

            int timeoutSeconds = defaultTimeoutSeconds;
            Object t = params.get("timeout");
            if (t instanceof Number && ((Number) t).intValue() > 0) {
                timeoutSeconds = ((Number) t).intValue();
            }

            ProcessBuilder pb;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                String script = "$ProgressPreference = 'SilentlyContinue'; "
                        + "$ErrorActionPreference = 'Continue'; "
                        + "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
                        + "& { " + command + " } 2>&1 | Out-String -Width 300";
                // 转为 UTF-16LE 字节序列
                byte[] bytes = script.getBytes(StandardCharsets.UTF_16LE);
                String encoded = Base64.getEncoder().encodeToString(bytes);
                pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-EncodedCommand", encoded);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }
            // 不修改全局 user.dir；并行 SubAgent 通过 Sandbox 的线程级 workspace
            // 各自在独立 Git worktree 中执行。
            pb.directory(Sandbox.workspaceRoot().toFile());
            pb.redirectErrorStream(true);
            // 环境变量净化：子进程默认继承全部环境，任意命令都能读到宿主 API key 等敏感值。
            // 按名称黑名单剔除密钥类变量（PATH/JAVA_HOME 等不受影响）。
            pb.environment().keySet().removeIf(k -> {
                String u = k.toUpperCase();
                return u.contains("API_KEY") || u.contains("TOKEN") || u.contains("SECRET")
                        || u.contains("PASSWORD") || u.contains("PASSWD") || u.contains("CREDENTIAL");
            });

            Process process = pb.start();

            // 取消传播：token 触发时立即 kill 子进程（bash 进程不响应 Java 中断，必须 destroyForcibly）。
            // 进程死后 waitFor 返回，reader 线程随流关闭自然结束。
            if (token != null) {
                token.onCancel(() -> {
                    process.destroyForcibly();
                });
            }

            // 后台线程消费 stdout，避免主线程因 readLine 阻塞而无法触发超时。
            // 输出封顶：超过 MAX_OUTPUT_CHARS 停止收集（进程继续跑完/超时终止），尾部标注截断。
            StringBuilder output = new StringBuilder();
            final boolean[] truncated = {false};
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (output.length() >= MAX_OUTPUT_CHARS) {
                            truncated[0] = true;
                            continue; // 丢弃后续输出，但继续消费防止管道背压卡死子进程
                        }
                        int room = MAX_OUTPUT_CHARS - output.length();
                        if (line.length() > room) {
                            output.append(line, 0, room).append('\n');
                            truncated[0] = true;
                        } else {
                            output.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                    // 进程被 destroy 后流关闭，忽略
                }
            }, "bash-stdout-reader");
            readerThread.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (token != null && token.isCancelled()) {
                return error("命令已被用户取消", System.currentTimeMillis() - startTime);
            }
            if (!finished) {
                process.destroyForcibly();
                readerThread.interrupt();
                return error("命令超时（>" + timeoutSeconds + "s），已被终止",
                        System.currentTimeMillis() - startTime);
            }

            // 进程已退出，等 reader 线程收完最后几行输出
            readerThread.join(5000);

            int exitCode = process.exitValue();
            String result = output.toString().trim();
            if (truncated[0]) {
                result = result + "\n[输出已截断：仅保留前 " + MAX_OUTPUT_CHARS
                        + " 字符。如需完整输出，请让命令重定向到文件后用 read 分页查看]";
            }
            if (exitCode != 0) {
                return error("命令退出码 " + exitCode + ":\n" + result, System.currentTimeMillis() - startTime);
            }
            return success(result.isEmpty() ? "命令执行成功（无输出）" : result,
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return error("命令执行失败: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }
}
