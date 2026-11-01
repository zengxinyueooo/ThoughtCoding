package com.thoughtcoding.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ShellSandboxConfigTest {
    @TempDir Path directory;

    private AppConfig load(String yaml) throws Exception {
        Path file = directory.resolve("config.yaml");
        Files.writeString(file, yaml);
        return new ConfigLoader().loadConfig(file.toString());
    }

    @Test void legacyConfigKeepsSandboxOff() throws Exception {
        assertEquals("off", load("defaultModel: test\n").getSandbox().getMode());
    }

    @Test void loadsRequiredAndLists() throws Exception {
        var config = load("""
                sandbox:
                  mode: required
                  allowedDomains: [repo.maven.apache.org]
                  extraWritePaths: [~/.m2/repository]
                """);
        assertEquals("required", config.getSandbox().getMode());
        assertEquals("repo.maven.apache.org", config.getSandbox().getAllowedDomains().getFirst());
    }

    @Test void invalidPolicyNeverFallsBackToOff() {
        for (String yaml : new String[]{
                "sandbox: null", "sandbox: {}\nsandbox: {mode: off}", "sandbox: {mode: null}",
                "sandbox: {mode: requird}", "sandbox: {mode: required, allowedDomain: [example.com]}",
                "sandbox: {mode: required, allowedDomains: null}",
                "sandbox: {mode: required, maxTimeoutSeconds: 0}",
                "sandbox: {mode: required, maxTimeoutSeconds: 101}",
                "sandbox: {mode: required, maxTimeoutSeconds: wrong}", "sandbox: [", ""}) {
            assertThrows(IllegalArgumentException.class, () -> load(yaml), yaml);
        }
    }

    @Test void parseFailureDoesNotEchoCredentialValues() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> load("models: {test: {apiKey: do-not-print-this}}\nsandbox: ["));
        assertFalse(error.getMessage().contains("do-not-print-this"));
        assertNull(error.getCause());
    }
}
