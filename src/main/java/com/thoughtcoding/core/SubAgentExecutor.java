package com.thoughtcoding.core;

import com.thoughtcoding.ui.ThoughtCodingUI;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SubAgent 并发调度器 —— 并行/后台子代理的唯一运行时。
 *
 * <p><b>选型：Java 21 虚拟线程（Loom）+ Semaphore 限流。</b>
 * 子代理工作负载是典型 IO 密集（模型流式往返、子进程、文件读写），虚拟线程
 * 用阻塞式代码写法获得高并发，无需回调编排；Semaphore 限制同时在跑的子代理数，
 * 防止一轮内模型派发过多任务触发 API 限流。对比固定大小平台线程池：
 * 平台线程默认 ~200KB 栈且创建昂贵，数量上限低；对比 CompletableFuture 链：
 * 回调式编排对「子代理内部本来就有 for 循环 + 阻塞等待」的代码形态是纯负担。
 *
 * <p>两种模式：
 * <ul>
 *   <li><b>前台并行</b>：{@link #submitForeground}——AgentLoop 把同一批的多个 subAgent
 *       调用全部提交后等待，批内并行、批间串行（主循环语义不变）；</li>
 *   <li><b>后台</b>：{@link #startBackground}——立即返回任务 id，子代理跨回合存活，
 *       结论完成后由 {@link #drainCompleted()} 在下一轮 processInput 入口注入主历史。</li>
 * </ul>
 */
public class SubAgentExecutor {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;
    private final ThoughtCodingUI ui; // 可为 null（测试环境）
    private final AtomicInteger idSeq = new AtomicInteger();

    /** 后台任务：跨回合存活，状态由执行线程写、REPL 线程读，全部用 volatile。 */
    public static final class BackgroundTask {
        public enum Status { RUNNING, DONE, FAILED }

        private final String id;
        private final String label;
        private final CancelToken token; // 任务私有令牌：shutdown/退出时统一取消
        private volatile Status status = Status.RUNNING;
        private volatile String conclusion = "";

        BackgroundTask(String id, String label, CancelToken token) {
            this.id = id;
            this.label = label;
            this.token = token;
        }

        public String id() { return id; }
        public String label() { return label; }
        public CancelToken token() { return token; }
        public Status status() { return status; }
        public String conclusion() { return conclusion; }
    }

    // 所有后台任务（含运行中）；已完成的结论进入 completedResults 等待注入
    private final ConcurrentHashMap<String, BackgroundTask> tasks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<BackgroundTask> completedResults = new ConcurrentLinkedQueue<>();

    public SubAgentExecutor(int maxConcurrent, ThoughtCodingUI ui) {
        this.permits = new Semaphore(Math.max(1, maxConcurrent));
        this.ui = ui;
    }

    /**
     * 提交一个前台子代理任务：虚拟线程执行，持有许可（限流）。
     * 调用方（AgentLoop）自行决定等待一个还是一批。
     */
    public <T> Future<T> submitForeground(Callable<T> task) {
        var workspace = com.thoughtcoding.security.Sandbox.workspaceRoot();
        return executor.submit(() -> {
            permits.acquire();
            try (var scope = com.thoughtcoding.security.Sandbox.enterWorkspace(workspace)) {
                return task.call();
            } finally {
                permits.release();
            }
        });
    }

    /**
     * 启动一个后台子代理任务：立即返回任务句柄，不阻塞调用方。
     * 任务完成后打印通知，结论进入待注入队列。
     */
    public BackgroundTask startBackground(String label, java.util.function.Function<CancelToken, String> task) {
        String id = "subagent-" + idSeq.incrementAndGet();
        return startBackground(id, label, task, false);
    }

    public BackgroundTask startDurableBackground(String id, String label, java.util.function.Function<CancelToken, String> task) {
        return startBackground(id, label, task, true);
    }

    private BackgroundTask startBackground(String id, String label, java.util.function.Function<CancelToken, String> task,
                                           boolean durable) {
        CancelToken token = new CancelToken();
        BackgroundTask t = new BackgroundTask(id, label, token);
        if (tasks.putIfAbsent(id, t) != null) throw new IllegalStateException("子任务已在调度器中: " + id);
        var workspace = com.thoughtcoding.security.Sandbox.workspaceRoot();

        Runnable backgroundTask = () -> {
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                t.conclusion = "后台子代理被中断";
                t.status = BackgroundTask.Status.FAILED;
                if (!durable) completedResults.add(t);
                return;
            }
            try (var scope = com.thoughtcoding.security.Sandbox.enterWorkspace(workspace)) {
                String conclusion = task.apply(token);
                t.conclusion = conclusion == null ? "" : conclusion;
                t.status = BackgroundTask.Status.DONE;
                if (!durable) completedResults.add(t);
                notifyDone(t);
            } catch (Exception e) {
                t.conclusion = "后台子代理失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                t.status = BackgroundTask.Status.FAILED;
                if (!durable) completedResults.add(t);
                notifyDone(t);
            } finally {
                permits.release();
            }
        };
        try { executor.submit(backgroundTask); }
        catch (RuntimeException error) {
            tasks.remove(id); throw error;
        }
        return t;
    }

    /**
     * 取走所有已完成、尚未注入主历史的后台任务结论（由 AgentLoop.processInput 入口调用）。
     */
    public List<BackgroundTask> drainCompleted() {
        List<BackgroundTask> out = new ArrayList<>();
        BackgroundTask t;
        while ((t = completedResults.poll()) != null) {
            out.add(t);
        }
        return out;
    }

    /** 运行中/已完成的全部后台任务（展示用）。 */
    public List<BackgroundTask> allTasks() {
        return new ArrayList<>(tasks.values());
    }

    public boolean stop(String id, boolean pause) {
        BackgroundTask task = tasks.get(id);
        if (task == null || task.status() != BackgroundTask.Status.RUNNING) return false;
        if (pause) task.token().pause(); else task.token().cancel();
        return true;
    }

    /** 退出时统一取消所有后台任务并停掉调度器。 */
    public void shutdown() {
        for (BackgroundTask t : tasks.values()) {
            t.token().pause();
        }
        executor.shutdownNow();
    }

    private void notifyDone(BackgroundTask t) {
        if (ui != null) {
            ui.displayInfo("✅ [SubAgent " + t.label() + "] 已完成（" + t.id() + "），结论将在下一轮对话自动注入。");
        }
    }
}
