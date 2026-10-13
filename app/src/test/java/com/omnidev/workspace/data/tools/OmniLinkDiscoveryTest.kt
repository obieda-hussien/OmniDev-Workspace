package com.omnidev.workspace.data.tools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OmniLinkDiscoveryTest {
    private val extension = JSONObject().put("extension_id", "app/test").put("connected", true)
        .put("manifest", JSONObject().put("capabilities", JSONArray((0 until 50).map { index ->
            JSONObject().put("name", "app.action$index").put("description", "Description ".repeat(100))
                .put("inputSchema", JSONObject().put("large", "x".repeat(5000)))
                .put("requiresConfirmation", true).put("risk", "MEDIUM")
        })))
    @Test fun `discovery is compact paged and keeps confirmation metadata`() {
        val first = OmniLinkDiscovery.page(listOf(extension), emptyMap()) { _, _ -> true }
        assertEquals(8, first.getInt("count"))
        assertEquals(50, first.getInt("total"))
        assertEquals(8, first.getInt("next_offset"))
        val cap = first.getJSONArray("capabilities").getJSONObject(0)
        assertFalse(cap.has("inputSchema"))
        assertTrue(cap.getBoolean("requiresConfirmation"))
        assertEquals("MEDIUM", cap.getString("risk"))
        assertTrue(first.toString().length < 4000)
        val next = OmniLinkDiscovery.page(listOf(extension), mapOf("offset" to "8")) { _, _ -> true }
        assertEquals("app.action8", next.getJSONArray("capabilities").getJSONObject(0).getString("name"))
    }
    @Test fun `query and policy exclude unavailable actions without false absence`() {
        val page = OmniLinkDiscovery.page(listOf(extension), mapOf("query" to "action4", "limit" to "999")) { _, name -> name != "app.action4" }
        assertEquals(10, page.getInt("count"))
        assertTrue(page.isNull("next_offset"))
        assertEquals("app.action40", page.getJSONArray("capabilities").getJSONObject(0).getString("name"))
    }
}
