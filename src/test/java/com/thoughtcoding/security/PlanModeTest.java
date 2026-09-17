package com.thoughtcoding.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PlanMode 全局状态机的单元测试。
 *
 * <p>测试直接操纵进程级静态状态，{@code @AfterEach} 复位为 DEFAULT，
 * 避免污染同 JVM 内的其他测试（如 PermissionGate 经旧签名读取全局模式）。
 */
class PlanModeTest {

    @AfterEach
    void reset() {
        PlanMode.exit();
    }

    @Test
    void 默认处于DEFAULT模式() {
        assertFalse(PlanMode.isActive());
        assertEquals(PlanMode.Mode.DEFAULT, PlanMode.current());
    }

    @Test
    void enter后激活_exit后回到DEFAULT() {
        PlanMode.enter();
        assertTrue(PlanMode.isActive());
        assertEquals(PlanMode.Mode.PLAN, PlanMode.current());

        PlanMode.exit();
        assertFalse(PlanMode.isActive());
        assertEquals(PlanMode.Mode.DEFAULT, PlanMode.current());
    }

    @Test
    void enter与exit均幂等() {
        PlanMode.enter();
        PlanMode.enter();
        assertTrue(PlanMode.isActive());

        PlanMode.exit();
        PlanMode.exit();
        assertFalse(PlanMode.isActive());
    }

    @Test
    void 未激活时exit保持DEFAULT不变() {
        PlanMode.exit();
        assertFalse(PlanMode.isActive());
    }
}
