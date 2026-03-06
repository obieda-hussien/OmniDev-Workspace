package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Tool that creates pages in a Notion database via the Notion API.
 *
 * Requires a Notion Integration API key and a target Database ID,
 * both configured in Settings → Integrations.
 */
class NotionPublisherTool(private val settingsRepository: SettingsRepository) {

    companion object {
        private const val NOTION_API_VERSION = "2022-06-28"
        private const val BASE_URL = "https://api.notion.com/v1"

        fun getToolDefinitions(): List<ToolDefinition> = listOf(
            ToolDefinition(
                name = "create_notion_page",
                description = "Creates a new page in a Notion database. Use this to log build results, document findings, or create task entries. Requires Notion API key in Settings → Integrations.",
                parameters = listOf(
                    ToolParameter("title", "string", "The title/name of the Notion page to create", required = true),
                    ToolParameter("content", "string", "The body content of the page (plain text, max 2000 chars)", required = true),
                    ToolParameter("status", "string", "Optional status property value (e.g. 'In Progress', 'Done', 'Failed')", required = false)
                )
            )
        )
    }

    suspend fun execute(params: Map<String, String>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val apiKey = settingsRepository.observeNotionApiKey().first()
            ?: return@withContext ToolExecutionResult(
                "Error: Notion API key not configured. Go to Settings → Integrations to add it.",
                isError = true
            )

        val databaseId = settingsRepository.observeNotionDatabaseId().first()
            ?: return@withContext ToolExecutionResult(
                "Error: Notion Database ID not configured. Go to Settings → Integrations to add it.",
                isError = true
            )

        val title = params["title"]
            ?: return@withContext ToolExecutionResult("Error: title parameter is required", isError = true)
        val content = params["content"]
            ?: return@withContext ToolExecutionResult("Error: content parameter is required", isError = true)

        try {
            val payload = buildNotionPayload(databaseId, title, content, params["status"])

            val url = URL("$BASE_URL/pages")
            val connection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Notion-Version", NOTION_API_VERSION)
            connection.doOutput = true
            connection.outputStream.write(payload.toString().toByteArray())
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val responseBody = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(responseBody)
                val pageUrl = json.optString("url", "")
                ToolExecutionResult("✅ Notion page created: ${if (pageUrl.isNotBlank()) pageUrl else "success"}")
            } else {
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.readText() }.getOrNull()
                ToolExecutionResult("Error: Notion API responded with HTTP $responseCode: $errorBody", isError = true)
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error: Failed to create Notion page: ${e.message}", isError = true)
        }
    }

    private fun buildNotionPayload(
        databaseId: String,
        title: String,
        content: String,
        status: String?
    ): JSONObject {
        val payload = JSONObject()

        val parent = JSONObject()
        parent.put("type", "database_id")
        parent.put("database_id", databaseId)
        payload.put("parent", parent)

        val properties = JSONObject()
        val nameProp = JSONObject()
        val titleArray = JSONArray()
        val titleObj = JSONObject()
        val textObj = JSONObject()
        textObj.put("content", title)
        titleObj.put("text", textObj)
        titleArray.put(titleObj)
        nameProp.put("title", titleArray)
        properties.put("Name", nameProp)

        if (!status.isNullOrBlank()) {
            val statusProp = JSONObject()
            val statusSelect = JSONObject()
            statusSelect.put("name", status)
            statusProp.put("select", statusSelect)
            properties.put("Status", statusProp)
        }
        payload.put("properties", properties)

        val children = JSONArray()
        val block = JSONObject()
        block.put("object", "block")
        block.put("type", "paragraph")
        val paragraph = JSONObject()
        val richText = JSONArray()
        val textBlock = JSONObject()
        val textContent = JSONObject()
        textContent.put("content", content.take(2000))
        textBlock.put("text", textContent)
        richText.put(textBlock)
        paragraph.put("rich_text", richText)
        block.put("paragraph", paragraph)
        children.put(block)
        payload.put("children", children)

        return payload
    }
}
