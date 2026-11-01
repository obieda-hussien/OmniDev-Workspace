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

    @Test fun `code fences and unverified user supplied names are handled correctly`() {
        val ledger = RunEvidenceLedger()
        val answer = "See `Foo.kt`:\n```kotlin\nimport Bar.kt\n```"
        assertEquals(listOf("Foo.kt"), ledger.unverifiedReferences(answer))
        assertEquals(
            listOf("Foo.kt"),
            ledger.unverifiedReferences(
                answer,
                extraEvidence = listOf(ToolCallResult("failed", "read_file", "please open Foo.kt", true))
            )
        )
        assertTrue(
            ledger.unverifiedReferences(
                answer,
                extraEvidence = listOf(ToolCallResult("prior", "read_file", "File: Foo.kt\\ncontents", false))
            ).isEmpty()
        )

        val verified = RunEvidenceLedger()
        record(verified, "2", "read_file", mapOf("path" to "/workspace/Foo.kt"), "File: Foo.kt\ncontents")
        assertTrue(verified.unverifiedReferences(answer).isEmpty())
    }

    @Test fun `absolute path evidence does not validate a different directory with the same filename`() {
        val ledger = RunEvidenceLedger()
        record(
            ledger,
            "path",
            "read_file",
            mapOf("path" to "/workspace/Foo.kt"),
            "Read file /workspace/Foo.kt successfully"
        )
        assertEquals(
            listOf("/another/folder/Foo.kt"),
            ledger.unverifiedReferences("Read /another/folder/Foo.kt")
        )
        assertTrue(ledger.unverifiedReferences("Read /workspace/Foo.kt").isEmpty())
    }

    @Test fun `absolute path does not match a longer filename prefix`() {
        val ledger = RunEvidenceLedger()
        record(
            ledger,
            "suffix",
            "read_file",
            mapOf("path" to "/workspace/Foo.kt.backup"),
            "Read file /workspace/Foo.kt.backup"
        )
        assertEquals(
            listOf("/workspace/Foo.kt"),
            ledger.unverifiedReferences("Read /workspace/Foo.kt")
        )
    }

    @Test fun `a later successful call recovers an earlier transient failure`() {
        val ledger = RunEvidenceLedger()
        record(ledger, "failed", "agent_runtime", mapOf("action" to "shell_script"), "SHIZUKU_UNAVAILABLE", error = true)
        record(ledger, "probe", "agent_runtime", mapOf("action" to "env_probe"), "Fresh probe")
        record(ledger, "retry", "agent_runtime", mapOf("action" to "shell_script"), "Completed")
        assertTrue(ledger.groundingNote("Execution completed").isEmpty())
    }

    @Test fun `actions skipped after steering are not reported as execution failures`() {
        val ledger = RunEvidenceLedger()
        record(ledger, "skip", "write_file", mapOf("path" to "/workspace/obsolete.kt"),
            "Skipped: the user redirected this run before this action started.", error = true)
        assertTrue(ledger.groundingNote("Kept the requested file").isEmpty())
        assertTrue(ledger.render().contains("SKIPPED"))
    }

    @Test fun `tool arguments do not prove a file was returned`() {
        val ledger = RunEvidenceLedger()
        record(ledger, "3", "read_file", mapOf("path" to "/workspace/Secret.kt"),
            "File not found: /workspace/Secret.kt; permission denied", error = true)
        assertEquals(listOf("Secret.kt"), ledger.unverifiedReferences("Successfully read Secret.kt"))
    }

    @Test fun `ledger omits raw command and URL arguments that may contain secrets`() {
        val ledger = RunEvidenceLedger()
        record(
            ledger,
            "secret",
            "http_request",
            mapOf(
                "action" to "get",
                "path" to "/workspace/Foo.kt",
                "url" to "https://example.com?token=TOPSECRET_VALUE",
                "command" to "curl -H 'Authorization: Bearer VERY_SECRET_BEARER'"
            ),
            "ok"
        )
        val rendered = ledger.render()
        assertTrue(rendered.contains("path=/workspace/Foo.kt"))
        assertFalse(rendered.contains("TOPSECRET_VALUE"))
        assertFalse(rendered.contains("VERY_SECRET_BEARER"))
        assertFalse(rendered.contains("url="))
        assertFalse(rendered.contains("command="))
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
