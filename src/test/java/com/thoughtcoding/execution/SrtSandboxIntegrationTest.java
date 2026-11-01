package com.thoughtcoding.execution;

import com.sun.net.httpserver.HttpServer;
import com.thoughtcoding.config.ShellSandboxConfig;
import com.thoughtcoding.core.CancelToken;
import com.thoughtcoding.core.CancelledException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, real OS acceptance tests. Enabling on an unsupported host FAILS rather than skips. */
@EnabledIfEnvironmentVariable(named = "THOUGHTCODING_SANDBOX_INTEGRATION", matches = "true")
class SrtSandboxIntegrationTest {
    @TempDir Path root;

    private ShellSandboxConfig config() {
        ShellSandboxConfig config = new ShellSandboxConfig();
        config.setMode("required");
        if (System.getenv("THOUGHTCODING_SRT_PATH") != null) config.setRuntimePath(System.getenv("THOUGHTCODING_SRT_PATH"));
        if (System.getenv("THOUGHTCODING_NODE_PATH") != null) config.setNodePath(System.getenv("THOUGHTCODING_NODE_PATH"));
        return config;
    }

    @Test void buildsJavaAndEnforcesPathsIncludingSymlink() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace with spaces [one]"));
        Path outside = Files.writeString(root.resolve("outside.txt"), "keep");
        Files.createSymbolicLink(workspace.resolve("escape"), outside);
        Files.writeString(workspace.resolve("config.yaml"), "fake-secret");
        Files.writeString(workspace.resolve(".env"), "FAKE_SECRET=not-a-real-key");
        Files.writeString(workspace.resolve("Hello.java"),
                "class Hello { public static void main(String[] a) { System.out.print(\"sandbox-ok\"); } }");
        var executor = new SrtCommandExecutor(config(), workspace);
        var build = executor.execute("javac Hello.java && java Hello", workspace, 20, null);
        assertEquals(0, build.exitCode(), build.output());
        assertTrue(build.output().contains("sandbox-ok"));
        var temporary = executor.execute("f=$(mktemp); printf temp-ok > \"$f\"; cat \"$f\"", workspace, 15, null);
        assertEquals(0, temporary.exitCode(), temporary.output());
        assertTrue(temporary.output().contains("temp-ok"));
        assertNotEquals(0, executor.execute("printf changed > ../outside.txt", workspace, 15, null).exitCode());
        assertNotEquals(0, executor.execute("printf changed > escape", workspace, 15, null).exitCode());
        assertEquals("keep", Files.readString(outside));
        assertNotEquals(0, executor.execute("cat config.yaml", workspace, 15, null).exitCode());
        assertNotEquals(0, executor.execute("cat .env", workspace, 15, null).exitCode());
    }

    @Test void networkMustPassThroughExplicitlyAllowedProxyTarget() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("network"));
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = "allowed-response".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        try {
            String destination = "127.0.0.1:" + server.getAddress().getPort();
            String curl = "/usr/bin/curl --fail --silent --show-error --connect-timeout 2 --max-time 4 ";
            var blocked = new SrtCommandExecutor(config(), workspace);
            assertNotEquals(0, blocked.execute(curl + "http://" + destination, workspace, 15, null).exitCode());
            assertEquals(0, requests.get());
            var config = config();
            config.setAllowedDomains(List.of(destination));
            var allowed = new SrtCommandExecutor(config, workspace);
            var result = allowed.execute(curl + "http://" + destination, workspace, 15, null);
            assertEquals(0, result.exitCode(), result.output());
            assertTrue(result.output().contains("allowed-response"));
            assertNotEquals(0, allowed.execute(curl + "--noproxy '*' http://" + destination, workspace, 15, null).exitCode());
            assertEquals(1, requests.get(), "bypassing proxy must never reach host server");
        } finally { server.stop(0); }
    }

    @Test void simultaneousWorkspacesCannotWriteEachOther() throws Exception {
        Path first = Files.createDirectory(root.resolve("first"));
        Path second = Files.createDirectory(root.resolve("second"));
        var executor = new SrtCommandExecutor(config(), root);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CommandExecutor.Result> a = pool.submit(() -> executor.execute("printf a > own; printf bad > ../second/leak", first, 15, null));
            Future<CommandExecutor.Result> b = pool.submit(() -> executor.execute("printf b > own; printf bad > ../first/leak", second, 15, null));
            assertNotEquals(0, a.get(25, TimeUnit.SECONDS).exitCode());
            assertNotEquals(0, b.get(25, TimeUnit.SECONDS).exitCode());
        }
        assertEquals("a", Files.readString(first.resolve("own")));
        assertEquals("b", Files.readString(second.resolve("own")));
        assertFalse(Files.exists(first.resolve("leak")));
        assertFalse(Files.exists(second.resolve("leak")));
    }

    @Test void subWorkspaceCannotReadMainSecretsOrChangeGitMetadata() throws Exception {
        Path main = Files.createDirectory(root.resolve("main"));
        Path child = Files.createDirectory(root.resolve("child"));
        Files.writeString(main.resolve(".env.production"), "fake-main-secret");
        Files.writeString(main.resolve("config.yaml"), "fake-config-secret");
        Path git = Files.createDirectory(child.resolve(".git"));
        Path head = Files.writeString(git.resolve("HEAD"), "original");
        var executor = new SrtCommandExecutor(config(), main);
        assertNotEquals(0, executor.execute("cat ../main/.env.production", child, 15, null).exitCode());
        assertNotEquals(0, executor.execute("cat ../main/config.yaml", child, 15, null).exitCode());
        assertNotEquals(0, executor.execute("printf changed > .git/HEAD", child, 15, null).exitCode());
        assertEquals("original", Files.readString(head));
        var environment = executor.execute(
                "test -z \"${NODE_OPTIONS-}${BASH_ENV-}${LD_PRELOAD-}${OPENAI_API_KEY-}${ANTHROPIC_API_KEY-}\"",
                child, 15, null);
        assertEquals(0, environment.exitCode(), environment.output());
    }

    @Test void cancellationKillsSandboxChildrenBeforeTheyCanWriteLater() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("cancel"));
        var executor = new SrtCommandExecutor(config(), workspace);
        CancelToken token = new CancelToken();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> running = pool.submit(() -> assertThrows(CancelledException.class,
                    () -> executor.execute("touch started; (sleep 4; touch leaked) & wait", workspace, 20, token)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(workspace.resolve("started")) && System.nanoTime() < deadline) Thread.sleep(25);
            assertTrue(Files.exists(workspace.resolve("started")));
            token.cancel();
            running.get(10, TimeUnit.SECONDS);
        }
        Thread.sleep(4500);
        assertFalse(Files.exists(workspace.resolve("leaked")));
    }

    @Test void timeoutAndNormalExitDoNotLeaveBackgroundChildrenRunning() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("lifecycle"));
        var executor = new SrtCommandExecutor(config(), workspace);
        assertThrows(TimeoutException.class,
                () -> executor.execute("(sleep 4; touch timeout-leak) & wait", workspace, 2, null));
        var result = executor.execute("(sleep 4; touch exit-leak) & exit 0", workspace, 15, null);
        assertEquals(0, result.exitCode(), result.output());
        Thread.sleep(4500);
        assertFalse(Files.exists(workspace.resolve("timeout-leak")));
        assertFalse(Files.exists(workspace.resolve("exit-leak")));
    }
}
