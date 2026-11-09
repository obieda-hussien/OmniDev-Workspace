package com.omnidev.workspace.data.tools

import org.junit.Assert.*
import org.junit.Test

class BrowserToolPolicyTest {
    @Test fun unsupportedSnapshotIsRejectedBeforeAnyBrowserExecution() {
        val definition = ToolDefinition("headless_browser", "Browser", listOf(ToolParameter("action", "string", "Exact action", true, allowedValues = BrowserToolPolicy.allActions.sorted())))
        val invalid = com.omnidev.workspace.domain.engine.ToolCallPreflight(listOf(definition)).check(
            com.omnidev.workspace.data.model.ToolCall("c", "headless_browser", mapOf("action" to "snapshot")))
        assertEquals("INVALID_TOOL_ARGUMENTS", invalid?.classification)
        assertFalse(BrowserToolPolicy.allowed("PRO", "snapshot"))
        assertTrue(BrowserToolPolicy.allowed("PRO", "get_dom"))
        assertTrue(BrowserToolPolicy.allowed("PRO", "screenshot"))
    }
    @Test fun liteCannotExecuteScriptsOrExportSessionSecrets() {
        for (action in listOf("execute_js", "get_cookies", "set_cookie", "get_local_storage", "inject_js_persistent"))
            assertFalse(action, BrowserToolPolicy.allowed("LITE", action))
    }
    @Test fun researchAndReviewedNamedInteractionsRemainAvailable() {
        for (action in listOf("navigate", "extract_table", "screenshot", "new_incognito_session", "fill_form"))
            assertTrue(action, BrowserToolPolicy.allowed("LITE", action))
        assertTrue("submit_form" in BrowserToolPolicy.mutations)
        assertFalse(BrowserToolPolicy.allowed("UNKNOWN", "navigate"))
    }
}
