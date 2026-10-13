package com.omnidev.workspace.data.tools

import org.json.JSONArray
import org.json.JSONObject

/** Paged metadata only. Execution always re-resolves the provider and its full manifest. */
internal object OmniLinkDiscovery {
    fun page(extensions: List<JSONObject>, args: Map<String, String>, allowed: (String, String) -> Boolean): JSONObject {
        val query = args["query"].orEmpty().trim().lowercase().take(200)
        val matches = extensions.flatMap { extension ->
            val id = extension.optString("extension_id")
            val caps = extension.optJSONObject("manifest")?.optJSONArray("capabilities") ?: JSONArray()
            (0 until caps.length()).mapNotNull { index ->
                val cap = caps.optJSONObject(index) ?: return@mapNotNull null
                val name = cap.optString("name")
                if (name.isBlank() || !allowed(id, name) || (query.isNotBlank() &&
                    !name.lowercase().contains(query) && !cap.optString("description").lowercase().contains(query))) return@mapNotNull null
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
            .put("extensions", JSONArray(extensions.map { extension -> JSONObject()
                .put("extension_id", extension.optString("extension_id"))
                .put("connected", extension.optBoolean("connected"))
                .put("available", extension.optJSONObject("manifest")?.optJSONArray("capabilities") != null) }))
            .put("next_offset", if (offset + page.size < matches.size) offset + page.size else JSONObject.NULL)
            .put("hint", "Use find_capability(action_name) for one full input schema, then execute_capability. No preliminary discover is needed. Missing capabilities may be on the next page.")
    }
}
