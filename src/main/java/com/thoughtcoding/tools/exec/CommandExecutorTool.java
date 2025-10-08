// src/main/java/com/thoughtcoding/tools/exec/CommandExecutorTool.java
package com.thoughtcoding.tools.exec;

import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.tools.BaseTool;
import com.thoughtcoding.model.ToolResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class CommandExecutorTool extends BaseTool {
    private final Set<String> allowedCommands;

    public CommandExecutorTool(AppConfig appConfig) {
        super("command_executor", "Execute system commands safely");

        if (appConfig.getTools().getCommandExec().getAllowedCommands() != null) {
            this.allowedCommands = new HashSet<>(Arrays.asList(appConfig.getTools().getCommandExec().getAllowedCommands()));
        } else {
            // 默认允许的命令
            this.allowedCommands = Set.of("ls", "pwd", "cat", "grep", "find", "echo", "which", "where");
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
}