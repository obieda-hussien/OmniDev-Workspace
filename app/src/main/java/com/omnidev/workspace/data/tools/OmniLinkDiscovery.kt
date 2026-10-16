package com.omnidev.workspace.data.tools

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Paged metadata only. Execution always re-resolves the provider and its full manifest. */
internal object OmniLinkDiscovery {
    fun page(extensions: List<JSONObject>, args: Map<String, String>, allowed: (String, String) -> Boolean): JSONObject {
        val scoped = selectExtensions(extensions, args["extension_id"], "extension_id")
        if (!args["extension_id"].isNullOrBlank() && scoped.isEmpty()) return JSONObject()
            .put("ok", false).put("code", "extension_not_found")
            .put("error", "The selected extension ID is not installed/verified. Use an available ID or omit extension_id for automatic resolution.")
            .put("available_extensions", JSONArray(extensions.map { it.optString("extension_id") }))
        val query = args["query"].orEmpty().trim().lowercase(Locale.ROOT).take(200)
        val matches = scoped.flatMap { extension ->
            val id = extension.optString("extension_id")
            val caps = extension.optJSONObject("manifest")?.optJSONArray("capabilities") ?: JSONArray()
            (0 until caps.length()).mapNotNull { index ->
                val cap = caps.optJSONObject(index) ?: return@mapNotNull null
                val name = cap.optString("name")
                if (name.isBlank() || !allowed(id, name) || (query.isNotBlank() &&
                    !name.lowercase(Locale.ROOT).contains(query) && !cap.optString("description").lowercase(Locale.ROOT).contains(query))) return@mapNotNull null
                JSONObject().put("extension_id", id).put("name", name)
                    .put("description", cap.optString("description").take(120))
                    .put("requiresConfirmation", cap.optBoolean("requiresConfirmation") || cap.optBoolean("destructive"))
                    .apply { for (key in listOf("risk", "idempotency")) if (cap.has(key)) put(key, cap.get(key)) }
            }
        }
        val offset = args["offset"]?.toIntOrNull()?.coerceIn(0, matches.size) ?: 0
        val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 20) ?: 8
        val page = matches.drop(offset).take(limit)
        return JSONObject().put("ok", true).put("action", "discover_capabilities")
            .put("total", matches.size).put("offset", offset).put("count", page.size)
            .put("capabilities", JSONArray(page))
            .put("capability_names", JSONArray(matches.take(64).map { it.optString("name") }))
            .put("capability_names_truncated", matches.size > 64)
            .put("extensions", JSONArray(scoped.map { extension ->
                val id = extension.optString("extension_id")
                val caps = extension.optJSONObject("manifest")?.optJSONArray("capabilities")
                val advertised = caps?.length() ?: 0
                JSONObject()
                .put("extension_id", extension.optString("extension_id"))
                .put("connected", extension.optBoolean("connected"))
                 .put("available", caps != null)
                .put("advertised_count", advertised)
                .put("allowed_count", (0 until advertised).count { allowed(id, caps!!.getJSONObject(it).optString("name")) })
                .apply {
                    if (caps == null) put("error", extension.optJSONObject("manifest")?.optString("error").orEmpty())
                    if (id.substringBefore('/').startsWith("app.lawnchair") && caps != null &&
                        (0 until advertised).none { caps.getJSONObject(it).optString("name") == "launcher.get_layout" }) {
                        put("diagnostic", "Installed Launcher does not advertise Home layout control. Install the matching new Launcher build; refreshing cannot add capabilities to an older APK.")
                    }
                }
            }))
            .put("extension_id", args["extension_id"]?.trim()?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
            .put("next_offset", if (offset + page.size < matches.size) offset + page.size else JSONObject.NULL)
            .put("hint", "Use find_capability(action_name) for one full input schema, then execute_capability. No preliminary discover is needed. Missing capabilities may be on the next page.")
    }

    fun selectExtensions(extensions: List<JSONObject>, extensionId: String?, idField: String = "id"): List<JSONObject> {
        val selected = extensionId?.trim().orEmpty()
        return if (selected.isBlank()) extensions else extensions.filter { it.optString(idField) == selected }
    }
}
