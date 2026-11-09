package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.domain.engine.RunEvidenceLedger
import com.omnidev.workspace.domain.engine.completion.*
import org.junit.Assert.*
import org.junit.Test

class MissingGitCapabilityTest {
    @Test fun `missing git before read-only commands is skipped not success or unresolved side effect`() {
        val cmd = "git status && git log -n 5 --oneline"
        val r = MissingGitCapability.classify(cmd, 127, "", "/system/bin/sh: git: inaccessible or not found")!!
        assertTrue(r.isError); assertFalse(r.retryable)
        assertEquals("TOOL_DEPENDENCY_MISSING", r.classification)
        val l = RunEvidenceLedger("run")
        l.record(ToolCall("git", "run_terminal", mapOf("command" to cmd)), ToolCallResult("git", "run_terminal", r.output, true))
        assertEquals(RunEvidenceLedger.Outcome.SKIPPED, l.snapshot().single().outcome)
        assertFalse(FinishVerifier.evaluate(VerificationContext("run", null, l.snapshot(), true), FinishRequest(FinishStatus.COMPLETED, "Inspected repository")).accepted)
        l.record(ToolCall("read", "github_manager", mapOf("action" to "read_file")), ToolCallResult("read", "github_manager", "Actual repository source"))
        assertTrue(FinishVerifier.evaluate(VerificationContext("run", null, l.snapshot(), true), FinishRequest(FinishStatus.COMPLETED, "Inspected repository")).accepted)
    }
    @Test fun `writes mixed scripts partial results and other errors are never downgraded`() {
        for (command in listOf("git push", "git status; echo changed > file", "git status || touch file", "touch file && git status", "$(touch file) git status"))
            assertNull(command, MissingGitCapability.classify(command, 127, "", "/system/bin/sh: git: not found"))
        assertNull(MissingGitCapability.classify("git status", 127, "partial results", "/system/bin/sh: git: not found"))
        assertNull(MissingGitCapability.classify("git status", 1, "", "fatal: permission denied"))
        assertNull(MissingGitCapability.classify("git status", 127, "", "transport outcome unknown"))
    }
}
