package com.thoughtcoding.service;

import com.thoughtcoding.core.CancelToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM 限流/瞬时错误重试判定矩阵的单元测试。
 *
 * <p>直接验证 {@link LangChainService} 的静态纯函数，不构造实例（构造会初始化模型）。
 * 决策铁律：限流/超时/网关类瞬时错误按指数退避重试；
 * 业务性错误（鉴权、参数、上下文超限）重试必然再失败，必须立即收敛失败路径。
 */
class LangChainServiceRetryTest {

    // ═══════════════ isRetryableError ═══════════════

    @Test
    void 限流类型异常可重试() {
        assertTrue(LangChainService.isRetryableError(
                new dev.langchain4j.exception.RateLimitException("TPM limit reached")));
    }

    @Test
    void 限流类消息可重试() {
        // 用户实际遇到的硅基流动限流报错
        assertTrue(LangChainService.isRetryableError(new RuntimeException(
                "{\"code\":50602,\"message\":\"Request was rejected due to rate limiting. "
                        + "Details: TPM limit reached.\"}")));
        assertTrue(LangChainService.isRetryableError(new RuntimeException("HTTP 429 Too Many Requests")));
        assertTrue(LangChainService.isRetryableError(new RuntimeException("Rate limit exceeded")));
    }

    @Test
    void 网络瞬时错误可重试() {
        assertTrue(LangChainService.isRetryableError(new RuntimeException("Read timeout")));
        assertTrue(LangChainService.isRetryableError(new RuntimeException("request timed out")));
        assertTrue(LangChainService.isRetryableError(new RuntimeException("Connection reset by peer")));
        assertTrue(LangChainService.isRetryableError(new RuntimeException("HTTP 503 Service Unavailable")));
    }

    @Test
    void 业务性错误不可重试() {
        assertFalse(LangChainService.isRetryableError(new RuntimeException("401 Unauthorized")));
        assertFalse(LangChainService.isRetryableError(new RuntimeException("Invalid API key")));
        assertFalse(LangChainService.isRetryableError(new RuntimeException("context length exceeded")));
        assertFalse(LangChainService.isRetryableError(new IllegalArgumentException("bad argument")));
        assertFalse(LangChainService.isRetryableError(null));
    }

    // ═══════════════ nextRetryDelayMs：指数退避 ═══════════════

    @Test
    void 退避序列按5s_20s_45s递增() {
        Throwable e = new RuntimeException("50602 TPM limit reached");
        assertEquals(5_000L, LangChainService.nextRetryDelayMs(e, 0, false, null));
        assertEquals(20_000L, LangChainService.nextRetryDelayMs(e, 1, false, null));
        assertEquals(45_000L, LangChainService.nextRetryDelayMs(e, 2, false, null));
    }

    @Test
    void 超过最大次数不再重试() {
        Throwable e = new RuntimeException("50602 TPM limit reached");
        assertNull(LangChainService.nextRetryDelayMs(e, LangChainService.MAX_STREAM_RETRIES, false, null));
    }

    @Test
    void 不可重试错误不重试() {
        assertNull(LangChainService.nextRetryDelayMs(new RuntimeException("401 Unauthorized"), 0, false, null));
    }

    @Test
    void 停止或取消时不重试() {
        Throwable e = new RuntimeException("50602 TPM limit reached");
        assertTrue(LangChainService.nextRetryDelayMs(e, 0, true, null) == null);

        CancelToken cancelled = new CancelToken();
        cancelled.cancel();
        assertNull(LangChainService.nextRetryDelayMs(e, 0, false, cancelled));
    }

    @Test
    void 未取消的token不影响重试() {
        Throwable e = new RuntimeException("50602 TPM limit reached");
        assertEquals(5_000L, LangChainService.nextRetryDelayMs(e, 0, false, new CancelToken()));
    }
}
