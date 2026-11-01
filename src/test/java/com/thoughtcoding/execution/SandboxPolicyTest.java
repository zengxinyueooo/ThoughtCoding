package com.thoughtcoding.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.config.ShellSandboxConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SandboxPolicyTest {
    @TempDir Path directory;

    @Test void policyProtectsCredentialsAndDoesNotShareTaskWriteRoots() throws Exception {
        var config = new ShellSandboxConfig();
        config.setAllowedDomains(List.of("repo.maven.apache.org"));
        SandboxPolicy policy = new SandboxPolicy(config);
        config.setAllowedDomains(List.of("evil.example")); // executor snapshot must not change
        Path task = directory.resolve("task [one]");
        Path mainSecret = Files.writeString(directory.resolve(".env.production"), "fake-secret");
        var root = new ObjectMapper().valueToTree(policy.build(task, directory, directory.resolve("temp"),
                directory.resolve("policy"), directory.resolve("home"), List.of(),
                List.of(directory.resolve("runtime")), directory.resolve("seccomp")));
        assertTrue(root.path("network").path("strictAllowlist").asBoolean());
        assertEquals("repo.maven.apache.org", root.path("network").path("allowedDomains").get(0).asText());
        var writes = root.path("filesystem").path("allowWrite");
        assertEquals(2, writes.size());
        assertEquals(task.toString(), writes.get(0).path("path").asText());
        assertTrue(writes.get(0).path("literal").asBoolean());
        assertTrue(root.path("filesystem").path("denyRead").toString().contains("config.yaml"));
        assertTrue(root.path("filesystem").path("denyRead").toString().contains(".ssh"));
        assertTrue(root.path("filesystem").path("denyRead").findValuesAsText("path").contains(mainSecret.toString()));
        assertTrue(root.path("filesystem").path("denyWrite").toString().contains("/tmp/claude"));
        assertFalse(root.path("allowNestedUserNamespaces").asBoolean());
        // A fixture for checking the generated contract against the pinned npm package's real schema.
        Files.createDirectories(Path.of("target"));
        new ObjectMapper().writeValue(Path.of("target/sandbox-policy-fixture.json").toFile(), root);
    }

    @Test void unsupportedPlatformOrMissingRuntimeNeverCreatesLocalExecutor() {
        var config = new ShellSandboxConfig();
        config.setMode("required");
        config.setRuntimePath(directory.resolve("missing/dist/cli.js").toString());
        assertThrows(IllegalStateException.class, () -> CommandExecutors.create(config));
    }
}
