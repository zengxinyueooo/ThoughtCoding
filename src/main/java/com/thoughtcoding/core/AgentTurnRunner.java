package com.thoughtcoding.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 回合运行器 —— 把一个用户回合（processInput 全程：模型流式 + 工具执行 +
 * 确认框）放到虚拟线程上执行，REPL 主线程保持可输入。
 *
 * <p>由此获得两个能力：
 * <ul>
 *   <li><b>中断</b>：生成期间用户输入 stop → {@link #cancelCurrent()} 触发回合
 *       CancelToken，跨层传播（流式提前结束、bash 进程被 kill、子代理中断）；</li>
 *   <li><b>输入独占的解决</b>：主线程仍是终端输入的唯一读取者，agent 线程的
 *       确认框经 {@link ConsoleInputRouter}（构造时注册到 context）投递。</li>
 * </ul>
 *
 * <p>同一时刻只允许一个回合在跑（REPL 语义：一条消息处理完再收下一条）；
 * {@code submit} 在回合运行中会被拒绝。
 */
public class AgentTurnRunner {

    private record CurrentTurn(CancelToken token, Future<?> future) {}

    private final AgentLoop agentLoop;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicReference<CurrentTurn> current = new AtomicReference<>();
    private final ConsoleInputRouter inputRouter;
    private volatile String submittedTaskId;

    public AgentTurnRunner(AgentLoop agentLoop, ThoughtCodingContext context) {
        this.agentLoop = agentLoop;
        // 路由器的 owner 就是创建本 runner 的线程（REPL 主线程）
        this.inputRouter = new ConsoleInputRouter(Thread.currentThread());
        context.setConsoleInputRouter(inputRouter);
    }

    /** 是否有回合正在执行。 */
    public boolean isRunning() {
        CurrentTurn turn = current.get();
        return turn != null && !turn.future().isDone();
    }

    /**
     * 提交一个回合。回合运行中调用返回 false（调用方提示用户先 stop）。
     */
    public boolean submit(String input) {
        return submit(token -> agentLoop.processInput(input, token));
    }

    public boolean submitNewTask(String input) {
        return submit(token -> agentLoop.processNewTask(input, token));
    }

    public boolean resume(String taskId) {
        return submit(token -> agentLoop.resumeTask(taskId, token), taskId);
    }

    private boolean submit(java.util.function.Consumer<CancelToken> action) { return submit(action, null); }

    public String getCurrentTaskId() { return submittedTaskId != null ? submittedTaskId : agentLoop.getActiveTaskId(); }

    private synchronized boolean submit(java.util.function.Consumer<CancelToken> action, String taskId) {
        CurrentTurn turn = current.get();
        if (turn != null && !turn.future().isDone()) {
            return false;
        }

        CancelToken token = new CancelToken();
        submittedTaskId = taskId;
        java.util.concurrent.FutureTask<Void> future = new java.util.concurrent.FutureTask<>(() -> {
            action.accept(token);
            return null;
        });
        current.set(new CurrentTurn(token, future));
        try { executor.execute(future); }
        catch (RuntimeException e) { current.set(null); throw e; }
        return true;
    }

    /**
     * 取消当前回合（幂等）。返回是否有回合被取消。
     * 调用方通常还需另行触发 LangChainService.stopCurrentGeneration()（共享生成状态兜底）。
     */
    public boolean cancelCurrent() {
        CurrentTurn turn = current.get();
        if (turn != null && !turn.future().isDone()) {
            turn.token().cancel();
            inputRouter.cancelPending();
            return true;
        }
        return false;
    }

    public boolean pauseCurrent() {
        CurrentTurn turn = current.get();
        if (turn == null || turn.future().isDone()) return false;
        turn.token().pause();
        inputRouter.cancelPending();
        return true;
    }

    /** 等待当前回合结束（测试/退出前用）。 */
    public void awaitIdle() {
        CurrentTurn turn = current.get();
        if (turn != null) {
            try {
                turn.future().get();
            } catch (Exception ignored) {
                // 取消/异常都视为结束
            }
        }
    }

    /** 退出时：取消运行中的回合并关闭线程池。 */
    public void shutdown() {
        pauseCurrent();
        inputRouter.cancelPending();
        executor.shutdownNow();
    }
}
