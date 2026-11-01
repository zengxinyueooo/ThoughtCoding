package com.thoughtcoding.execution;

import com.thoughtcoding.core.CancelToken;
import java.nio.file.Path;

public interface CommandExecutor {
    Result execute(String command, Path workspace, int timeoutSeconds, CancelToken token) throws Exception;

    record Result(int exitCode, String output, boolean truncated) {}
}
