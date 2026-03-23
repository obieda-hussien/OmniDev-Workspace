package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.ipc.ExtensionConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Omni-Link tool for universal third-party extension discovery and execution.
 */
object OmniLinkTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "omni_link",
            description = """
Discover and control third-party apps exposing Omni-Link AIDL extension services.
Actions:
• discover          — Force re-scan extension services and return updated list.
• list_extensions   — Return currently cached discovered extensions (fast, no re-scan).
• get_manifest      — extension_id: fetch capability manifest JSON.
• execute_action    — extension_id, action_name, json_payload(optional): execute extension action.
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string", "discover, list_extensions, get_manifest, execute_action", required = true),
                ToolParameter("extension_id", "string", "Extension ID: <package>/<serviceClass>", required = false),
                ToolParameter("action_name", "string", "Extension action name for execute_action", required = false),
                ToolParameter("json_payload", "string", "JSON payload string for execute_action (default: {})", required = false)
            )
        )
    )

    suspend fun execute(action: String, args: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            when (action.trim().lowercase()) {
                "discover", "list_extensions" -> {
                    val forceRefresh = action.trim().lowercase() == "discover"
                    val list = ExtensionConnectionManager.listExtensions(forceRefresh = forceRefresh)
                    val arr = JSONArray()
                    list.forEach { arr.put(it) }
                    ToolExecutionResult(
                        JSONObject()
                            .put("ok", true)
                            .put("action", if (forceRefresh) "discover" else "list_extensions")
                            .put("count", list.size)
                            .put("extensions", arr)
                            .toString()
                    )
                }

                "get_manifest" -> {
                    val extensionId = args["extension_id"]?.trim().orEmpty()
                    if (extensionId.isBlank()) return@withContext missing("extension_id")
                    val manifest = ExtensionConnectionManager.getExtensionManifest(extensionId)
                    ToolExecutionResult(
                        JSONObject()
                            .put("ok", true)
                            .put("extension_id", extensionId)
                            .put("manifest_json", manifest)
                            .toString()
                    )
                }

                "execute_action" -> {
                    val extensionId = args["extension_id"]?.trim().orEmpty()
                    if (extensionId.isBlank()) return@withContext missing("extension_id")
                    val actionName = args["action_name"]?.trim().orEmpty()
                    if (actionName.isBlank()) return@withContext missing("action_name")
                    val payload = args["json_payload"]?.trim().takeUnless { it.isNullOrBlank() } ?: "{}"
                    val result = ExtensionConnectionManager.executeAction(
                        extensionId = extensionId,
                        actionName = actionName,
                        jsonPayload = payload
                    )
                    ToolExecutionResult(
                        JSONObject()
                            .put("ok", true)
                            .put("extension_id", extensionId)
                            .put("action_name", actionName)
                            .put("result_json", result)
                            .toString()
                    )
                }

                else -> ToolExecutionResult(
                    output = JSONObject()
                        .put("ok", false)
                        .put("error", "Unknown omni_link action '$action'")
                        .toString(),
                    isError = true
                )
            }
        }

    private fun missing(name: String): ToolExecutionResult =
        ToolExecutionResult(
            output = JSONObject()
                .put("ok", false)
                .put("error", "Missing required parameter: $name")
                .toString(),
            isError = true
        )
}
