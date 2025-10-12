package com.thoughtcoding.cli;


import com.thoughtcoding.core.ThoughtCodingContext;
import com.thoughtcoding.service.SessionService;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * SessionCommand类用于管理会话相关的命令行操作
 */
@Command(name = "session", description = "Session management commands")
public class SessionCommand implements Callable<Integer> {

    private final ThoughtCodingContext context;

    @Option(names = {"--list"}, description = "List all sessions")
    private boolean list;

    @Option(names = {"--delete"}, description = "Delete session")
    private String delete;

    @Option(names = {"--info"}, description = "Show session info")
    private String info;

    public SessionCommand(ThoughtCodingContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        SessionService sessionService = context.getSessionService();

        if (list) {
            // 列出所有会话
            return 0;
        }

        if (delete != null) {
            // 删除会话
            return 0;
        }

        if (info != null) {
            // 显示会话信息
            return 0;
        }

        context.getUi().displayInfo("Use --help to see available session commands");
        return 0;
    }
}