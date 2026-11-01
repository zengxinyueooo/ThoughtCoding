package com.thoughtcoding.eval;
import com.thoughtcoding.config.AppConfig;
import com.thoughtcoding.core.CancelToken;
import com.thoughtcoding.model.*;
import com.thoughtcoding.tool.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DispatcherCancelAcceptanceTest {
    ToolCall call() { return new ToolCall("slow", Map.of(), null, false,0,false,"id"); }
    @Test void cancelInterruptsToolAlreadyExecuting() throws Exception {
        var started = new CountDownLatch(1);
        var result = new AtomicReference<ToolResult>();
        var registry = new ToolRegistry(new AppConfig());
        registry.register(new BaseTool("slow", "fixture") {
            public ToolResult execute(String input) {
                started.countDown();
                try { Thread.sleep(8000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return ToolResult.success("done", 0);
            }
        });
        var token = new CancelToken();
        var dispatcher = new ToolDispatcher(registry);
        Thread thread = new Thread(() -> result.set(dispatcher.dispatch(call(), token)));
        thread.setDaemon(true); thread.start();
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS)); token.cancel(); thread.join(1500);
            assertFalse(thread.isAlive(), "dispatch must return promptly after cancellation");
            assertNotNull(result.get()); assertFalse(result.get().isSuccess());
        } finally { token.cancel(); thread.interrupt(); thread.join(1000); }
    }
    @Test void normalAndUnknownCallsRetainBehavior() {
        var registry = new ToolRegistry(new AppConfig());
        var dispatcher = new ToolDispatcher(registry);
        assertFalse(dispatcher.dispatch(call()).isSuccess());
        registry.register(new BaseTool("slow", "fixture") {
            public ToolResult execute(String input) { return ToolResult.success("ok", 0); }
        });
        assertTrue(dispatcher.dispatch(call()).isSuccess());
    }
}
