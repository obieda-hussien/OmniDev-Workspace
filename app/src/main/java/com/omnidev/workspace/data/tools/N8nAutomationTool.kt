package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI-callable tool for n8n workflow automation.
 *
 * Allows the agent to:
 * - List available workflows
 * - Get details of a specific workflow
 * - Execute (trigger) a workflow manually
 * - Toggle a workflow active/inactive
 * - List recent executions
 * - Create a simple webhook-triggered workflow
 *
 * Requires n8n base URL and API key in Settings → Integrations.
 * n8n must be running and accessible from the device (local network or cloud).
 *
 * Exposed tool name: `n8n_automation`
 */
object N8nAutomationTool {

    // ── Constants ─────────────────────────────────────────────────────────

    private const val API_KEY_HEADER = "X-N8N-API-KEY"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    // ── Tool definitions ─────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "n8n_automation",
            description = "Interact with n8n workflow automation server. " +
                "List workflows, execute them, check execution history, toggle active/inactive, " +
                "and create simple webhook-triggered workflows. " +
                "Requires n8n Base URL and API Key in Settings → Integrations. " +
                "Use this to help non-technical users automate repetitive tasks.",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "Action: 'list_workflows' | 'get_workflow' | 'execute_workflow' | " +
                        "'toggle_workflow' | 'list_executions' | 'create_webhook_workflow' | 'delete_workflow'",
                    required = true
                ),
                ToolParameter(
                    name = "workflow_id",
                    type = "string",
                    description = "Workflow ID (required for get, execute, toggle, list_executions, delete)",
                    required = false
                ),
                ToolParameter(
                    name = "workflow_name",
                    type = "string",
                    description = "Workflow name (for create_webhook_workflow)",
                    required = false
                ),
                ToolParameter(
                    name = "webhook_path",
                    type = "string",
                    description = "Webhook path slug for create_webhook_workflow, e.g. 'send-email'. Default: auto-generated.",
                    required = false
                ),
                ToolParameter(
                    name = "active",
                    type = "string",
                    description = "For toggle_workflow: 'true' to activate, 'false' to deactivate",
                    required = false
                ),
                ToolParameter(
                    name = "payload",
                    type = "string",
                    description = "JSON payload to send when executing a webhook workflow, e.g. '{\"email\":\"x@y.com\"}'",
                    required = false
                ),
                ToolParameter(
                    name = "limit",
                    type = "string",
                    description = "Max results for list_executions. Default: 20",
                    required = false
                ),
                ToolParameter(
                    name = "base_url",
                    type = "string",
                    description = "n8n base URL override, e.g. 'http://192.168.1.10:5678'. Uses setting if omitted.",
                    required = false
                ),
                ToolParameter(
                    name = "api_key",
                    type = "string",
                    description = "n8n API key override. Uses setting if omitted.",
                    required = false
                )
            )
        )
    )

    // ── Execution ─────────────────────────────────────────────────────────

    suspend fun execute(
        args: Map<String, String>,
        settingsBaseUrl: String?,
        settingsApiKey: String?
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val baseUrl = (args["base_url"]?.takeIf { it.isNotBlank() }
            ?: settingsBaseUrl?.takeIf { it.isNotBlank() })
            ?.trimEnd('/')
            ?: return@withContext ToolExecutionResult(
                "n8n Base URL is not configured. Go to Settings → Integrations → n8n to set it up.",
                isError = true
            )

        val apiKey = args["api_key"]?.takeIf { it.isNotBlank() }
            ?: settingsApiKey?.takeIf { it.isNotBlank() }
            ?: return@withContext ToolExecutionResult(
                "n8n API Key is not configured. Go to Settings → Integrations → n8n to set it up.",
                isError = true
            )

        val action = args["action"]?.lowercase()
            ?: return@withContext ToolExecutionResult("Missing required argument: action", isError = true)

        return@withContext when (action) {
            "list_workflows"        -> listWorkflows(baseUrl, apiKey)
            "get_workflow"          -> getWorkflow(baseUrl, apiKey, args)
            "execute_workflow"      -> executeWorkflow(baseUrl, apiKey, args)
            "toggle_workflow"       -> toggleWorkflow(baseUrl, apiKey, args)
            "list_executions"       -> listExecutions(baseUrl, apiKey, args)
            "create_webhook_workflow" -> createWebhookWorkflow(baseUrl, apiKey, args)
            "delete_workflow"       -> deleteWorkflow(baseUrl, apiKey, args)
            else -> ToolExecutionResult(
                "Unknown n8n_automation action '$action'. " +
                    "Valid: list_workflows | get_workflow | execute_workflow | toggle_workflow | " +
                    "list_executions | create_webhook_workflow | delete_workflow",
                isError = true
            )
        }
    }

    // ── Action implementations ────────────────────────────────────────────

    private fun listWorkflows(base: String, key: String): ToolExecutionResult {
        val (code, body) = get("$base/api/v1/workflows", key)
        if (code !in 200..299) return apiError("list workflows", code, body)
        return parseWorkflowList(body)
    }

    private fun getWorkflow(base: String, key: String, args: Map<String, String>): ToolExecutionResult {
        val id = args["workflow_id"]
            ?: return ToolExecutionResult("Missing required argument: workflow_id", isError = true)
        val (code, body) = get("$base/api/v1/workflows/$id", key)
        if (code !in 200..299) return apiError("get workflow", code, body)
        return ToolExecutionResult("Workflow details:\n$body")
    }

    private fun executeWorkflow(base: String, key: String, args: Map<String, String>): ToolExecutionResult {
        val id = args["workflow_id"]
            ?: return ToolExecutionResult("Missing required argument: workflow_id", isError = true)
        val payload = args["payload"]?.takeIf { it.isNotBlank() } ?: "{}"
        // POST to the workflow's test URL (requires workflow to have webhook trigger node)
        val (code, body) = post("$base/api/v1/workflows/$id/execute", key, payload)
        if (code !in 200..299) return apiError("execute workflow", code, body)
        return ToolExecutionResult("✅ Workflow #$id triggered successfully.\n$body")
    }

    private fun toggleWorkflow(base: String, key: String, args: Map<String, String>): ToolExecutionResult {
        val id = args["workflow_id"]
            ?: return ToolExecutionResult("Missing required argument: workflow_id", isError = true)
        val active = args["active"]?.lowercase() != "false"   // default to activate
        val payload = """{"active":$active}"""
        val (code, body) = patch("$base/api/v1/workflows/$id", key, payload)
        if (code !in 200..299) return apiError("toggle workflow", code, body)
        return ToolExecutionResult("✅ Workflow #$id ${if (active) "activated ▶" else "deactivated ⏸"}.")
    }

    private fun listExecutions(base: String, key: String, args: Map<String, String>): ToolExecutionResult {
        val id = args["workflow_id"]
        val limit = args["limit"]?.toIntOrNull() ?: 20
        val url = if (id != null)
            "$base/api/v1/executions?workflowId=$id&limit=$limit"
        else
            "$base/api/v1/executions?limit=$limit"
        val (code, body) = get(url, key)
        if (code !in 200..299) return apiError("list executions", code, body)
        return ToolExecutionResult("Recent executions:\n$body")
    }

    private fun createWebhookWorkflow(base: String, key: String, args: Map<String, String>): ToolExecutionResult {
        val name = args["workflow_name"]?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult("Missing required argument: workflow_name", isError = true)
        val path = args["webhook_path"]?.takeIf { it.isNotBlank() }
            ?: name.lowercase().replace(Regex("[^a-z0-9-]"), "-").take(40)

        val workflowJson = buildWebhookWorkflowJson(name, path)
        val (code, body) = post("$base/api/v1/workflows", key, workflowJson)
        if (code !in 200..299) return apiError("create workflow", code, body)

        val idMatch = Regex("\"id\"\\s*:\\s*\"?([^,\"\\}]+)\"?").find(body)
        val newId = idMatch?.groupValues?.getOrNull(1) ?: "?"
        return ToolExecutionResult(
            "✅ Webhook workflow \"$name\" created (ID: $newId).\n" +
                "Trigger URL: $base/webhook/$path\n" +
                "POST any JSON payload to that URL to run the workflow."
        )
    }

    private fun deleteWorkflow(base: String, key: String, args: Map<String, String>): ToolExecutionResult {
        val id = args["workflow_id"]
            ?: return ToolExecutionResult("Missing required argument: workflow_id", isError = true)
        val (code, body) = delete("$base/api/v1/workflows/$id", key)
        if (code !in 200..299) return apiError("delete workflow", code, body)
        return ToolExecutionResult("🗑️ Workflow #$id deleted.")
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────

    private fun get(url: String, apiKey: String): Pair<Int, String> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty(API_KEY_HEADER, apiKey)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            readResponse(conn)
        } catch (e: IOException) {
            -1 to "IOException: ${e.message}"
        }
    }

    private fun post(url: String, apiKey: String, body: String): Pair<Int, String> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty(API_KEY_HEADER, apiKey)
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            readResponse(conn)
        } catch (e: IOException) {
            -1 to "IOException: ${e.message}"
        }
    }

    private fun patch(url: String, apiKey: String, body: String): Pair<Int, String> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "PATCH"
            conn.setRequestProperty(API_KEY_HEADER, apiKey)
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            readResponse(conn)
        } catch (e: IOException) {
            -1 to "IOException: ${e.message}"
        }
    }

    private fun delete(url: String, apiKey: String): Pair<Int, String> {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.setRequestProperty(API_KEY_HEADER, apiKey)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            readResponse(conn)
        } catch (e: IOException) {
            -1 to "IOException: ${e.message}"
        }
    }

    private fun readResponse(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        return code to body
    }

    // ── Parsers / builders ────────────────────────────────────────────────

    private fun parseWorkflowList(body: String): ToolExecutionResult {
        // Lightweight JSON parse — extract id + name + active without pulling in a full JSON lib
        val sb = StringBuilder("n8n Workflows:\n")
        val idPattern = Regex("\"id\"\\s*:\\s*\"?([^,\"\\}]+)\"?")
        val namePattern = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
        val activePattern = Regex("\"active\"\\s*:\\s*(true|false)")

        val ids = idPattern.findAll(body).map { it.groupValues[1] }.toList()
        val names = namePattern.findAll(body).map { it.groupValues[1] }.toList()
        val actives = activePattern.findAll(body).map { it.groupValues[1] == "true" }.toList()

        if (ids.isEmpty()) return ToolExecutionResult("No workflows found in n8n.")

        for (i in ids.indices) {
            val active = actives.getOrNull(i) ?: false
            val name = names.getOrNull(i) ?: "(unnamed)"
            sb.appendLine("  ${if (active) "▶" else "⏸"} #${ids[i]} — $name")
        }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private fun buildWebhookWorkflowJson(name: String, path: String): String {
        val escapedName = name.replace("\"", "\\\"")
        val escapedPath = path.replace("\"", "\\\"")
        return """{
  "name": "$escapedName",
  "active": false,
  "nodes": [
    {
      "id": "webhook-node",
      "name": "Webhook",
      "type": "n8n-nodes-base.webhook",
      "typeVersion": 1,
      "position": [250, 300],
      "parameters": {
        "path": "$escapedPath",
        "responseMode": "onReceived",
        "options": {}
      }
    },
    {
      "id": "set-node",
      "name": "Process Data",
      "type": "n8n-nodes-base.set",
      "typeVersion": 1,
      "position": [500, 300],
      "parameters": {
        "values": {
          "string": [{ "name": "message", "value": "Workflow triggered successfully!" }]
        }
      }
    }
  ],
  "connections": {
    "Webhook": {
      "main": [[{ "node": "Process Data", "type": "main", "index": 0 }]]
    }
  },
  "settings": {}
}"""
    }

    private fun apiError(action: String, code: Int, body: String) =
        ToolExecutionResult("n8n API error while $action (HTTP $code): $body", isError = true)
}
