package com.thoughtcoding.execution;

import com.thoughtcoding.config.ShellSandboxConfig;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Immutable policy snapshot. Generated paths are literal, never interpreted as globs. */
final class SandboxPolicy {
    private final List<String> allowedDomains;
    private final List<String> deniedDomains;
    private final List<String> denyRead;
    private final List<String> denyWrite;

    SandboxPolicy(ShellSandboxConfig config) {
        config.validate();
        allowedDomains = List.copyOf(config.getAllowedDomains());
        deniedDomains = List.copyOf(config.getDeniedDomains());
        denyRead = List.copyOf(config.getDenyRead());
        denyWrite = List.copyOf(config.getDenyWrite());
    }

    Map<String, Object> build(Path workspace, Path baseWorkspace, Path temp, Path policyDirectory,
                              Path home, List<Path> extraWrites, List<Path> protectedPaths, Path seccomp) throws IOException {
        List<Object> readDenies = new ArrayList<>(denyRead);
        // Globs also cover secrets already present in a repository or its worktree.
        readDenies.addAll(List.of("**/.env", "**/.env.*"));
        for (String relative : List.of(".ssh", ".aws", ".azure", ".config/gcloud", ".kube",
                ".npmrc", ".git-credentials", ".m2/settings.xml", ".gradle/gradle.properties")) {
            readDenies.add(literal(home.resolve(relative)));
        }
        readDenies.add(literal(baseWorkspace.resolve("config.yaml")));
        readDenies.add(literal(workspace.resolve("config.yaml")));
        readDenies.add(literal(policyDirectory));
        // A subagent's relative glob does not cover the main checkout's untracked .env files.
        for (Path root : List.of(baseWorkspace, workspace, home)) {
            readDenies.add(literal(root.resolve(".env")));
            if (Files.isDirectory(root)) {
                try (var entries = Files.newDirectoryStream(root, ".env.*")) {
                    for (Path entry : entries) readDenies.add(literal(entry));
                }
            }
        }

        List<Object> writes = new ArrayList<>();
        writes.add(literal(workspace));
        writes.add(literal(temp));
        extraWrites.forEach(p -> writes.add(literal(p)));

        List<Object> writeDenies = new ArrayList<>(readDenies);
        writeDenies.addAll(denyWrite);
        protectedPaths.forEach(p -> writeDenies.add(literal(p)));
        // Host WorktreeManager owns Git mutations; do not expose shared Git metadata for writing.
        writeDenies.add(literal(baseWorkspace.resolve(".git")));
        writeDenies.add(literal(workspace.resolve(".git")));
        // Override SRT's convenience write grants; each invocation has its own temporary directory.
        writeDenies.addAll(List.of("/tmp/claude", "/private/tmp/claude"));
        writeDenies.add(literal(home.resolve(".npm/_logs")));
        writeDenies.add(literal(home.resolve(".claude")));

        return Map.of(
                "filesystem", Map.of("denyRead", readDenies, "allowWrite", writes, "denyWrite", writeDenies,
                        "allowGitConfig", false),
                "network", Map.of("allowedDomains", allowedDomains, "deniedDomains", deniedDomains,
                        "strictAllowlist", true, "allowAllUnixSockets", false, "allowLocalBinding", false),
                "bwrapPath", "/usr/bin/bwrap", "socatPath", "/usr/bin/socat",
                "ripgrep", Map.of("command", "/usr/bin/rg"),
                "seccomp", Map.of("applyPath", seccomp.toString()),
                "allowNestedUserNamespaces", false,
                "enableWeakerNestedSandbox", false);
    }

    static Map<String, Object> literal(Path path) {
        return Map.of("path", path.toAbsolutePath().normalize().toString(), "literal", true);
    }
}
