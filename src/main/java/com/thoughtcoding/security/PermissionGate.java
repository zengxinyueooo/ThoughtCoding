package com.thoughtcoding.security;

import com.thoughtcoding.exception.WorkspaceSecurityException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限管道 —— AgentLoop 的唯一决策入口。
 *
 * <pre>
 * 调用方式：
 *   PermissionResult perm = PermissionGate.check(toolName, params);
 *   if (perm.type() == DENY)  → 直接报错跳过
 *   if (perm.type() == WARN)  → 弹确认
 *   // ALLOW → 静默放行
 * </pre>
 *
 * 决策规则（默认模式）：
 *   read / glob  → 路径越界则 WARN，否则 ALLOW
 *   write / edit → 固定 WARN
 *   bash         → Gate 1 硬拒绝 DENY，否则固定 WARN（危险模式追加提示）
 *   todo_write   → ALLOW（纯内存规划工具，无副作用，静默放行）
 *   subAgent     → ALLOW（子Agent内部工具调用各自走权限管道，umbrella 再确认会双重弹框）
 *   skill        → ALLOW（纯读取已扫描的本地技能文本，无副作用，静默放行）
 *   未知工具      → WARN
 *
 * 决策规则（计划模式，{@link PlanMode} 激活时，对齐 Claude Code 的 Plan Mode）：
 *   write / edit       → DENY（研究阶段禁止一切修改）
 *   bash               → Gate 1 照旧 → 只读白名单 ALLOW，白名单外/组合命令 DENY（fail-closed）
 *   未知工具（含 MCP）  → DENY（无只读标注体系，一律保守）
 *   其余同默认模式；subAgent 放行——子代理内部工具走同一 Gate，约束自动传播。
 *
 * 决策与模式解耦：{@link #check(String, Map)} 读全局 {@link PlanMode}；
 * {@link #check(String, Map, boolean)} 是纯函数，供单测绕过全局状态直接验证规则矩阵。
 */
public final class PermissionGate {

    private PermissionGate() {}

    // ═══════════════ Gate 1: 硬拒绝列表 ═══════════════

    private static final List<String> BASH_DENY_PATTERNS = List.of(
        "rm -rf /", "sudo ", "shutdown", "reboot", "mkfs",
        "dd if=", "> /dev/sda", "format ", "del /f /s", "rd /s /q"
    );

    private static PermissionResult checkBashDeny(String command) {
        if (command == null || command.isBlank()) return null;
        String lower = command.toLowerCase();
        for (String pattern : BASH_DENY_PATTERNS) {
            if (lower.contains(pattern)) {
                return PermissionResult.deny(
                    "⛔ 命令被阻止: '" + pattern + "' 在拒绝列表中");
            }
        }
        return null;
    }

    // ═══════════════ Gate 2: 规则匹配 ═══════════════

    // ── read / glob：路径越界才确认 ──
    private static PermissionResult checkReadPath(Map<String, Object> params) {
        String pathStr = paramString(params, "path");
        if (pathStr == null || pathStr.isBlank()) return PermissionResult.ALLOW;

        try {
            Path resolved = Sandbox.resolve(pathStr);
            if (!Sandbox.isWithinWorkspace(resolved)) {
                return PermissionResult.warn(
                    "⚠️ 路径在 workspace 之外: " + resolved);
            }
        } catch (WorkspaceSecurityException e) {
            // 路径为空/非法 → 交给工具侧报具体错误，权限不做拦截
            return PermissionResult.ALLOW;
        }
        return PermissionResult.ALLOW;
    }

    // ── bash：先过 Gate 1 拒绝列表，再追加危险模式提示 ──
    private static PermissionResult checkBash(Map<String, Object> params) {
        String command = paramString(params, "command");

        // Gate 1: 硬拒绝
        PermissionResult deny = checkBashDeny(command);
        if (deny != null) return deny;

        // 固定确认，有危险模式则追加提示
        String extra = bashWarning(command);
        return PermissionResult.warn(
            "⚠️ 将执行 shell 命令" + (extra.isEmpty() ? "" : " —— " + extra));
    }

    /** 返回危险模式描述，无匹配返回空串。 */
    private static String bashWarning(String command) {
        if (command == null || command.isBlank()) return "";
        String lower = command.toLowerCase();

        if (lower.contains("rm ") || lower.contains("del "))
            return "包含删除操作";
        if (lower.contains("> /etc/") || lower.contains("> c:\\windows"))
            return "写入系统目录";
        if (lower.contains("chmod 777"))
            return "修改关键权限";
        if (lower.contains("git push --force") || lower.contains("git push -f"))
            return "强制推送";
        return "";
    }

    // ═══════════════ 计划模式：bash 只读白名单 ═══════════════

    /**
     * 计划模式允许直接放行的只读命令首词。
     *
     * <p>有意保守（fail-closed）：白名单只覆盖「读代码/读仓库」的高频命令；
     * curl 等网络命令可写远端、mvn test 会写 target、find 带 -delete/-exec 可写本地，
     * 一律不进白名单——研究阶段 read/glob 已够用，误拒的代价远小于误放。
     */
    private static final Set<String> PLAN_READONLY_COMMANDS = Set.of(
        "pwd", "whoami", "hostname", "date",
        "ls", "dir", "tree", "du", "df", "stat", "file", "wc",
        "cat", "type", "head", "tail",
        "grep", "rg", "findstr",
        "which", "where", "echo"
    );

    /** 只读 git 子命令：这些子命令本身语义就是读，参数不限（仍受组合符检查约束）。 */
    private static final Set<String> GIT_READONLY_SUBCOMMANDS = Set.of(
        "status", "log", "diff", "show", "blame", "rev-parse", "describe",
        "shortlog", "reflog", "ls-files", "ls-remote", "cat-file", "grep", "help", "version"
    );

    /** git 读/写两可子命令（branch/tag/remote）：只允许纯 flag 参数（列表形态），出现位置名/URL 即拒绝。 */
    private static boolean isGitListFormReadonly(String[] tokens) {
        for (int i = 2; i < tokens.length; i++) {
            if (!tokens[i].startsWith("-")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 计划模式 bash 检查：Gate 1 硬拒绝优先 → 组合/重定向符拒绝 → 只读白名单。
     * 返回非 DENY 即放行（白名单内的只读命令不打扰用户）。
     */
    private static PermissionResult checkBashPlan(String command) {
        // Gate 1 硬拒绝列表在计划模式下依然优先
        PermissionResult deny = checkBashDeny(command);
        if (deny != null) return deny;

        String planDenyMsg = "⛔ 计划模式(Plan Mode)：仅允许只读命令"
                + "（ls/cat/grep/git log 等白名单内命令），不允许执行有副作用的操作。"
                + "请继续只读研究，并在准备好后输出实施计划。";

        if (command == null || command.isBlank()) {
            return PermissionResult.deny(planDenyMsg);
        }

        // 组合/重定向一律拒绝：第二段命令绕不过首词白名单，重定向/命令替换可写入或执行任意内容。
        // 管道也拒绝（| tee / | sh 类写入与执行），研究阶段单条命令足够——有意保守，注释为证。
        String lower = command.toLowerCase();
        for (String marker : new String[]{";", "&&", "||", "|", ">", "<", "&", "`", "$(", "\n"}) {
            if (lower.contains(marker)) {
                return PermissionResult.deny(planDenyMsg + "（检测到组合/重定向符: " + marker + "）");
            }
        }

        String[] tokens = command.trim().split("\\s+");
        String head = tokens[0].toLowerCase();

        if ("git".equals(head)) {
            if (tokens.length < 2) {
                return PermissionResult.ALLOW; // 裸 git → 帮助文本，只读
            }
            String sub = tokens[1].toLowerCase();
            if (GIT_READONLY_SUBCOMMANDS.contains(sub)) {
                return PermissionResult.ALLOW;
            }
            if (("branch".equals(sub) || "tag".equals(sub) || "remote".equals(sub))
                    && isGitListFormReadonly(tokens)) {
                return PermissionResult.ALLOW;
            }
            return PermissionResult.deny(planDenyMsg + "（git " + sub + " 非只读子命令）");
        }

        if (PLAN_READONLY_COMMANDS.contains(head)) {
            return PermissionResult.ALLOW;
        }

        // 版本查询形态（java -version / mvn -v / python --version ...）
        if (tokens.length == 2 && Set.of("java", "mvn", "node", "npm", "python", "python3",
                "pip", "gradle", "dotnet", "git").contains(head)
                && Set.of("-v", "-version", "--version", "-V").contains(tokens[1].toLowerCase())) {
            return PermissionResult.ALLOW;
        }

        return PermissionResult.deny(planDenyMsg);
    }

    // ═══════════════ 入口 ═══════════════

    /**
     * 一次性权限决策（读取全局 {@link PlanMode} 当前模式）。
     *
     * @return DENY → 拒绝执行；WARN → 弹确认；ALLOW → 静默放行
     */
    public static PermissionResult check(String toolName, Map<String, Object> params) {
        return check(toolName, params, PlanMode.isActive());
    }

    /**
     * 带模式的一次性权限决策（纯函数，模式由调用方显式给定，便于测试规则矩阵）。
     *
     * @param planMode true = 计划模式（只读研究）；false = 默认模式
     * @return DENY → 拒绝执行；WARN → 弹确认；ALLOW → 静默放行
     */
    public static PermissionResult check(String toolName, Map<String, Object> params, boolean planMode) {
        if (toolName == null) return PermissionResult.warn("⚠️ 未知工具");

        if (planMode) {
            return switch (toolName) {
                case "read", "glob" -> checkReadPath(params);
                case "bash"         -> checkBashPlan(paramString(params, "command"));
                case "write", "edit" -> PermissionResult.deny(
                        "⛔ 计划模式(Plan Mode)：禁止修改文件。请只读研究代码，"
                                + "并在准备好后输出完整实施计划，等待用户批准。");
                case "todo_write"   -> PermissionResult.ALLOW;   // 纯内存规划，计划模式的核心工具
                case "subAgent"     -> PermissionResult.ALLOW;   // 子代理内部工具走同一 Gate，约束自动传播
                case "skill"        -> PermissionResult.ALLOW;   // 纯读取本地技能文本
                default             -> PermissionResult.deny(
                        "⛔ 计划模式(Plan Mode)：仅允许只读内置工具（read/glob/bash 只读命令/todo_write/skill），"
                                + "工具 " + toolName + " 在此模式下被禁止。");
            };
        }

        return switch (toolName) {
            case "read", "glob" -> checkReadPath(params);
            case "write"        -> PermissionResult.warn("⚠️ 将写入文件");
            case "edit"         -> PermissionResult.warn("⚠️ 将修改文件");
            case "bash"         -> checkBash(params);
            case "todo_write"   -> PermissionResult.ALLOW;
            case "subAgent"     -> PermissionResult.ALLOW;
            case "skill"        -> PermissionResult.ALLOW;
            default             -> PermissionResult.warn("⚠️ 未知工具: " + toolName);
        };
    }

    // ── 工具方法 ──

    private static String paramString(Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v == null ? null : v.toString();
    }
}
