package com.thoughtcoding.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件状态跟踪器 —— agent 认知与现实一致性的守门人。
 *
 * <p>记录模型「最后一次看到的文件快照」（mtime + size，由 read/write/edit 成功后登记），
 * 在 edit/覆盖写之前校验：文件是否从未被读过（模型在盲写）、是否在读取后被外部修改
 * （模型基于过期内容编辑会<b>静默覆盖</b>他人的改动）。
 *
 * <p>这正是 agent 工程里「ledger 与现实脱节」问题的最小防线：对话历史说模型读过某文件，
 * 但文件系统此刻的内容可能已经不同——校验的成本是一次 stat，事故的代价是静默数据丢失。
 *
 * <p>线程安全（ConcurrentHashMap）：主 Agent 与并行子代理（worktree 内路径不同，天然不冲突）
 * 可并发访问。注册为 null 时所有检查跳过——供测试与降级场景使用，行为与旧版完全一致。
 */
public final class FileStateTracker {

    /** 一致性状态：CLEAN 与记录一致；STALE 读取后被外部修改；NEVER_READ 从未登记过。 */
    public enum State { CLEAN, STALE, NEVER_READ }

    /** path 归一化字符串 → [lastModifiedMillis, size]。 */
    private final Map<String, long[]> snapshots = new ConcurrentHashMap<>();

    /** 登记「模型刚看到/刚写完」的文件快照。stat 失败静默跳过（不阻塞工具）。 */
    public void recordRead(Path path) {
        if (path == null) {
            return;
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            snapshots.put(normalize(path),
                    new long[]{attrs.lastModifiedTime().toMillis(), attrs.size()});
        } catch (Exception ignored) {
            // 文件刚被删/不可达：清掉旧快照，避免用陈旧记录做判断
            snapshots.remove(normalize(path));
        }
    }

    /** 校验编辑/覆盖写前的一致性。 */
    public State checkStale(Path path) {
        if (path == null) {
            return State.NEVER_READ;
        }
        long[] snapshot = snapshots.get(normalize(path));
        if (snapshot == null) {
            return State.NEVER_READ;
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            boolean changed = attrs.lastModifiedTime().toMillis() != snapshot[0]
                    || attrs.size() != snapshot[1];
            return changed ? State.STALE : State.CLEAN;
        } catch (Exception e) {
            // 文件已消失：视为被外部修改（外部动作删除了它）
            return State.STALE;
        }
    }

    private String normalize(Path path) {
        return path.toAbsolutePath().toString();
    }
}
