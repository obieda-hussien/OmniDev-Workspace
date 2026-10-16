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
    @Test fun `extension id scopes both capabilities and extension metadata before pagination`() {
        val other = JSONObject(extension.toString()).put("extension_id", "other/service")
        val page = OmniLinkDiscovery.page(listOf(other, extension), mapOf("extension_id" to "app/test", "offset" to "8")) { _, _ -> true }
        assertEquals(50, page.getInt("total"))
        assertEquals(1, page.getJSONArray("extensions").length())
        assertEquals("app/test", page.getJSONArray("capabilities").getJSONObject(0).getString("extension_id"))
        assertEquals("app.action8", page.getJSONArray("capabilities").getJSONObject(0).getString("name"))
        val missing = OmniLinkDiscovery.page(listOf(extension), mapOf("extension_id" to "missing/service")) { _, _ -> true }
        assertFalse(missing.getBoolean("ok"))
        assertEquals("extension_not_found", missing.getString("code"))
        assertEquals("app/test", missing.getJSONArray("available_extensions").getString(0))
    }
    @Test fun `advertised and allowed counts distinguish installed catalog from host policy`() {
        val page = OmniLinkDiscovery.page(listOf(extension), emptyMap()) { _, name -> name == "app.action1" }
        val status = page.getJSONArray("extensions").getJSONObject(0)
        assertEquals(50, status.getInt("advertised_count"))
        assertEquals(1, status.getInt("allowed_count"))
    }
    @Test fun `old launcher catalog includes an actionable update diagnostic`() {
        val launcher = JSONObject(extension.toString()).put("extension_id", "app.lawnchair/app.lawnchair.omni.OmniLauncherService")
        val page = OmniLinkDiscovery.page(listOf(launcher), emptyMap()) { _, _ -> true }
        assertTrue(page.getJSONArray("extensions").getJSONObject(0).getString("diagnostic").contains("Launcher build"))
    }
    @Test fun `exact provider selection avoids fetching unrelated manifests`() {
        val selected = OmniLinkDiscovery.selectExtensions(listOf(JSONObject().put("id", "launcher/service"), JSONObject().put("id", "workspace/service")), "launcher/service")
        assertEquals(listOf("launcher/service"), selected.map { it.getString("id") })
    }
    @Test fun `query and policy exclude unavailable actions without false absence`() {
        val page = OmniLinkDiscovery.page(listOf(extension), mapOf("query" to "action4", "limit" to "999")) { _, name -> name != "app.action4" }
        assertEquals(10, page.getInt("count"))
        assertTrue(page.isNull("next_offset"))
        assertEquals("app.action40", page.getJSONArray("capabilities").getJSONObject(0).getString("name"))
    }
}
