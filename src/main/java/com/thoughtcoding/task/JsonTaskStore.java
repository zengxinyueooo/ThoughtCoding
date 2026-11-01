package com.thoughtcoding.task;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Read-only legacy importer. HEAD fences prevent old binaries from writing a second source of truth. */
public final class JsonTaskStore {
    private static final String PENDING = "SQLITE-PENDING:";
    private static final String MIGRATED = "SQLITE-MIGRATED:";
    private final Path root;
    public JsonTaskStore(Path root) { this.root = root.toAbsolutePath().normalize(); }

    record LegacyTask(String id, String head, boolean completed, List<ExecutionCheckpoint> generations,
                      ExecutionCheckpoint latest) { }

    public void migrateTo(SqliteTaskStore target) {
        if (!Files.isDirectory(root)) return;
        List<Path> initial = directories();
        if (initial.stream().noneMatch(p -> Files.exists(p.resolve("HEAD")))) return;
        boolean pending = false;
        for (Path path : initial) {
            Path head = path.resolve("HEAD");
            if (!Files.exists(head)) continue;
            try {
                String pointer = Files.readString(head).trim();
                if (pointer.startsWith(MIGRATED)) target.verifyImported(path.getFileName().toString(), pointer.substring(MIGRATED.length()));
                else pending = true;
            } catch (IOException e) { throw new TaskPersistenceException("无法读取迁移标记", e); }
        }
        // Finished archives need no execution locks; new processes may open the database while tasks run.
        if (!pending) return;
        var manager = new TaskLeaseManager(root);
        try (var migration = manager.lock("sqlite-migration")) {
            List<Path> directories = directories();
            List<TaskLeaseManager.Lease> held = new ArrayList<>();
            try {
                // Include session and lock-only directories: a legacy task can be between lock and first commit.
                for (Path path : directories) {
                    if (!path.getFileName().toString().equals("sqlite-migration"))
                        held.add(manager.lock(path.getFileName().toString()));
                }
                if (!directories.equals(directories())) throw new IllegalStateException("旧任务目录发生变化，请退出旧版进程后重试迁移");
                List<LegacyTask> tasks = new ArrayList<>();
                for (Path directory : directories) {
                    if (Files.exists(directory.resolve("HEAD"))) tasks.add(read(directory));
                }
                // A crash before the database commit leaves PENDING, which is retryable using the same JSON.
                for (LegacyTask task : tasks) if (!task.completed()) fence(task, PENDING);
                target.importLegacy(tasks);
                // A crash after commit is idempotent: import metadata confirms the existing generations.
                for (LegacyTask task : tasks) if (!task.completed()) fence(task, MIGRATED);
            } finally {
                Collections.reverse(held);
                held.forEach(TaskLeaseManager.Lease::close);
            }
        } catch (Exception e) {
            throw new TaskPersistenceException("旧任务迁移未完成: " + e.getMessage()
                    + "。请退出旧版进程并核验后重试，原 JSON 文件保留在 " + root, e);
        }
    }

    private List<Path> directories() {
        try (var paths = Files.list(root)) {
            return paths.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) { throw new TaskPersistenceException("无法读取旧任务目录", e); }
    }

    private LegacyTask read(Path directory) throws IOException {
        String id = directory.getFileName().toString();
        TaskLeaseManager.validateId(id);
        String pointer = Files.readString(directory.resolve("HEAD")).trim();
        boolean completed = pointer.startsWith(MIGRATED);
        String head = completed ? pointer.substring(MIGRATED.length())
                : pointer.startsWith(PENDING) ? pointer.substring(PENDING.length()) : pointer;
        if (!head.matches("[0-9]+-[a-f0-9-]+\\.json")) throw new IOException("无效 HEAD: " + id);
        long latestVersion = version(head);
        ExecutionCheckpoint latest = SqliteTaskStore.decode(Files.readString(directory.resolve(head)), id, latestVersion);
        TreeMap<Long, ExecutionCheckpoint> generations = new TreeMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (!name.matches("[0-9]+-[a-f0-9-]+\\.json")) continue;
                long version = version(name);
                if (version >= latestVersion) continue; // Only HEAD identifies the valid latest generation.
                ExecutionCheckpoint cp = SqliteTaskStore.decode(Files.readString(path), id, version);
                if (generations.putIfAbsent(version, cp) != null)
                    throw new IOException("存在无法判定归属的历史版本: " + id + "/" + version);
                if (!Objects.equals(cp.task().sessionId(), latest.task().sessionId())
                        || !Objects.equals(cp.task().parentTaskId(), latest.task().parentTaskId()))
                    throw new IOException("历史版本任务归属不一致: " + id);
            }
        }
        generations.put(latestVersion, latest);
        return new LegacyTask(id, head, completed, List.copyOf(generations.values()), latest);
    }

    private static long version(String filename) throws IOException {
        try {
            long version = Long.parseLong(filename.substring(0, filename.indexOf('-')));
            if (version <= 0) throw new NumberFormatException();
            return version;
        } catch (NumberFormatException e) { throw new IOException("非法检查点版本: " + filename, e); }
    }

    private void fence(LegacyTask task, String prefix) throws IOException {
        Path directory = root.resolve(task.id());
        Path temp = directory.resolve("sqlite-head-" + UUID.randomUUID() + ".tmp");
        byte[] content = (prefix + task.head()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            try (var channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(content);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temp, directory.resolve("HEAD"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
