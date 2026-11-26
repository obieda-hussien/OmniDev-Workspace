package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.domain.engine.completion.CompletionReport
import com.omnidev.workspace.domain.engine.completion.EvidenceKinds
import com.omnidev.workspace.domain.engine.completion.EvidenceRef
import com.omnidev.workspace.domain.engine.completion.FinishStatus
import org.junit.Assert.*
import org.junit.Test

class ScheduledRunVerificationTest {
    @Test fun `completed effects and uncertain outcomes gate automatic retry`() {
        val write = EvidenceRef("call", 1, "write_file", "path=report.md", RunEvidenceLedger.Outcome.OK,
            EvidenceKinds.Kind.MUTATION, "run", null)
        assertTrue(ScheduledRunVerification.blocksReplay(write, null))
        assertTrue(ScheduledRunVerification.blocksReplay(null, "MUTATION_OUTCOME_UNKNOWN"))
        assertTrue(ScheduledRunVerification.blocksReplay(null, "PRIOR_MUTATION_OUTCOME_UNKNOWN"))
        assertTrue(ScheduledRunVerification.blocksReplay(null, "USER_ACTION_REQUIRED"))
        assertFalse(ScheduledRunVerification.blocksReplay(write.copy(kind = EvidenceKinds.Kind.READ), "TOOL_TRANSPORT_FAILURE"))
        assertFalse(ScheduledRunVerification.blocksReplay(write.copy(outcome = RunEvidenceLedger.Outcome.SKIPPED), null))
    }

    @Test fun `connected service result with no receipt text still blocks scheduled replay`() {
        val effect = ToolCall("mcp-call", "mcp_example_create_record", emptyMap())
        val evidence = EvidenceRef(effect.id, 1, effect.name, "", RunEvidenceLedger.Outcome.EMPTY,
            EvidenceKinds.classify(effect.name), "run", null)
        assertEquals(EvidenceKinds.Kind.OTHER, evidence.kind)
        assertTrue(ScheduledRunVerification.blocksReplay(evidence, null, !ToolBatchPolicy.isReadOnly(effect)))
        assertFalse(ScheduledRunVerification.blocksReplay(evidence.copy(outcome = RunEvidenceLedger.Outcome.ERROR),
            "TOOL_TRANSPORT_FAILURE"))
    }

    @Test fun `authorized GitHub file read does not block scheduled replay`() {
        val call = ToolCall("read-call", "github_manager", mapOf("action" to "read_file"))
        val evidence = EvidenceRef(call.id, 1, call.name, "", RunEvidenceLedger.Outcome.OK,
            EvidenceKinds.classify(call.name), "run", null)
        assertFalse(ScheduledRunVerification.blocksReplay(evidence, null, !ToolBatchPolicy.isReadOnly(call)))
    }

    private fun report(status: FinishStatus, verified: Boolean) =
        CompletionReport("scheduled", null, status, "result", verified,
            emptyList(), emptyList(), emptyList(), "digest")

    @Test fun `plausible text is never completion evidence`() {
        val verdict = ScheduledRunVerification.evaluate("Email sent!", null, null)
        assertFalse(verdict.succeeded)
        assertTrue(verdict.reason!!.contains("UNVERIFIED"))
    }

    @Test fun `failed P0 criteria override a cheerful model answer`() {
        assertFalse(ScheduledRunVerification.evaluate("Done!", null, report(FinishStatus.COMPLETED, false)).succeeded)
        assertFalse(ScheduledRunVerification.evaluate("Done!", null, report(FinishStatus.INCOMPLETE, false)).succeeded)
    }

    @Test fun `verified P0 completion and an actual response permit success`() {
        assertTrue(ScheduledRunVerification.evaluate("Confirmed result", null,
            report(FinishStatus.COMPLETED, true)).succeeded)
        assertFalse(ScheduledRunVerification.evaluate("", null,
            report(FinishStatus.COMPLETED, true)).succeeded)
    }

    @Test fun `runtime error cannot be overwritten by a successful-looking P0 report`() {
        val verdict = ScheduledRunVerification.evaluate("Done", "network unavailable",
            report(FinishStatus.COMPLETED, true))
        assertFalse(verdict.succeeded)
        assertEquals("network unavailable", verdict.reason)
    }
}
