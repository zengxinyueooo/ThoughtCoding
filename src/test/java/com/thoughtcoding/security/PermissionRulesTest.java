package com.thoughtcoding.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 声明式权限规则的单元测试（全部走四参纯函数，不触碰 PlanMode / 全局规则）。
 *
 * <p>铁律：
 * <ul>
 *   <li>层级不可翻转：bash 硬拒绝与计划模式矩阵永远压过用户 allow 规则</li>
 *   <li>规则内部优先级：deny &gt; ask &gt; allow</li>
 *   <li>无命中回落默认矩阵（回归：write/bash 仍 WARN，read 仍 ALLOW）</li>
 *   <li>非法规则条目跳过不抛</li>
 * </ul>
 */
class PermissionRulesTest {

    private static final boolean PLAN = true;
    private static final boolean DEFAULT = false;

    private static PermissionResult.Type type(PermissionResult r) {
        return r.type();
    }

    private static Map<String, Object> bash(String command) {
        return Map.of("command", command);
    }

    private static Map<String, Object> path(String p) {
        return Map.of("path", p);
    }

    // ═══════════════ 规则层生效 ═══════════════

    @Test
    void allow规则让bash静默放行() {
        PermissionRules rules = PermissionRules.parse(null, null, List.of("Bash(mvn *)"));

        assertEquals(PermissionResult.Type.ALLOW, type(PermissionGate.check("bash", bash("mvn test"), DEFAULT, rules)));
        assertEquals(PermissionResult.Type.ALLOW,
                type(PermissionGate.check("bash", bash("mvn -q -ntp test"), DEFAULT, rules)));
        // 未命中规则的命令仍走默认矩阵 → WARN
        assertEquals(PermissionResult.Type.WARN, type(PermissionGate.check("bash", bash("git status"), DEFAULT, rules)));
    }

    @Test
    void 裸工具名规则命中整个工具() {
        PermissionRules rules = PermissionRules.parse(null, null, List.of("Read"));

        assertEquals(PermissionResult.Type.ALLOW, type(PermissionGate.check("read", path("src/Main.java"), DEFAULT, rules)));
    }

    @Test
    void ask规则把默认放行的工具变成强制确认() {
        PermissionRules rules = PermissionRules.parse(null, List.of("subAgent"), null);

        assertEquals(PermissionResult.Type.WARN, type(PermissionGate.check("subAgent", Map.of(), DEFAULT, rules)));
    }

    // ═══════════════ 优先级：deny > ask > allow ═══════════════

    @Test
    void deny压过allow() {
        PermissionRules rules = PermissionRules.parse(
                List.of("Bash(git push*)"), null, List.of("Bash(git *)"));

        assertEquals(PermissionResult.Type.DENY, type(PermissionGate.check("bash", bash("git push origin main"), DEFAULT, rules)));
        assertEquals(PermissionResult.Type.ALLOW, type(PermissionGate.check("bash", bash("git status"), DEFAULT, rules)));
    }

    @Test
    void ask压过allow() {
        PermissionRules rules = PermissionRules.parse(
                null, List.of("Bash(git push --force*)"), List.of("Bash(git *)"));

        assertEquals(PermissionResult.Type.WARN,
                type(PermissionGate.check("bash", bash("git push --force origin main"), DEFAULT, rules)));
        assertEquals(PermissionResult.Type.ALLOW, type(PermissionGate.check("bash", bash("git pull"), DEFAULT, rules)));
    }

    // ═══════════════ 安全底线不可被规则解锁 ═══════════════

    @Test
    void allow规则放不出硬拒绝命令() {
        PermissionRules rules = PermissionRules.parse(null, null, List.of("Bash(*)"));

        assertEquals(PermissionResult.Type.DENY, type(PermissionGate.check("bash", bash("rm -rf /"), DEFAULT, rules)));
        assertEquals(PermissionResult.Type.DENY, type(PermissionGate.check("bash", bash("sudo apt install x"), DEFAULT, rules)));
    }

    @Test
    void allow规则绕不开计划模式写禁令() {
        PermissionRules rules = PermissionRules.parse(null, null, List.of("Write", "Edit", "Bash(mvn *)"));

        assertEquals(PermissionResult.Type.DENY, type(PermissionGate.check("write", path("a.txt"), PLAN, rules)));
        assertEquals(PermissionResult.Type.DENY, type(PermissionGate.check("bash", bash("mvn test"), PLAN, rules)));
    }

    // ═══════════════ 路径说明符与健壮性 ═══════════════

    @Test
    void 路径通配匹配deny规则() {
        PermissionRules rules = PermissionRules.parse(List.of("Write(.env*)"), null, null);

        assertEquals(PermissionResult.Type.DENY, type(PermissionGate.check("write", path(".env"), DEFAULT, rules)));
        assertEquals(PermissionResult.Type.DENY, type(PermissionGate.check("write", path(".env.local"), DEFAULT, rules)));
        // 其他路径回落默认矩阵 → WARN
        assertEquals(PermissionResult.Type.WARN, type(PermissionGate.check("write", path("src/Main.java"), DEFAULT, rules)));
    }

    @Test
    void 非法规则跳过不抛且不影响其余规则() {
        PermissionRules rules = PermissionRules.parse(
                List.of("Bash(没有闭合括号", ""), null, List.of("Bash(dir*)"));

        assertNotNull(rules);
        assertEquals(PermissionResult.Type.ALLOW, type(PermissionGate.check("bash", bash("dir /b"), DEFAULT, rules)));
    }

    @Test
    void 空规则等价于默认矩阵() {
        PermissionRules rules = PermissionRules.empty();

        assertEquals(PermissionResult.Type.WARN, type(PermissionGate.check("write", path("a.txt"), DEFAULT, rules)));
        assertEquals(PermissionResult.Type.WARN, type(PermissionGate.check("bash", bash("ls"), DEFAULT, rules)));
        assertEquals(PermissionResult.Type.ALLOW, type(PermissionGate.check("todo_write", Map.of(), DEFAULT, rules)));
    }

    @Test
    void 匹配大小写不敏感() {
        PermissionRules rules = PermissionRules.parse(null, null, List.of("Bash(MVN *)"));

        assertEquals(PermissionResult.Type.ALLOW, type(PermissionGate.check("bash", bash("mvn test"), DEFAULT, rules)));
    }
}
