package com.thoughtcoding.execution;

import com.thoughtcoding.config.ShellSandboxConfig;
import com.thoughtcoding.security.Sandbox;

public final class CommandExecutors {
    private CommandExecutors() {}

    public static CommandExecutor create(ShellSandboxConfig config) {
        config.validate();
        if ("off".equals(config.getMode())) return new LocalCommandExecutor();
        try {
            return new SrtCommandExecutor(config, Sandbox.baseWorkspaceRoot());
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Shell 沙箱不可用，拒绝降级执行：" + error.getMessage(), error);
        }
    }
}
