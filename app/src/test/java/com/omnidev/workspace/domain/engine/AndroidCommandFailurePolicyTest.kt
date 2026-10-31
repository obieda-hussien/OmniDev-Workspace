package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.tools.ToolExecutionResult
import org.junit.Assert.*
import org.junit.Test

class AndroidCommandFailurePolicyTest {
    @Test fun missingAndroidShellBinaryStopsRecovery() {
        for (name in listOf("privileged_tool", "run_terminal", "shizuku_command")) {
            assertTrue(AndroidCommandFailurePolicy.shouldStop(
                name,
                ToolExecutionResult("media: inaccessible or not found", isError = true, classification = "COMMAND_NOT_FOUND")
            ))
        }
    }

    @Test fun backendOutageIsNotMisclassifiedAsMissingBinary() {
        assertFalse(AndroidCommandFailurePolicy.shouldStop(
            "privileged_tool",
            ToolExecutionResult("Shizuku permission required", isError = true, classification = "SHIZUKU_PERMISSION_REQUIRED")
        ))
    }

    @Test fun ordinaryToolsAreNotStoppedByCommandPolicy() {
        assertFalse(AndroidCommandFailurePolicy.shouldStop(
            "agent_runtime",
            ToolExecutionResult("python: command not found", isError = true, classification = "COMMAND_NOT_FOUND")
        ))
    }

    @Test fun successfulCommandIsNeverTreatedAsMissing() {
        assertFalse(AndroidCommandFailurePolicy.shouldStop(
            "privileged_tool",
            ToolExecutionResult("completed", isError = false, classification = "COMMAND_NOT_FOUND")
        ))
    }
}
