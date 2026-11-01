package com.thoughtcoding.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单仓库 Worktree 元数据存储。
 *
 * <p>JVM 内先用 ReentrantLock 排队，再用文件锁防止两个 ThoughtCoding 进程同时修改
 * Git worktree 注册表和 tasks.json。调用方只在短暂的创建/收尾/清理阶段持锁，
 * SubAgent 的模型调用与文件操作不持锁，因此仍可并行。
 */
final class WorktreeRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final TypeReference<List<WorktreeManager.TaskRecord>> TASK_LIST_TYPE =
            new TypeReference<>() {};
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path repositoryStateRoot;
    private final Path indexPath;
    private final Path lockPath;

    WorktreeRegistry(Path repositoryRoot, Path stateRoot) {
        Path normalizedRepository = repositoryRoot.toAbsolutePath().normalize();
        this.repositoryStateRoot = stateRoot.toAbsolutePath().normalize()
                .resolve(repositoryKey(normalizedRepository));
        this.indexPath = repositoryStateRoot.resolve("tasks.json");
        this.lockPath = repositoryStateRoot.resolve("manager.lock");
    }

    Path repositoryStateRoot() {
        return repositoryStateRoot;
    }

    Path taskDirectory(String taskId) {
        return repositoryStateRoot.resolve(taskId).toAbsolutePath().normalize();
    }

    com.thoughtcoding.task.TaskLeaseManager.Lease executionLease(String taskId) {
        return new com.thoughtcoding.task.TaskLeaseManager(repositoryStateRoot.resolve(".leases")).lock(taskId);
    }

    <T> T withLock(Callable<T> action) {
        try {
            Files.createDirectories(repositoryStateRoot);
        } catch (IOException e) {
            throw new WorktreeManager.WorktreeException(
                    "无法创建 Worktree 状态目录: " + repositoryStateRoot, e);
        }

        Path normalizedLock = lockPath.toAbsolutePath().normalize();
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(normalizedLock, ignored -> new ReentrantLock());
        jvmLock.lock();
        try (FileChannel channel = FileChannel.open(normalizedLock,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return action.call();
        } catch (WorktreeManager.WorktreeException e) {
            throw e;
        } catch (Exception e) {
            throw new WorktreeManager.WorktreeException("Worktree 注册表操作失败: " + e.getMessage(), e);
        } finally {
            jvmLock.unlock();
        }
    }

    List<WorktreeManager.TaskRecord> load() {
        if (!Files.isRegularFile(indexPath)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(indexPath);
            if (json.isBlank()) return new ArrayList<>();
            List<WorktreeManager.TaskRecord> tasks = MAPPER.readValue(json, TASK_LIST_TYPE);
            return tasks == null ? new ArrayList<>() : new ArrayList<>(tasks);
        } catch (Exception e) {
            throw new WorktreeManager.WorktreeException("无法读取 Worktree 任务索引: " + indexPath, e);
        }
    }

    void save(List<WorktreeManager.TaskRecord> tasks) {
        Path temp = indexPath.resolveSibling(indexPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(repositoryStateRoot);
            Files.writeString(temp, MAPPER.writeValueAsString(tasks == null ? List.of() : tasks));
            try {
                Files.move(temp, indexPath, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, indexPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Windows 上杀毒/索引程序可能让 ATOMIC_MOVE 短暂不可用，退化为同目录替换。
                Files.move(temp, indexPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
            throw new WorktreeManager.WorktreeException("无法保存 Worktree 任务索引: " + indexPath, e);
        }
    }

    private static String repositoryKey(Path repositoryRoot) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(repositoryRoot.toString().toLowerCase().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                out.append(String.format("%02x", digest[i]));
            }
            return out.toString();
        } catch (Exception e) {
            return Integer.toUnsignedString(repositoryRoot.toString().toLowerCase().hashCode(), 36);
        }
    }
}
