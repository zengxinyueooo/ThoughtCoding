package com.thoughtcoding.hook;

import com.thoughtcoding.checkpoint.CheckpointStore;
import com.thoughtcoding.model.ToolCall;

import java.util.Map;

/**
 * 文件检查点 Hook —— write/edit 执行前对目标文件做快照，供 {@code /rewind} 恢复。
 *
 * <p>注册在 Agent 私有链的<b>末尾</b>（权限门/重复调用熔断经 registerFirst 恒在前）：
 * 被安全动作阻断的写操作不会产生快照——没发生的修改不需要回滚点。
 *
 * <p>fail-open（默认策略）：快照失败降级放行写操作——检查点是保险，保险不能变成新的故障源，
 * 宁可失去该次回滚点也不能卡住编辑。
 */
public class CheckpointHook implements Hook {

    private final CheckpointStore store;
    private final String sessionId;

    public CheckpointHook(CheckpointStore store, String sessionId) {
        this.store = store;
        this.sessionId = sessionId;
    }

    @Override
    public HookResult execute(HookContext context) {
        ToolCall call = context.getToolCall();
        if (store == null || call == null) {
            return HookResult.proceed();
        }
        String tool = call.getToolName();
        if (!"write".equals(tool) && !"edit".equals(tool)) {
            return HookResult.proceed();
        }
        Map<String, Object> params = call.getParameters();
        Object path = params != null ? params.get("path") : null;
        if (path == null || path.toString().isBlank()) {
            return HookResult.proceed(); // 无目标路径（如批量形态）→ 交给工具自身处理
        }
        store.snapshot(sessionId, path.toString(), tool + " 前");
        return HookResult.proceed();
    }

    @Override
    public String name() {
        return "Checkpoint";
    }
}
