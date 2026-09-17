package com.thoughtcoding.security;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 计划模式（Plan Mode）全局状态。
 *
 * <p>语义对齐 Claude Code：用户通过 {@code /plan} 进入后，Agent 只能做只读研究
 * （read/glob、只读 shell 命令、skill 加载说明），所有修改类工具在权限门被直接拒绝；
 * 模型产出实施计划后停止，用户 {@code /plan approve} 批准则退出计划模式并把计划
 * 注入上下文开始执行，{@code /plan exit} 则放弃计划。
 *
 * <p>状态为<b>进程级全局</b>（volatile 写、原子读）：主 Agent、SubAgent、直接命令共用
 * 同一视图。子代理内部的 {@code PermissionHook} 走同一个 {@link PermissionGate}，
 * 因此计划模式<b>沿执行链自动传播到子代理</b>——子代理的写操作同样被拒，无需各处单独传递。
 *
 * <p>权限决策本身在 {@link PermissionGate#check(String, java.util.Map, boolean)} 这个
 * 纯函数里完成（可测）；本类只负责「当前模式是什么」这一个可变事实。
 */
public final class PlanMode {

    /** Agent 运行模式。DEFAULT 正常执行；PLAN 只读研究与规划。 */
    public enum Mode { DEFAULT, PLAN }

    private static final AtomicReference<Mode> CURRENT = new AtomicReference<>(Mode.DEFAULT);

    private PlanMode() {}

    /** 是否处于计划模式。 */
    public static boolean isActive() {
        return CURRENT.get() == Mode.PLAN;
    }

    /** 当前模式（供状态展示）。 */
    public static Mode current() {
        return CURRENT.get();
    }

    /** 进入计划模式。幂等。 */
    public static void enter() {
        CURRENT.set(Mode.PLAN);
    }

    /** 退出计划模式（无论计划是否被批准）。幂等。 */
    public static void exit() {
        CURRENT.set(Mode.DEFAULT);
    }
}
