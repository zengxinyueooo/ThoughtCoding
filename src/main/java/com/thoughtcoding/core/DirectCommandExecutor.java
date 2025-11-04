package com.thoughtcoding.core;

import com.thoughtcoding.tools.exec.CommandExecutorTool;
import com.thoughtcoding.ui.ThoughtCodingUI;
import com.thoughtcoding.model.ToolResult;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 直接命令执行器
 * 负责识别和直接执行系统命令，绕过AI交互
 */
public class DirectCommandExecutor {

    private final ThoughtCodingContext context;
    private final ThoughtCodingUI ui;
    private final CommandExecutorTool commandExecutor;

    // 直接执行的模式匹配
    private static final Map<Pattern, String> DIRECT_COMMANDS = new HashMap<>();

    static {
        // Java相关命令
        DIRECT_COMMANDS.put(Pattern.compile("^java\\s+-?version$", Pattern.CASE_INSENSITIVE), "java -version");
        DIRECT_COMMANDS.put(Pattern.compile("^javac\\s+-?version$", Pattern.CASE_INSENSITIVE), "javac -version");
        DIRECT_COMMANDS.put(Pattern.compile("^java\\s+.*\\.jar$", Pattern.CASE_INSENSITIVE), null); // 运行jar文件

        // Git命令
        DIRECT_COMMANDS.put(Pattern.compile("^git\\s+status$", Pattern.CASE_INSENSITIVE), "git status");
        DIRECT_COMMANDS.put(Pattern.compile("^git\\s+log(?:\\s+-\\d+)?$", Pattern.CASE_INSENSITIVE), null); // git log
        DIRECT_COMMANDS.put(Pattern.compile("^git\\s+add\\s+.*", Pattern.CASE_INSENSITIVE), null); // git add
        DIRECT_COMMANDS.put(Pattern.compile("^git\\s+commit(?:\\s+-m\\s+.+)?$", Pattern.CASE_INSENSITIVE), null); // git commit
        DIRECT_COMMANDS.put(Pattern.compile("^git\\s+push$", Pattern.CASE_INSENSITIVE), "git push");
        DIRECT_COMMANDS.put(Pattern.compile("^git\\s+pull$", Pattern.CASE_INSENSITIVE), "git pull");
        DIRECT_COMMANDS.put(Pattern.compile("^git\\s+branch$", Pattern.CASE_INSENSITIVE), "git branch");
        DIRECT_COMMANDS.put(Pattern.compile("^git\\s+checkout\\s+.+", Pattern.CASE_INSENSITIVE), null); // git checkout

        // 系统信息命令
        DIRECT_COMMANDS.put(Pattern.compile("^pwd$", Pattern.CASE_INSENSITIVE), "pwd");
        DIRECT_COMMANDS.put(Pattern.compile("^whoami$", Pattern.CASE_INSENSITIVE), "whoami");
        DIRECT_COMMANDS.put(Pattern.compile("^date$", Pattern.CASE_INSENSITIVE), "date");
        DIRECT_COMMANDS.put(Pattern.compile("^uname(?:\\s+-a)?$", Pattern.CASE_INSENSITIVE), "uname");
        DIRECT_COMMANDS.put(Pattern.compile("^ls(?:\\s+-[la]+)?$", Pattern.CASE_INSENSITIVE), "ls");
        DIRECT_COMMANDS.put(Pattern.compile("^dir$", Pattern.CASE_INSENSITIVE), "dir");

        // 文件操作命令
        DIRECT_COMMANDS.put(Pattern.compile("^cat\\s+.+", Pattern.CASE_INSENSITIVE), null); // cat file
        DIRECT_COMMANDS.put(Pattern.compile("^head\\s+.+", Pattern.CASE_INSENSITIVE), null); // head file
        DIRECT_COMMANDS.put(Pattern.compile("^tail\\s+.+", Pattern.CASE_INSENSITIVE), null); // tail file
        DIRECT_COMMANDS.put(Pattern.compile("^find\\s+.+", Pattern.CASE_INSENSITIVE), null); // find

        // 网络命令
        DIRECT_COMMANDS.put(Pattern.compile("^ping\\s+.+", Pattern.CASE_INSENSITIVE), null); // ping
        DIRECT_COMMANDS.put(Pattern.compile("^curl\\s+.+", Pattern.CASE_INSENSITIVE), null); // curl
        DIRECT_COMMANDS.put(Pattern.compile("^wget\\s+.+", Pattern.CASE_INSENSITIVE), null); // wget
    }

    // 需要确认的敏感命令
    private static final Set<String> CONFIRM_REQUIRED_COMMANDS = Set.of(
        "git push", "git pull", "git commit", "rm -rf", "sudo"
    );

    public DirectCommandExecutor(ThoughtCodingContext context) {
        this.context = context;
        this.ui = context.getUi();
        this.commandExecutor = new CommandExecutorTool(context.getAppConfig());
    }

    /**
     * 检查输入是否应该直接执行
     */
    public boolean shouldExecuteDirectly(String input) {
        String trimmedInput = input.trim();

        // 检查是否匹配直接命令模式
        for (Pattern pattern : DIRECT_COMMANDS.keySet()) {
            if (pattern.matcher(trimmedInput).matches()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 直接执行命令
     * @return true表示已执行，false表示需要AI处理
     */
    public boolean executeDirectCommand(String input) {
        String trimmedInput = input.trim();

        // 查找匹配的命令模式
        for (Map.Entry<Pattern, String> entry : DIRECT_COMMANDS.entrySet()) {
            if (entry.getKey().matcher(trimmedInput).matches()) {
                String command = entry.getValue() != null ? entry.getValue() : trimmedInput;

                // 检查是否需要确认
                if (requiresConfirmation(command)) {
                    if (!askForConfirmation(command)) {
                        ui.displayInfo("命令执行已取消");
                        return true; // 用户取消，也算直接处理了
                    }
                }

                // 执行命令
                executeCommand(command);
                return true;
            }
        }

        return false; // 没有匹配的模式，交给AI处理
    }

    /**
     * 执行命令
     */
    private void executeCommand(String command) {
        ui.displayInfo("🔧 直接执行命令: " + command);

        try {
            ToolResult result = commandExecutor.execute(command);

            if (result.isSuccess()) {
                ui.displaySuccess("✅ 命令执行成功");
                if (!result.getOutput().isEmpty()) {
                    ui.displayInfo("输出:\n" + result.getOutput());
                }
            } else {
                ui.displayError("❌ 命令执行失败: " + result.getOutput());
            }

            // 显示执行时间
            if (result.getExecutionTime() > 0) {
                ui.displayInfo("⏱️  执行时间: " + result.getExecutionTime() + "ms");
            }

        } catch (Exception e) {
            ui.displayError("❌ 命令执行异常: " + e.getMessage());
        }
    }

    /**
     * 检查命令是否需要用户确认
     */
    private boolean requiresConfirmation(String command) {
        String lowerCommand = command.toLowerCase();
        for (String sensitiveCmd : CONFIRM_REQUIRED_COMMANDS) {
            if (lowerCommand.startsWith(sensitiveCmd)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 请求用户确认
     */
    private boolean askForConfirmation(String command) {
        ui.displayWarning("⚠️  即将执行敏感命令: " + command);
        String response = ui.readInput("确认执行吗? (y/N): ");
        return response != null &&
               (response.equalsIgnoreCase("y") || response.equalsIgnoreCase("yes"));
    }

    /**
     * 获取支持的所有直接命令列表
     */
    public void listSupportedCommands() {
        ui.getTerminal().writer().println("\n🔧 支持直接执行的命令:");
        ui.getTerminal().writer().println("──────────────────────────────────");

        Map<String, List<String>> categorizedCommands = new TreeMap<>();

        for (Pattern pattern : DIRECT_COMMANDS.keySet()) {
            String patternStr = pattern.pattern();
            String category = getCategory(patternStr);

            categorizedCommands.computeIfAbsent(category, k -> new ArrayList<>())
                             .add("  • " + patternStr);
        }

        for (Map.Entry<String, List<String>> entry : categorizedCommands.entrySet()) {
            ui.getTerminal().writer().println("\n" + entry.getKey() + ":");
            entry.getValue().forEach(ui.getTerminal().writer()::println);
        }

        ui.getTerminal().writer().println("\n💡 这些命令会绕过AI直接执行，提供更快的响应");
        ui.getTerminal().writer().flush();
    }

    /**
     * 根据命令模式获取分类
     */
    private String getCategory(String pattern) {
        if (pattern.contains("java")) return "Java 开发";
        if (pattern.contains("git")) return "Git 版本控制";
        if (pattern.contains("pwd|whoami|date|uname|ls|dir")) return "系统信息";
        if (pattern.contains("cat|head|tail|find")) return "文件操作";
        if (pattern.contains("ping|curl|wget")) return "网络工具";
        return "其他";
    }
}
