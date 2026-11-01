package com.thoughtcoding.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.ShellSandboxConfig;
import com.thoughtcoding.core.CancelToken;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Linux/WSL2 adapter for the pinned SRT CLI. There is intentionally no local fallback. */
public final class SrtCommandExecutor implements CommandExecutor {
    public static final String VERSION = "0.0.81";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path runtime;
    private final Path node;
    private final Path seccomp;
    private final Path home;
    private final Path baseWorkspace;
    private final List<Path> extraWrites;
    private final List<Path> protectedPaths;
    private final SandboxPolicy policy;
    private final Map<String, String> environment;
    private final int maxTimeout;

    public SrtCommandExecutor(ShellSandboxConfig config, Path baseWorkspace) throws Exception {
        config.validate();
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) {
            throw new IllegalStateException("第一版仅支持 Linux/WSL2；请在 WSL2 内启动整个 Java 应用");
        }
        this.home = Path.of(System.getProperty("user.home")).toRealPath();
        this.baseWorkspace = baseWorkspace.toRealPath();
        this.runtime = absolute(config.getRuntimePath()).toRealPath();
        this.node = executable(absolute(config.getNodePath()));
        Path packageRoot = runtime.getParent().getParent();
        if (!runtime.endsWith(Path.of("dist", "cli.js"))) {
            throw new IOException("sandbox.runtimePath 必须指向已安装包的 dist/cli.js");
        }
        var manifest = JSON.readTree(packageRoot.resolve("package.json").toFile());
        if (!"@anthropic-ai/sandbox-runtime".equals(manifest.path("name").asText())
                || !VERSION.equals(manifest.path("version").asText())) {
            throw new IOException("需要 @anthropic-ai/sandbox-runtime@" + VERSION);
        }
        String arch = switch (System.getProperty("os.arch")) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> throw new IOException("SRT seccomp 不支持当前 CPU 架构");
        };
        this.seccomp = executable(packageRoot.resolve("vendor/seccomp/" + arch + "/apply-seccomp"));
        List<Path> protectedFiles = new ArrayList<>(List.of(node, runtime, seccomp));
        // Protect the whole install tree, including transitive Node dependencies.
        Path installation = packageRoot;
        for (Path p = packageRoot; p != null; p = p.getParent()) {
            if ("node_modules".equals(p.getFileName() == null ? "" : p.getFileName().toString())) {
                installation = p.getParent();
                break;
            }
        }
        protectedFiles.add(installation);
        for (String dependency : List.of("/usr/bin/bwrap", "/usr/bin/socat", "/usr/bin/rg", "/bin/bash", "/bin/sh")) {
            protectedFiles.add(executable(Path.of(dependency)));
        }
        protectedPaths = List.copyOf(protectedFiles);
        List<Path> caches = new ArrayList<>();
        for (String entry : config.getExtraWritePaths()) {
            Path path = absolute(entry).toRealPath();
            if (!Files.isDirectory(path)) throw new IOException("extraWritePaths 必须为已存在的目录");
            validateWriteRoot(path);
            caches.add(path);
        }
        extraWrites = List.copyOf(caches);
        validateWriteRoot(this.baseWorkspace);
        policy = new SandboxPolicy(config);
        maxTimeout = config.getMaxTimeoutSeconds();
        environment = trustedEnvironment();
        Result nodeVersion = ProcessRunner.run(builder(List.of(node.toString(), "--version"), home), 10, null);
        if (nodeVersion.exitCode() != 0 || !supportedNode(nodeVersion.output())) {
            throw new IOException("sandbox.nodePath 需要 Node.js >= 22.12.0");
        }
        // Real startup probe: missing bwrap/user namespaces/seccomp or invalid policy stops startup.
        Result probe = execute(":", this.baseWorkspace, Math.min(15, maxTimeout), null);
        if (probe.exitCode() != 0) throw new IOException("SRT 启动探测失败: " + probe.output());
    }

    @Override
    public Result execute(String command, Path workspace, int timeoutSeconds, CancelToken token) throws Exception {
        if (token != null) token.check();
        // SRT can otherwise warn and weaken Unix-socket protection when this helper disappears.
        executable(seccomp);
        Path cwd = workspace.toRealPath();
        validateWriteRoot(cwd);
        Path policyDir = null;
        Path temp = null;
        try {
            // Keep policy outside writable roots, including /tmp. Never use a repository-owned settings file.
            policyDir = Files.createTempDirectory(home, ".thoughtcoding-srt-policy-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            for (Path write : extraWrites) {
                if (policyDir.startsWith(write)) throw new IOException("策略目录位于允许写入范围内");
            }
            if (policyDir.startsWith(cwd)) throw new IOException("workspace 不得覆盖策略目录");
            temp = Files.createTempDirectory(Path.of("/tmp"), "thoughtcoding-srt-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path settings = policyDir.resolve("policy.json");
            JSON.writeValue(settings.toFile(), policy.build(cwd, baseWorkspace, temp, policyDir,
                    home, extraWrites, protectedPaths, seccomp));
            Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString("rw-------"));
            // -c accepts one command argument. No host shell ever interprets command or path strings.
            ProcessBuilder process = builder(List.of(node.toString(), runtime.toString(),
                    "--settings", settings.toString(), "-c", command), cwd);
            process.environment().put("TMPDIR", temp.toString());
            process.environment().put("TMP", temp.toString());
            process.environment().put("TEMP", temp.toString());
            // SRT rewrites the child's TMPDIR from this variable, not the launcher's TMPDIR.
            process.environment().put("CLAUDE_CODE_TMPDIR", temp.toString());
            return ProcessRunner.run(process, Math.min(timeoutSeconds, maxTimeout), token);
        } finally {
            // Delete only the directories allocated above; walkFileTree does not follow symbolic links.
            try {
                if (temp != null) deleteTempTree(temp);
            } finally {
                if (policyDir != null) {
                    Files.deleteIfExists(policyDir.resolve("policy.json"));
                    Files.deleteIfExists(policyDir);
                }
            }
        }
    }

    private ProcessBuilder builder(List<String> command, Path cwd) {
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        return builder;
    }

    private Map<String, String> trustedEnvironment() throws IOException {
        // Do not pass NODE_OPTIONS, BASH_ENV, LD_PRELOAD, inherited proxies, or credential variables
        // to the unsandboxed launcher. Toolchains remain accessible through a filtered PATH.
        Map<String, String> env = new HashMap<>();
        env.put("HOME", home.toString());
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        env.put("SHELL", "/bin/bash");
        LinkedHashSet<String> paths = new LinkedHashSet<>(List.of(node.getParent().toString(), "/usr/bin", "/bin"));
        for (String value : System.getenv().getOrDefault("PATH", "").split(":")) {
            if (value.isBlank() || !Path.of(value).isAbsolute() || !Files.isDirectory(Path.of(value))) continue;
            Path path = Path.of(value).toRealPath();
            if (!path.startsWith(baseWorkspace) && extraWrites.stream().noneMatch(path::startsWith)) paths.add(path.toString());
        }
        env.put("PATH", String.join(":", paths));
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && Path.of(javaHome).isAbsolute() && Files.isDirectory(Path.of(javaHome))) {
            Path path = Path.of(javaHome).toRealPath();
            if (!path.startsWith(baseWorkspace) && extraWrites.stream().noneMatch(path::startsWith)) env.put("JAVA_HOME", path.toString());
        }
        return Map.copyOf(env);
    }

    private void validateWriteRoot(Path path) throws IOException {
        if (home.startsWith(path) || Path.of("/tmp").startsWith(path)
                || protectedPaths.stream().anyMatch(p -> p.startsWith(path))) {
            throw new IOException("允许写入的目录覆盖 HOME、系统临时目录或沙箱运行组件: " + path);
        }
    }

    private Path absolute(String value) throws IOException {
        String expanded = value.startsWith("~/") ? home + value.substring(1) : value;
        Path path = Path.of(expanded);
        if (!path.isAbsolute()) throw new IOException("沙箱运行组件和额外写目录必须使用绝对路径或 ~/ 路径");
        return path.normalize();
    }

    private static Path executable(Path value) throws IOException {
        Path path = value.toRealPath();
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) throw new IOException("缺少可执行依赖: " + value);
        return path;
    }

    private static boolean supportedNode(String value) {
        try {
            String[] parts = value.strip().replaceFirst("^v", "").split("\\.");
            int major = Integer.parseInt(parts[0]);
            return major > 22 || (major == 22 && Integer.parseInt(parts[1]) >= 12);
        } catch (RuntimeException e) { return false; }
    }

    private static void deleteTempTree(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(Path.of("/tmp"))
                || !normalized.getFileName().toString().startsWith("thoughtcoding-srt-")) {
            throw new IOException("拒绝清理不属于本次沙箱的目录");
        }
        Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
