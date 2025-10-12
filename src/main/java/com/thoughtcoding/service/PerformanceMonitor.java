package com.thoughtcoding.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 性能监控器，跟踪请求数、令牌数、工具调用数和执行时间
 */
public class PerformanceMonitor {
    private Instant startTime;
    private final AtomicLong totalRequests;
    private final AtomicLong totalTokens;
    private final AtomicLong totalToolCalls;

    public PerformanceMonitor() {
        this.totalRequests = new AtomicLong(0);
        this.totalTokens = new AtomicLong(0);
        this.totalToolCalls = new AtomicLong(0);
    }

    public void start() {
        this.startTime = Instant.now();
        totalRequests.incrementAndGet();
    }

    public PerformanceData stop() {
        if (startTime == null) {
            throw new IllegalStateException("Monitor not started");
        }

        Duration duration = Duration.between(startTime, Instant.now());
        return new PerformanceData(duration.toMillis(), totalRequests.get(), totalTokens.get(), totalToolCalls.get());
    }

    public void recordTokens(int tokens) {
        totalTokens.addAndGet(tokens);
    }

    public void recordToolCall() {
        totalToolCalls.incrementAndGet();
    }

    public void reset() {
        totalRequests.set(0);
        totalTokens.set(0);
        totalToolCalls.set(0);
    }

    public static class PerformanceData {
        private final long executionTimeMs;
        private final long totalRequests;
        private final long totalTokens;
        private final long totalToolCalls;

        public PerformanceData(long executionTimeMs, long totalRequests, long totalTokens, long totalToolCalls) {
            this.executionTimeMs = executionTimeMs;
            this.totalRequests = totalRequests;
            this.totalTokens = totalTokens;
            this.totalToolCalls = totalToolCalls;
        }

        // Getters
        public long getExecutionTimeMs() { return executionTimeMs; }
        public long getTotalRequests() { return totalRequests; }
        public long getTotalTokens() { return totalTokens; }
        public long getTotalToolCalls() { return totalToolCalls; }

        @Override
        public String toString() {
            return String.format("Performance{time=%dms, requests=%d, tokens=%d, tools=%d}",
                    executionTimeMs, totalRequests, totalTokens, totalToolCalls);
        }
    }
}