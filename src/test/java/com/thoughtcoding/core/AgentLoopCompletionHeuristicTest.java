package com.thoughtcoding.core;

import com.thoughtcoding.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopCompletionHeuristicTest {

    @Test
    void recognizesCommonVerificationCommands() {
        assertTrue(AgentLoop.isVerificationCommand(call("mvn -q test")));
        assertTrue(AgentLoop.isVerificationCommand(call("python -m pytest tests/test_api.py")));
        assertTrue(AgentLoop.isVerificationCommand(call("git diff; cargo test")));
    }

    @Test
    void doesNotTreatBuildOrInspectionAsCompletedVerification() {
        assertFalse(AgentLoop.isVerificationCommand(call("mvn package -DskipTests")));
        assertFalse(AgentLoop.isVerificationCommand(call("git status")));
        assertFalse(AgentLoop.isVerificationCommand(new ToolCall(
                "read", Map.of("file_path", "README.md"), null, false, 0, false, "read-1")));
    }

    private ToolCall call(String command) {
        return new ToolCall("bash", Map.of("command", command), null, false, 0, false, "bash-1");
    }
}
