package com.thoughtcoding.execution;

import com.thoughtcoding.core.CancelToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

/** Compatibility mode; deliberately provides no OS sandbox. */
public final class LocalCommandExecutor implements CommandExecutor {
    @Override
    public Result execute(String command, Path workspace, int seconds, CancelToken token) throws Exception {
        ProcessBuilder builder;
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            String script = "$ProgressPreference = 'SilentlyContinue'; "
                    + "$ErrorActionPreference = 'Stop'; "
                    + "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
                    + "$global:LASTEXITCODE = 0; & { " + command + " }; "
                    + "if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }";
            builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-EncodedCommand",
                    Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)));
        } else {
            builder = new ProcessBuilder("/bin/sh", "-c", command);
        }
        builder.directory(workspace.toFile());
        builder.environment().keySet().removeIf(key -> {
            String upper = key.toUpperCase(Locale.ROOT);
            return upper.contains("API_KEY") || upper.contains("TOKEN") || upper.contains("SECRET")
                    || upper.contains("PASSWORD") || upper.contains("PASSWD") || upper.contains("CREDENTIAL");
        });
        return ProcessRunner.run(builder, seconds, token);
    }
}
