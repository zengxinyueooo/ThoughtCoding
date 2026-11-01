package com.thoughtcoding.execution;

import com.thoughtcoding.core.CancelToken;
import com.thoughtcoding.core.CancelledException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ProcessRunnerTest {
    @TempDir Path directory;

    @Test void boundsHugeOutputWithoutNewlinesAndPreservesExitCode() throws Exception {
        var result = ProcessRunner.run(command("output"), 15, null);
        assertEquals(7, result.exitCode());
        assertTrue(result.truncated());
        assertEquals(ProcessRunner.MAX_OUTPUT_CHARS, result.output().length());
    }

    @Test void timeoutStopsProcessTree() throws Exception {
        Path marker = directory.resolve("timeout pids.txt");
        assertThrows(TimeoutException.class, () -> ProcessRunner.run(command("tree", marker.toString()), 2, null));
        assertTreeDead(marker);
    }

    @Test void cancellationStopsProcessTree() throws Exception {
        Path marker = directory.resolve("cancel pids.txt");
        CancelToken token = new CancelToken();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> future = executor.submit(() -> {
                assertThrows(CancelledException.class, () -> ProcessRunner.run(command("tree", marker.toString()), 30, token));
            });
            awaitMarker(marker);
            token.cancel();
            future.get(10, TimeUnit.SECONDS);
        }
        assertTreeDead(marker);
    }

    @Test void dispatcherStyleInterruptionAlsoCleansUp() throws Exception {
        Path marker = directory.resolve("interrupt pids.txt");
        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                assertThrows(InterruptedException.class,
                        () -> ProcessRunner.run(command("tree", marker.toString()), 30, null));
            } catch (Throwable error) {
                failure.set(error);
            } finally { done.countDown(); }
        });
        awaitMarker(marker);
        worker.interrupt();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        assertTreeDead(marker);
    }

    private static ProcessBuilder command(String... args) {
        List<String> argv = new ArrayList<>();
        argv.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        argv.add("-cp");
        argv.add(Path.of("target/test-classes").toAbsolutePath().toString());
        argv.add(Child.class.getName());
        argv.addAll(List.of(args));
        return new ProcessBuilder(argv);
    }

    private static void awaitMarker(Path marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(Files.exists(marker), "helper must start before cancellation");
    }

    private static void assertTreeDead(Path marker) throws Exception {
        assertTrue(Files.exists(marker));
        for (String value : Files.readAllLines(marker)) {
            long pid = Long.parseLong(value);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(25);
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "process still alive: " + pid);
        }
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("output")) {
                System.out.print("x".repeat(500_000));
                System.exit(7);
            }
            if (args[0].equals("tree")) {
                Process child = command("sleep").inheritIO().start();
                Files.writeString(Path.of(args[1]), ProcessHandle.current().pid() + "\n" + child.pid());
            }
            Thread.sleep(60_000);
        }
    }
}
