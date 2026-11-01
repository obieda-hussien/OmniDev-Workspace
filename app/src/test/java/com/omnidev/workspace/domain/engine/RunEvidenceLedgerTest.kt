package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.model.ToolCallResult
import org.junit.Assert.*
import org.junit.Test

class RunEvidenceLedgerTest {
    private fun record(ledger: RunEvidenceLedger, id: String, tool: String, args: Map<String, String>, output: String, error: Boolean = false) =
        ledger.record(ToolCall(id, tool, args), ToolCallResult(id, tool, output, error))

    @Test fun `empty find output is recorded as EMPTY not success`() {
        val ledger = RunEvidenceLedger()
        record(ledger, "1", "find_files", mapOf("directory" to "/a", "name_pattern" to "*.kt"), "(no output)")
        val text = ledger.render()
        assertTrue(text.startsWith("[RUN LEDGER"))
        assertTrue(text.contains("find_files") && text.contains("EMPTY"))
        assertTrue(ledger.groundingNote("done").contains("find_files:empty"))
    }

    @Test fun `file names never returned by a tool are flagged`() {
        val ledger = RunEvidenceLedger()
        record(ledger, "1", "agent_runtime", mapOf("action" to "file_list"), "MainActivity.kt\nREADME.md")
        val flagged = ledger.unverifiedReferences("Read MainActivity.kt and Theme.kt in /app/src/Color.kt")
        assertEquals(listOf("/app/src/Color.kt", "Theme.kt"), flagged)
    }

    @Test fun `code fences and user supplied names are not flagged`() {
        val ledger = RunEvidenceLedger()
        val answer = "See `Foo.kt`:\n```kotlin\nimport Bar.kt\n```"
        assertEquals(listOf("Foo.kt"), ledger.unverifiedReferences(answer))
        assertTrue(ledger.unverifiedReferences(answer, extraEvidence = "please open Foo.kt").isEmpty())
    }

    @Test fun `ledger keeps problems and drops old successes into counts`() {
        val ledger = RunEvidenceLedger()
        repeat(60) { record(ledger, "$it", "read_file", mapOf("path" to "/f$it"), "ok $it") }
        record(ledger, "e", "git_manager", mapOf("action" to "push"), "denied", error = true)
        val text = ledger.render(maxOk = 5)
        assertTrue(text.contains("read_file×55"))
        assertTrue(text.contains("git_manager") && text.contains("ERROR"))
        assertFalse(text.contains("/f0 "))
    }
}
