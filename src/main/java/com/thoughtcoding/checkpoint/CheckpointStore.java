package com.thoughtcoding.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thoughtcoding.security.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 文件检查点存储 —— write/edit 执行前对目标文件做快照，供 {@code /rewind} 恢复。
 *
 * <p>目录结构：{@code checkpoints/<sessionId>/<seq>.meta.json} + {@code <seq>.bin}
 * （元数据与内容分离；{@code existed=false} 的快照 {@code .bin} 为空文件，恢复语义是<b>删除</b>当前文件）。
 *
 * <p>设计约束：
 * <ul>
 *   <li><b>只快照工作区内文件</b>：路径经 {@link Sandbox#resolve} 解析并校验 {@code isWithinWorkspace}，
 *       越界路径不快照（写工具本身也会拦，这里是不变量双保险）；</li>
 *   <li><b>任何失败静默降级</b>：快照/恢复失败只告警不抛——检查点是保险机制，保险本身不能变成新的故障源；
 *       快照失败时写操作照常执行（宁可没得回滚，不能因快照卡住编辑）；</li>
 *   <li><b>恢复后内容对 FileStateTracker 是外部变更</b>：模型需重新 read 才能 edit——
 *       这是特性而非缺陷（回滚后必须重读再改，防止基于过期内容继续编辑）。</li>
 * </ul>
 *
 * <p>快照在进程退出时整体清理（{@link #clearAll()}，由 Context.close 调用）：检查点是会话内保险，
 * 不跨进程保留。所有方法线程安全（synchronized），多 Agent 并行写不同会话目录互不干扰。
 */
public final class CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(CheckpointStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单个检查点（内容文件由 seq 推导）。 */
    public record Checkpoint(int seq, String originalPath, long timestamp, String reason, boolean existed) {}

    private final Path root;

    private CheckpointStore(Path root) {
        this.root = root;
    }

    /** 创建存储（建目录）；失败返回仍可安全调用的实例（所有操作降级为 no-op + 告警）。 */
    public static CheckpointStore create(Path root) {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.warn("检查点目录创建失败，/rewind 将不可用: {}", e.getMessage());
        }
        return new CheckpointStore(root);
    }

    /**
     * 对目标文件做快照。原文件不存在时记 {@code existed=false}（恢复语义 = 删除）。
     *
     * @return 快照记录；路径越界/写盘失败返回 null（调用方静默继续）
     */
    public synchronized Checkpoint snapshot(String sessionId, String fileArg, String reason) {
        try {
            Path target = Sandbox.resolve(fileArg);
            if (!Sandbox.isWithinWorkspace(target)) {
                return null; // 越界路径不快照：写工具本身会拦，这里是不变量双保险
            }
            Path dir = sessionDir(sessionId);
            int seq = nextSeq(dir);
            boolean existed = Files.exists(target);
            byte[] content = existed ? Files.readAllBytes(target) : new byte[0];

            writeAtomically(dir.resolve(seq + ".bin"), content);
            writeAtomically(dir.resolve(seq + ".meta.json"),
                    JSON.writeValueAsBytes(new Meta(seq, target.toString(), System.currentTimeMillis(),
                            reason == null ? "" : reason, existed)));
            return new Checkpoint(seq, target.toString(), System.currentTimeMillis(),
                    reason == null ? "" : reason, existed);
        } catch (Exception e) {
            log.warn("快照失败（写操作将继续，仅失去该次回滚点）: {}", e.getMessage());
            return null;
        }
    }

    /** 按会话列出全部检查点，seq 升序。 */
    public synchronized List<Checkpoint> list(String sessionId) {
        List<Checkpoint> out = new ArrayList<>();
        Path dir = sessionDir(sessionId);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var stream = Files.list(dir)) {
            for (Path meta : stream.filter(p -> p.getFileName().toString().endsWith(".meta.json")).toList()) {
                try {
                    Meta m = JSON.readValue(meta.toFile(), Meta.class);
                    out.add(new Checkpoint(m.seq, m.originalPath, m.timestamp, m.reason, m.existed));
                } catch (Exception ignored) {
                    // 单条元数据损坏跳过，不影响其余检查点
                }
            }
        } catch (IOException ignored) {
            // 目录列举失败 → 空列表
        }
        out.sort(Comparator.comparingInt(Checkpoint::seq));
        return out;
    }

    /**
     * 恢复指定检查点：原文件存在过 → 内容写回；原本不存在 → 删除当前文件。
     * 恢复同样限制在工作区内（防止被篡改的元数据指向任意路径）。
     */
    public synchronized boolean restore(String sessionId, int seq) {
        Path dir = sessionDir(sessionId);
        Path metaFile = dir.resolve(seq + ".meta.json");
        Path binFile = dir.resolve(seq + ".bin");
        if (!Files.isRegularFile(metaFile) || !Files.isRegularFile(binFile)) {
            return false;
        }
        try {
            Meta m = JSON.readValue(metaFile.toFile(), Meta.class);
            Path target = Path.of(m.originalPath).toAbsolutePath().normalize();
            if (!Sandbox.isWithinWorkspace(target)) {
                log.warn("拒绝恢复越界路径: {}", target);
                return false;
            }
            if (m.existed) {
                writeAtomically(target, Files.readAllBytes(binFile));
            } else {
                Files.deleteIfExists(target);
            }
            return true;
        } catch (Exception e) {
            log.warn("恢复检查点 {} 失败: {}", seq, e.getMessage());
            return false;
        }
    }

    /** 清除单个会话的检查点（会话删除时用）。 */
    public synchronized void clearSession(String sessionId) {
        deleteRecursively(sessionDir(sessionId));
    }

    /** 清除全部检查点（进程退出时用——检查点是会话内保险，不跨进程保留）。 */
    public synchronized void clearAll() {
        deleteRecursively(root);
    }

    // ── 内部 ──

    private record Meta(int seq, String originalPath, long timestamp, String reason, boolean existed) {}

    private Path sessionDir(String sessionId) {
        String safe = sessionId == null ? "nosession" : sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
        return root.resolve(safe);
    }

    private static int nextSeq(Path dir) {
        int max = 0;
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path p : stream.toList()) {
                    String name = p.getFileName().toString();
                    int dot = name.indexOf('.');
                    try {
                        max = Math.max(max, Integer.parseInt(name.substring(0, dot)));
                    } catch (NumberFormatException ignored) {
                        // 非本存储的文件跳过
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return max + 1;
    }

    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            log.warn("检查点清理失败: {}", e.getMessage());
        }
    }
}
