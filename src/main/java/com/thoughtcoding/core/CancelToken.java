package com.thoughtcoding.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 协作式取消令牌 —— 一个用户回合（agent turn）一个实例，自顶向下传播：
 * AgentTurnRunner（创建）→ AgentLoop → ToolDispatcher → BaseTool → BashTool/SubAgent → LangChainService。
 *
 * <p><b>选型说明（协作式 token vs Thread.interrupt() vs Future.cancel(true)）：</b>
 * <ul>
 *   <li>{@code interrupt()} 需要逐层穿透且对子进程 kill 无效（bash 进程不响应 Java 中断），
 *       虚拟线程上还受 pinning 限制；</li>
 *   <li>{@code Future.cancel(true)} 只作用于单层任务，无法携带到工具内部的子结构；</li>
 *   <li>协作式 token 可跨层显式传递、检查点位置可控，且支持 {@link #onCancel} 挂
 *       <b>即时动作</b>（如 destroyForcibly 一个正在跑的 bash 进程）——这是另外两者做不到的。</li>
 * </ul>
 *
 * <p>线程安全：cancel 幂等；回调注册后若已取消则立即执行。
 */
public class CancelToken {

    private volatile boolean cancelled = false;
    private volatile boolean pauseRequested;
    private final List<Runnable> actions = new ArrayList<>();

    /** 已取消返回 true。检查点逻辑请用 {@link #check()}。 */
    public boolean isCancelled() {
        return cancelled;
    }

    /** 取消本回合。幂等：仅第一次调用触发回调。 */
    public void cancel() {
        stop(false);
    }

    /** Pause uses the same cancellation propagation, retaining a resumable task lifecycle. */
    public void pause() {
        stop(true);
    }

    public boolean isPauseRequested() { return pauseRequested; }

    private void stop(boolean pause) {
        List<Runnable> toRun;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            pauseRequested = pause;
            toRun = List.copyOf(actions);
            actions.clear();
        }

        // 不持有锁执行用户回调，避免回调重入 onCancel/cancel 时造成锁内阻塞。
        for (Runnable action : toRun) {
            try {
                action.run();
            } catch (Exception ignored) {
                // 单个回调失败不影响其余回调（如多个 bash 进程都要被 kill）
            }
        }
    }

    /**
     * 协作式检查点：已取消时抛出 {@link CancelledException}。
     * 调用方必须捕获并转化为配对的 tool 结果 / 兜底结论。
     */
    public void check() {
        if (cancelled) {
            throw new CancelledException("任务已被用户取消");
        }
    }

    /**
     * 注册取消时立即执行的动作（如 kill bash 进程、提前完成等待中的 future）。
     * 若此刻已取消，动作立即内联执行。
     */
    public void onCancel(Runnable action) {
        synchronized (this) {
            if (!cancelled) {
                actions.add(action);
                return;
            }
        }

        // 已取消时不持有锁执行，语义与 cancel 中的回调执行保持一致。
        action.run();
    }
}
