package com.omnidev.workspace.data.tools

import org.junit.Assert.*
import org.junit.Test

class BrowserToolPolicyTest {
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
