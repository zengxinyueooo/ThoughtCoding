package com.thoughtcoding.task;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** OS locks retain the legacy location, including compatibility with older processes. */
public final class TaskLeaseManager {
    private final Path root;
    public TaskLeaseManager(Path root) { this.root = root.toAbsolutePath().normalize(); }

    static void validateId(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9-]{1,100}")) throw new IllegalArgumentException("非法任务 ID");
    }

    public Lease lock(String id) {
        validateId(id);
        FileChannel channel = null;
        try {
            Path directory = root.resolve(id);
            Files.createDirectories(directory);
            channel = FileChannel.open(directory.resolve("run.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("任务正在其他进程执行: " + id);
            return new Lease(channel, lock);
        } catch (IOException | IllegalStateException e) {
            if (channel != null) try { channel.close(); } catch (IOException ignored) { }
            throw new IllegalStateException("无法独占任务 " + id + ": " + e.getMessage(), e);
        }
    }

    public static final class Lease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private Lease(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
        @Override public void close() {
            try { lock.release(); } catch (IOException ignored) { }
            try { channel.close(); } catch (IOException ignored) { }
        }
    }
}
