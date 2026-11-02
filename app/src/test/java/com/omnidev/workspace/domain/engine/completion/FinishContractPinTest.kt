package com.omnidev.workspace.domain.engine.completion

import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.domain.engine.PipelineRecovery
import com.omnidev.workspace.domain.engine.RunEvidenceLedger
import org.junit.Assert.*
import org.junit.Test

class FinishContractPinTest {
    @Test fun `incomplete prefix literals stay identical across runtime and protocol`() {
        assertEquals(PipelineRecovery.INCOMPLETE_PREFIX, FinishDispatch.INCOMPLETE_PREFIX)
        assertEquals(PipelineRecovery.INCOMPLETE_PREFIX, FinishStatus.INCOMPLETE.label)
    }

    @Test fun `mixed batch is rejected whole and every call gets a paired result`() {
        val id = RunIdentity("run-m", null)
        val ledger = RunEvidenceLedger(id.runId, null)
        val policy = FinalizationPolicy(true, true)
        val gate = FinishGate(ledger, RunState("goal", ledger, emptyList(), true), policy, id)
        val finish = ToolCall("f1", FinishTool.NAME, mapOf("status" to "completed", "summary" to "x"))
        val other = ToolCall("t1", "read_file", mapOf("path" to "/a"))
        val step = FinishDispatch.explicit(gate, finish, CompletionResponse("", listOf(other, finish)))
        assertNull(step.publish)
        assertEquals(2, step.append[1].toolResults.size)
        assertTrue(step.append[1].toolResults.all { it.isError })
    }

    @Test fun `implicit incomplete prefix is published as incomplete without verification`() {
        val id = RunIdentity("run-i", null)
        val ledger = RunEvidenceLedger(id.runId, null)
        val gate = FinishGate(ledger, RunState("g", ledger, emptyList(), true), FinalizationPolicy(true, true), id)
        val step = FinishDispatch.implicit(gate, "[Incomplete] stalled")
        assertEquals(FinishStatus.INCOMPLETE, step.publish!!.status)
    }
}
