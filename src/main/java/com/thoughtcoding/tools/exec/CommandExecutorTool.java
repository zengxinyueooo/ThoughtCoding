package com.thoughtcoding.tools.exec;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.tools.BaseTool;
import com.thoughtcoding.model.ToolResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 命令执行工具，允许执行预定义的系统命令
 */
public class CommandExecutorTool extends BaseTool {
    private final Set<String> allowedCommands;
    private final AppConfig appConfig;

    public CommandExecutorTool(AppConfig appConfig) {
        super("command_executor", "Execute system commands safely");
        this.appConfig = appConfig;

        if (appConfig.getTools().getCommandExec().getAllowedCommands() != null) {
            this.allowedCommands = new HashSet<>(Arrays.asList(appConfig.getTools().getCommandExec().getAllowedCommands()));
        } else {
            // 扩展的默认允许命令 - 支持更多开发和系统命令
            this.allowedCommands = Set.of(
                // 基础系统命令
                "ls", "pwd", "cat", "grep", "find", "echo", "which", "where", "whoami", "date", "uname",
                // 文件操作
                "head", "tail", "wc", "sort", "uniq", "diff", "cp", "mv", "mkdir", "rmdir", "rm", "chmod", "chown",
                // 开发工具
                "java", "javac", "python", "python3", "node", "npm", "mvn", "gradle", "gcc", "g++", "make", "cmake",
                // Git命令
                "git", "git-status", "git-log", "git-add", "git-commit", "git-push", "git-pull", "git-branch", "git-checkout", "git-clone", "git-diff",
                // 网络工具
                "ping", "curl", "wget", "ssh", "scp", "rsync", "telnet", "netstat", "lsof",
                // 系统管理
                "ps", "top", "htop", "kill", "killall", "df", "du", "free", "uptime", "tar", "gzip", "zip", "unzip",
                // 其他工具
                "man", "help", "history", "clear", "reset", "exit", "tree", "file", "stat", "ln"
            );
        }
    }

    @Override
    public ToolResult execute(String input) {
        long startTime = System.currentTimeMillis();

        try {
            if (input == null || input.trim().isEmpty()) {
                return error("No command provided", System.currentTimeMillis() - startTime);
            }

            String[] commandParts = input.split("\\s+");
            String baseCommand = commandParts[0].toLowerCase();

            // 安全检查
            if (!isCommandAllowed(baseCommand)) {
                return error("Command not allowed: " + baseCommand + ". Allowed commands: " + allowedCommands,
                        System.currentTimeMillis() - startTime);
            }

            // 执行命令
            ProcessBuilder processBuilder = new ProcessBuilder(commandParts);
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            // 读取输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            // 等待进程完成
            int exitCode = process.waitFor();

            String result = output.toString().trim();
            if (exitCode != 0) {
                return error("Command failed with exit code " + exitCode + ":\n" + result,
                        System.currentTimeMillis() - startTime);
            }

            return success(result.isEmpty() ? "Command executed successfully (no output)" : result,
                    System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            return error("Command execution failed: " + e.getMessage(), System.currentTimeMillis() - startTime);
        }
    }

    private boolean isCommandAllowed(String command) {
        return allowedCommands.contains(command.toLowerCase());
    }

    public Set<String> getAllowedCommands() {
        return new HashSet<>(allowedCommands);
    }

    @Override
    public String getCategory() {
        return "exec";
    }

    @Override
    public boolean isEnabled() {
        return appConfig != null && appConfig.getTools().getCommandExec().isEnabled();
    }
}