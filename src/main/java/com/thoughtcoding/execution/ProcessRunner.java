package com.thoughtcoding.execution;

import com.thoughtcoding.core.CancelToken;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded output and one cleanup path for success, cancellation, timeout and interruption. */
public final class ProcessRunner {
    public static final int MAX_OUTPUT_CHARS = 50_000;

    private ProcessRunner() {}

    public static CommandExecutor.Result run(ProcessBuilder builder, int seconds, CancelToken token)
            throws IOException, InterruptedException, TimeoutException {
        if (token != null) token.check();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        Process process = builder.redirectErrorStream(true).start();
        Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
        Capture capture = new Capture();
        Thread reader = Thread.ofPlatform().daemon(true).name("command-output").start(() -> {
            try (var input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] chunk = new char[4096];
                int count;
                while ((count = input.read(chunk)) != -1) capture.append(chunk, count);
            } catch (IOException ignored) {
                // Streams close during process cleanup.
            }
        });
        try {
            // Commands are non-interactive. Closing stdin also prevents accidental hangs on input.
            process.getOutputStream().close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (true) {
                if (token != null) token.check();
                process.descendants().forEach(p -> descendants.put(p.pid(), p));
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("命令超时（>" + seconds + "s）");
                if (process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)) break;
            }
            if (token != null) token.check();
            reader.join(500);
            return new CommandExecutor.Result(process.exitValue(), capture.output(), capture.truncated());
        } finally {
            // Future.cancel(true) interrupts this thread; cleanup must still get its bounded grace period.
            boolean interrupted = Thread.interrupted();
            try {
                process.descendants().forEach(p -> descendants.put(p.pid(), p));
                List<ProcessHandle> children = List.copyOf(descendants.values());
                process.destroy();
                children.forEach(ProcessRunner::terminate);
                try {
                    process.waitFor(700, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                children.forEach(p -> { if (p.isAlive()) p.destroyForcibly(); });
                if (process.isAlive()) process.destroyForcibly();
                try {
                    process.waitFor(1000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                try { process.getInputStream().close(); } catch (IOException ignored) {}
                try { process.getErrorStream().close(); } catch (IOException ignored) {}
                reader.interrupt();
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static void terminate(ProcessHandle process) {
        if (process.isAlive()) process.destroy();
    }

    private static final class Capture {
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;

        synchronized void append(char[] chunk, int length) {
            int kept = Math.min(length, MAX_OUTPUT_CHARS - text.length());
            text.append(chunk, 0, kept);
            truncated |= kept < length;
        }

        synchronized String output() { return text.toString().trim(); }
        synchronized boolean truncated() { return truncated; }
    }
}
