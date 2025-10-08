// src/main/java/com/thoughtcoding/ThoughtCodingCLI.java
package com.thoughtcoding;

import com.thoughtcoding.cli.ThoughtCodingCommand;
import com.thoughtcoding.cli.SessionCommand;
import com.thoughtcoding.cli.ConfigCommand;
import com.thoughtcoding.core.ThoughtCodingContext;
import picocli.CommandLine;

public class ThoughtCodingCLI {
    public static void main(String[] args) {
        // 设置默认异常处理
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            System.err.println("💥 发生未预期错误: " + throwable.getMessage());
            System.exit(1);
        });

        // 初始化应用上下文
        ThoughtCodingContext context = ThoughtCodingContext.initialize();

        // 注册所有命令
        CommandLine commandLine = new CommandLine(new ThoughtCodingCommand(context));
        commandLine.addSubcommand("session", new SessionCommand(context));
        commandLine.addSubcommand("config", new ConfigCommand(context));

        // 执行命令
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }
}