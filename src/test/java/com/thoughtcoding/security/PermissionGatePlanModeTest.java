package com.thoughtcoding.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 计划模式权限规则矩阵的单元测试。
 *
 * <p>全部走三参纯函数 {@link PermissionGate#check(String, Map, boolean)}，
 * 不触碰 PlanMode 全局状态。铁律：
 * <ul>
 *   <li>计划模式下一切修改类操作 DENY（write/edit、白名单外 bash、未知/MCP 工具）</li>
 *   <li>只读操作放行（read/glob/skill/todo_write、白名单内 bash）</li>
 *   <li>默认模式行为与旧实现完全一致（回归不变量）</li>
 * </ul>
 */
class PermissionGatePlanModeTest {

    private static final boolean PLAN = true;
    private static final boolean DEFAULT = false;

    @BeforeEach
    void pinWorkspaceRoot() {
        // read/glob 的路径越界检查依赖 Sandbox 全局 root；同 JVM 里先序测试
        // （如 Context 生命周期）可能已把它 init 成别处——显式钉回项目根，消除测试顺序耦合。
        Sandbox.init(System.getProperty("user.dir"));
    }

    private static PermissionResult.Type typeOf(PermissionResult r) {
        return r.type();
    }

    // ═══════════════ 计划模式：修改类工具一律 DENY ═══════════════

    @Test
    void 计划模式write被拒绝() {
        assertEquals(PermissionResult.Type.DENY,
                typeOf(PermissionGate.check("write", Map.of("path", "a.txt", "input", "x"), PLAN)));
    }

    @Test
    void 计划模式edit被拒绝() {
        assertEquals(PermissionResult.Type.DENY,
                typeOf(PermissionGate.check("edit", Map.of("path", "a.txt"), PLAN)));
    }

    @Test
    void 计划模式未知工具与MCP工具被拒绝() {
        assertEquals(PermissionResult.Type.DENY,
                typeOf(PermissionGate.check("mcp__github__create_issue", Map.of(), PLAN)));
        assertEquals(PermissionResult.Type.DENY,
                typeOf(PermissionGate.check("totally_unknown", Map.of(), PLAN)));
    }

    // ═══════════════ 计划模式：只读工具放行 ═══════════════

    @Test
    void 计划模式read与glob放行() {
        assertEquals(PermissionResult.Type.ALLOW,
                typeOf(PermissionGate.check("read", Map.of("path", "src/App.java"), PLAN)));
        assertEquals(PermissionResult.Type.ALLOW,
                typeOf(PermissionGate.check("glob", Map.of("pattern", "**/*.java"), PLAN)));
    }

    @Test
    void 计划模式read越界仍走WARN路径检查() {
        // 路径边界检查独立于模式：越界读在计划模式下同样 WARN（不是 DENY，交给用户判断）
        assertEquals(PermissionResult.Type.WARN,
                typeOf(PermissionGate.check("read", Map.of("path", "C:\\Windows\\system32\\config"), PLAN)));
    }

    @Test
    void 计划模式skill_todoWrite_subAgent放行() {
        assertEquals(PermissionResult.Type.ALLOW,
                typeOf(PermissionGate.check("skill", Map.of("name", "review"), PLAN)));
        assertEquals(PermissionResult.Type.ALLOW,
                typeOf(PermissionGate.check("todo_write", Map.of(), PLAN)));
        assertEquals(PermissionResult.Type.ALLOW,
                typeOf(PermissionGate.check("subAgent", Map.of("prompt", "研究调用链"), PLAN)));
    }

    // ═══════════════ 计划模式：bash 只读白名单 ═══════════════

    @Test
    void 计划模式白名单内命令放行() {
        for (String cmd : new String[]{
                "ls -la", "cat foo.txt", "type pom.xml", "head -n 20 a.txt",
                "grep -r todo src", "rg \"PlanMode\" src", "pwd", "echo hello",
                "tree src", "wc -l Main.java", "which java"}) {
            assertEquals(PermissionResult.Type.ALLOW, typeOf(PermissionGate.check("bash",
                    Map.of("command", cmd), PLAN)), "应放行: " + cmd);
        }
    }

    @Test
    void 计划模式git只读子命令放行() {
        for (String cmd : new String[]{
                "git status", "git log -5", "git diff abc def", "git show HEAD",
                "git blame src/App.java", "git rev-parse HEAD", "git branch -a",
                "git branch", "git tag --list", "git ls-files", "git remote -v"}) {
            assertEquals(PermissionResult.Type.ALLOW, typeOf(PermissionGate.check("bash",
                    Map.of("command", cmd), PLAN)), "应放行: " + cmd);
        }
    }

    @Test
    void 计划模式git写形态被拒绝() {
        for (String cmd : new String[]{
                "git commit -m x", "git push", "git pull", "git checkout main",
                "git branch feature-x", "git branch -D old", "git tag v1.0",
                "git stash", "git reset --hard"}) {
            assertEquals(PermissionResult.Type.DENY, typeOf(PermissionGate.check("bash",
                    Map.of("command", cmd), PLAN)), "应拒绝: " + cmd);
        }
    }

    @Test
    void 计划模式版本查询命令放行() {
        for (String cmd : new String[]{"java -version", "mvn -v", "node -v", "python --version"}) {
            assertEquals(PermissionResult.Type.ALLOW, typeOf(PermissionGate.check("bash",
                    Map.of("command", cmd), PLAN)), "应放行: " + cmd);
        }
    }

    @Test
    void 计划模式白名单外命令被拒绝() {
        for (String cmd : new String[]{
                "npm install", "mvn test", "mvn clean", "rm -rf build",
                "curl http://example.com", "touch a.txt", "mkdir x", "find . -delete"}) {
            assertEquals(PermissionResult.Type.DENY, typeOf(PermissionGate.check("bash",
                    Map.of("command", cmd), PLAN)), "应拒绝: " + cmd);
        }
    }

    @Test
    void 计划模式组合与重定向命令被拒绝() {
        for (String cmd : new String[]{
                "ls; rm -rf /", "cat a.txt && rm a.txt", "echo hi > out.txt",
                "cat a.txt >> b.txt", "cat a.txt | grep x", "echo `whoami`",
                "echo $(rm -rf /)", "git log; git push"}) {
            assertEquals(PermissionResult.Type.DENY, typeOf(PermissionGate.check("bash",
                    Map.of("command", cmd), PLAN)), "应拒绝: " + cmd);
        }
    }

    @Test
    void 计划模式Gate1硬拒绝列表优先于白名单() {
        // sudo 在硬拒绝列表：即使语法上像白名单命令组合，也必须先被 Gate 1 拦下
        assertEquals(PermissionResult.Type.DENY,
                typeOf(PermissionGate.check("bash", Map.of("command", "sudo ls"), PLAN)));
        assertEquals(PermissionResult.Type.DENY,
                typeOf(PermissionGate.check("bash", Map.of("command", "sudo cat /etc/shadow"), PLAN)));
    }

    @Test
    void 计划模式拒绝消息包含PlanMode指引() {
        PermissionResult r = PermissionGate.check("write", Map.of(), PLAN);
        assertTrue(r.message().contains("计划模式"), "拒绝消息应解释计划模式约束");
    }

    // ═══════════════ 默认模式回归：行为与旧实现一致 ═══════════════

    @Test
    void 默认模式写类工具仍为WARN() {
        assertEquals(PermissionResult.Type.WARN,
                typeOf(PermissionGate.check("write", Map.of(), DEFAULT)));
        assertEquals(PermissionResult.Type.WARN,
                typeOf(PermissionGate.check("edit", Map.of(), DEFAULT)));
    }

    @Test
    void 默认模式bash仍为WARN确认() {
        assertEquals(PermissionResult.Type.WARN,
                typeOf(PermissionGate.check("bash", Map.of("command", "npm install"), DEFAULT)));
    }

    @Test
    void 默认模式只读工具放行_未知工具WARN() {
        assertEquals(PermissionResult.Type.ALLOW,
                typeOf(PermissionGate.check("read", Map.of("path", "a.txt"), DEFAULT)));
        assertEquals(PermissionResult.Type.WARN,
                typeOf(PermissionGate.check("mcp__github__create_issue", Map.of(), DEFAULT)));
    }

    @Test
    void 默认模式Gate1硬拒绝照旧() {
        assertEquals(PermissionResult.Type.DENY,
                typeOf(PermissionGate.check("bash", Map.of("command", "sudo rm file"), DEFAULT)));
    }
}
