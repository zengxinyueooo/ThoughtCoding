package com.thoughtcoding.mcp;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MCPClientTest {

    @Test
    void toolBusinessErrorMustNotBecomeSuccessfulContent() throws Exception {
        MCPClient client = new MCPClient("synthetic");
        for (var entry : java.util.Map.<String, Object>of(
                "initialized", true,
                "reader", new java.io.BufferedReader(new java.io.StringReader(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"test\",\"result\":{\"isError\":true,"
                                + "\"content\":[{\"type\":\"text\",\"text\":\"failed\"}]}}\n")),
                "writer", new java.io.BufferedWriter(new java.io.StringWriter())).entrySet()) {
            var field = MCPClient.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(client, entry.getValue());
        }
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> client.callTool("test", java.util.Map.of()));
    }

    @Test
    void resolvesNpxLauncherOnWindowsWhenItIsInstalled() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }

        String resolved = MCPClient.resolveExecutable("npx");

        assertTrue(resolved.toLowerCase(Locale.ROOT).endsWith("npx.cmd"),
                () -> "Expected npx.cmd but resolved: " + resolved);
    }
}
