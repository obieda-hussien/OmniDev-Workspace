package com.omnidev.workspace.data.tools

import org.junit.Assert.*
import org.junit.Test

class AgentBrowserPolicyTest {
    @Test fun `agent cannot export cookie storage raw DOM or execute arbitrary scripts`() {
        for (action in listOf("execute_js", "get_cookies", "get_html",
            "get_local_storage", "set_cookie", "inject_js_persistent", "get_network_log")) {
            val decision = AgentBrowserPolicy.evaluate(action, emptyMap(), "https://example.com/", "Example")
            assertEquals(action, "BROWSER_SESSION_SECRET_DENIED", decision?.classification)
            assertFalse(decision!!.handoff)
        }
    }

    @Test fun `login account page requires human control for inputs and screenshots`() {
        for (action in listOf("type_text", "click", "fill_form", "find_element", "screenshot")) {
            val decision = AgentBrowserPolicy.evaluate(action, mapOf("selector" to "button"),
                "https://www.fiverr.com/login", "Sign in")
            assertEquals(action, "USER_ACTION_REQUIRED", decision?.classification)
            assertTrue(decision!!.handoff)
        }
    }

    @Test fun `secret selectors and form fields require takeover on otherwise normal pages`() {
        assertEquals("USER_ACTION_REQUIRED",
            AgentBrowserPolicy.evaluate("type_text", mapOf("selector" to "input[name=password]"),
                "https://example.com/", "Home")?.classification)
        assertEquals("USER_ACTION_REQUIRED",
            AgentBrowserPolicy.evaluate("fill_form", mapOf("fields" to """{"#otp":"123456"}"""),
                "https://example.com/", "Home")?.classification)
    }

    @Test fun `submit form always needs deliberate user takeover`() {
        assertEquals("USER_ACTION_REQUIRED",
            AgentBrowserPolicy.evaluate("submit_form", mapOf("selector" to "#send"),
                "https://example.com/contact", "Contact")?.classification)
    }

    @Test fun `public browsing and non-sensitive navigation remain permitted`() {
        assertNull(AgentBrowserPolicy.evaluate("navigate",
            mapOf("url" to "https://example.com/jobs?q=developer"), "", ""))
        assertNull(AgentBrowserPolicy.evaluate("get_dom", emptyMap(),
            "https://example.com/jobs", "Job listings"))
        assertNull(AgentBrowserPolicy.evaluate("click", mapOf("selector" to "#next-page"),
            "https://example.com/jobs", "Job listings"))
    }

    @Test fun `navigation with embedded credentials or access tokens is denied`() {
        for (url in listOf("https://user:pass@example.com/", "https://example.com/?access_token=abc")) {
            val decision = AgentBrowserPolicy.evaluate("navigate", mapOf("url" to url), "", "")
            assertEquals("BROWSER_SENSITIVE_URL_DENIED", decision?.classification)
            assertFalse(decision!!.handoff)
        }
    }

    @Test fun `manual user browser can retain capabilities without granting them to agents`() {
        assertTrue(BrowserToolPolicy.allowed("PRO", "execute_js"))
        assertEquals("BROWSER_SESSION_SECRET_DENIED",
            AgentBrowserPolicy.evaluate("execute_js", emptyMap(), "https://example.com/", "")?.classification)
    }
}
