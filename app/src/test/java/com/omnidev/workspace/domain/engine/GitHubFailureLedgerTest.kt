package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.ToolExecutionResult
import org.junit.Assert.*
import org.junit.Test

class GitHubFailureLedgerTest {
    private fun call(action: String, repo: String = "a/b", path: String? = null) = ToolCall("id", "github_manager", buildMap {
        put("action", action); put("repo", repo); if (path != null) put("path", path)
    })
    private val failure = ToolExecutionResult("HTTP404", true, classification = "GITHUB_RESOURCE_NOT_FOUND")
    private val success = ToolExecutionResult("OK")
    @Test fun `unrelated tool or repo listing cannot erase a failed file read`() {
        val ledger = GitHubFailureLedger()
        ledger.observe(call("read_file", path = "README.md"), failure)
        ledger.observe(call("list_repos", repo = ""), success)
        ledger.observe(call("get_repo"), success)
        ledger.observe(ToolCall("id", "omni_link", mapOf("action" to "list_extensions")), success)
        assertNotNull(ledger.unresolved())
        ledger.observe(call("read_file", path = "README.md"), success)
        assertNull(ledger.unresolved())
    }
    @Test fun `repeated identical failures trip while different targets remain distinct`() {
        val ledger = GitHubFailureLedger()
        ledger.observe(call("get_repo"), failure)
        ledger.observe(call("get_repo", "a/c"), failure)
        assertFalse(ledger.repeatedFailure())
        ledger.observe(call("get_repo"), failure)
        assertTrue(ledger.repeatedFailure())
    }
    @Test fun `corrected legacy read clears the same typed target`() {
        val ledger = GitHubFailureLedger()
        ledger.observe(ToolCall("id", "github_manager", mapOf("action" to "api_request", "title" to "GET", "repo" to "a/b")), failure)
        ledger.observe(call("get_repo"), success)
        assertNull(ledger.unresolved())
    }
}
