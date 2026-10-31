package com.thoughtcoding.task;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Immutable JSON generations plus an atomically replaced HEAD commit marker. */
public final class TaskStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root;

    public TaskStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    private Path directory(String taskId) {
        if (taskId == null || !taskId.matches("[a-zA-Z0-9-]{1,100}")) {
            throw new IllegalArgumentException("非法任务 ID");
        }
        return root.resolve(taskId);
    }

    public Lease lock(String taskId) {
        FileChannel channel = null;
        try {
            Path dir = directory(taskId);
            Files.createDirectories(dir);
            channel = FileChannel.open(dir.resolve("run.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("任务正在其他进程执行: " + taskId);
            return new Lease(channel, lock);
        } catch (IOException | IllegalStateException e) {
            if (channel != null) try { channel.close(); } catch (IOException ignored) { }
            throw new IllegalStateException("无法独占任务 " + taskId + ": " + e.getMessage(), e);
        }
    }

    public ExecutionCheckpoint load(String taskId) {
        try {
            Path dir = directory(taskId);
            String name = Files.readString(dir.resolve("HEAD")).trim();
            if (!name.matches("[0-9]+-[a-f0-9-]+\\.json")) throw new IOException("无效的 HEAD");
            ExecutionCheckpoint cp = JSON.readValue(dir.resolve(name).toFile(), ExecutionCheckpoint.class);
            if (cp.schemaVersion() != 1 || !taskId.equals(cp.task().taskId())) {
                throw new IOException("不支持的检查点版本或任务 ID 不匹配");
            }
            return cp;
        } catch (IOException e) {
            throw new IllegalStateException("读取任务检查点失败: " + taskId, e);
        }
    }

    public boolean exists(String taskId) { return Files.isRegularFile(directory(taskId).resolve("HEAD")); }

    /** Caller holds the task lease; a failed commit never replaces the previous HEAD. */
    public void commit(ExecutionCheckpoint checkpoint, long expectedVersion) {
        String taskId = checkpoint.task().taskId();
        Path dir = directory(taskId);
        try {
            long actual = Files.exists(dir.resolve("HEAD")) ? load(taskId).task().version() : 0;
            if (actual != expectedVersion || checkpoint.task().version() != actual + 1) {
                throw new TaskPersistenceException("任务版本冲突: " + taskId, null);
            }
            String name = checkpoint.task().version() + "-" + UUID.randomUUID() + ".json";
            writeForced(dir.resolve(name), JSON.writeValueAsBytes(checkpoint));
            Path head = dir.resolve("HEAD-" + UUID.randomUUID() + ".tmp");
            try {
                writeForced(head, name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                // No non-atomic fallback: fail closed if the filesystem cannot commit safely.
                Files.move(head, dir.resolve("HEAD"), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(head);
            }
        } catch (IOException e) {
            throw new TaskPersistenceException("任务检查点写入失败，已停止后续执行: " + taskId, e);
        }
    }

    private static void writeForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    public List<TaskState> list(String sessionId) {
        if (!Files.isDirectory(root)) return List.of();
        List<TaskState> result = new ArrayList<>();
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                if (!Files.exists(dir.resolve("HEAD"))) continue;
                TaskState state = load(dir.getFileName().toString()).task();
                if (sessionId == null || sessionId.equals(state.sessionId())) result.add(state);
            }
        } catch (IOException e) {
            throw new IllegalStateException("任务列表读取失败", e);
        }
        result.sort(Comparator.comparing(TaskState::updatedAt).reversed());
        return List.copyOf(result);
    }

    public List<Long> checkpoints(String taskId) {
        ExecutionCheckpoint latest = load(taskId);
        try (var files = Files.list(directory(taskId))) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.matches("[0-9]+-[a-f0-9-]+\\.json"))
                    .map(n -> Long.parseLong(n.substring(0, n.indexOf('-'))))
                    .filter(v -> v <= latest.task().version()).distinct().sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("检查点列表读取失败", e);
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
