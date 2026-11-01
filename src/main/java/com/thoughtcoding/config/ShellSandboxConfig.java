package com.thoughtcoding.config;

import java.util.List;

/** Shell-only sandbox. Unknown fields and invalid values must fail configuration loading. */
public class ShellSandboxConfig {
    private String mode = "off";
    private String runtimePath = "~/.local/share/thoughtcoding/srt/node_modules/@anthropic-ai/sandbox-runtime/dist/cli.js";
    private String nodePath = "/usr/bin/node";
    private int maxTimeoutSeconds = 100;
    private List<String> allowedDomains = List.of();
    private List<String> deniedDomains = List.of();
    private List<String> denyRead = List.of();
    private List<String> denyWrite = List.of();
    private List<String> extraWritePaths = List.of();

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getRuntimePath() { return runtimePath; }
    public void setRuntimePath(String runtimePath) { this.runtimePath = runtimePath; }
    public String getNodePath() { return nodePath; }
    public void setNodePath(String nodePath) { this.nodePath = nodePath; }
    public int getMaxTimeoutSeconds() { return maxTimeoutSeconds; }
    public void setMaxTimeoutSeconds(int seconds) { this.maxTimeoutSeconds = seconds; }
    public List<String> getAllowedDomains() { return allowedDomains; }
    public void setAllowedDomains(List<String> values) { this.allowedDomains = values; }
    public List<String> getDeniedDomains() { return deniedDomains; }
    public void setDeniedDomains(List<String> values) { this.deniedDomains = values; }
    public List<String> getDenyRead() { return denyRead; }
    public void setDenyRead(List<String> values) { this.denyRead = values; }
    public List<String> getDenyWrite() { return denyWrite; }
    public void setDenyWrite(List<String> values) { this.denyWrite = values; }
    public List<String> getExtraWritePaths() { return extraWritePaths; }
    public void setExtraWritePaths(List<String> values) { this.extraWritePaths = values; }

    public void validate() {
        if (!"off".equals(mode) && !"required".equals(mode)) {
            throw new IllegalArgumentException("sandbox.mode 必须为 off 或 required");
        }
        if (runtimePath == null || runtimePath.isBlank() || nodePath == null || nodePath.isBlank()) {
            throw new IllegalArgumentException("sandbox.runtimePath/nodePath 不能为空");
        }
        if (maxTimeoutSeconds < 1 || maxTimeoutSeconds > 100) {
            throw new IllegalArgumentException("sandbox.maxTimeoutSeconds 必须在 1..100（预留 Dispatcher 清理时间）");
        }
        for (List<String> values : List.of(nonNull(allowedDomains), nonNull(deniedDomains),
                nonNull(denyRead), nonNull(denyWrite), nonNull(extraWritePaths))) {
            if (values.stream().anyMatch(v -> v == null || v.isBlank() || v.indexOf('\0') >= 0)) {
                throw new IllegalArgumentException("sandbox 列表不能包含空值");
            }
        }
    }

    private static List<String> nonNull(List<String> values) {
        if (values == null) throw new IllegalArgumentException("sandbox 列表不能为 null");
        return values;
    }
}
