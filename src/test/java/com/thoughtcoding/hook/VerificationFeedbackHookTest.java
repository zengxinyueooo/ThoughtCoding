package com.thoughtcoding.hook;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class VerificationFeedbackHookTest {
    private HookContext stop() { return HookContext.forStop(null, List.of()); }

    @Test void failedVerificationFeedsEvidenceThenPassAllowsCompletion() {
        AtomicInteger checks = new AtomicInteger();
        var hook = new VerificationFeedbackHook(context -> checks.getAndIncrement() == 0
                ? new VerificationFeedbackHook.Check(VerificationFeedbackHook.Status.FAILED, "normal nested write failed")
                : new VerificationFeedbackHook.Check(VerificationFeedbackHook.Status.PASSED, "2 tests passed"), 2);
        HookResult first = hook.execute(stop());
        assertTrue(first.isContinueLoop());
        assertTrue(first.message().contains("normal nested write failed"));
        assertFalse(hook.execute(stop()).isContinueLoop());
    }

    @Test void boundedRepairsAndNewTurnResetPreventUnboundedLoops() {
        var hook = new VerificationFeedbackHook(context -> new VerificationFeedbackHook.Check(
                VerificationFeedbackHook.Status.FAILED, "failure"), 1);
        assertTrue(hook.execute(stop()).isContinueLoop());
        assertFalse(hook.execute(stop()).isContinueLoop());
        hook.execute(HookContext.forUserPrompt(null, List.of(), "next task"));
        assertTrue(hook.execute(stop()).isContinueLoop());
    }

    @Test void infrastructureFailureIsNotClaimedAsTestFailureOrSuccess() {
        var hook = new VerificationFeedbackHook(context -> { throw new IllegalStateException("not logged"); }, 1);
        HookResult result = hook.execute(stop());
        assertTrue(result.isContinueLoop());
        assertTrue(result.message().contains("UNAVAILABLE"));
        assertFalse(result.message().contains("not logged"));
    }
}
