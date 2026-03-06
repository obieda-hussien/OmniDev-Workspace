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
 * Tool that sends messages to a Discord channel via webhook.
 *
 * Supports plain text messages and optional embeds with title/description.
 * The webhook URL is stored in SettingsRepository and configured in the Integrations screen.
 */
class DiscordPublisherTool(private val settingsRepository: SettingsRepository) {

    companion object {
        fun getToolDefinitions(): List<ToolDefinition> = listOf(
            ToolDefinition(
                name = "publish_to_discord",
                description = "Sends a message to a Discord channel via webhook. Use this to notify the user's Discord server about build results, code analysis, or autonomous task completions.",
                parameters = listOf(
                    ToolParameter("message", "string", "The message text to send to Discord (max 2000 chars)", required = true),
                    ToolParameter("title", "string", "Optional embed title for a richer formatted message", required = false),
                    ToolParameter("color", "string", "Optional embed color as hex (e.g. FF0000 for red, 00FF00 for green)", required = false)
                )
            )
        )
    }

    suspend fun execute(params: Map<String, String>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val webhookUrl = settingsRepository.observeDiscordWebhookUrl().first()
            ?: return@withContext ToolExecutionResult(
                "Error: Discord webhook URL not configured. Go to Settings → Integrations to add it.",
                isError = true
            )

        val message = params["message"]
            ?: return@withContext ToolExecutionResult("Error: message parameter is required", isError = true)

        if (message.length > 2000) {
            return@withContext ToolExecutionResult(
                "Error: Discord messages must be ≤ 2000 characters (got ${message.length})",
                isError = true
            )
        }

        try {
            val payload = JSONObject()
            val title = params["title"]
            if (title != null) {
                val embed = JSONObject()
                embed.put("title", title)
                embed.put("description", message)
                val colorHex = params["color"]?.replace("#", "")
                if (!colorHex.isNullOrBlank()) {
                    // Discord embed colors are 24-bit (0x000000–0xFFFFFF); mask to stay in range
                    val colorInt = colorHex.toLongOrNull(16)?.and(0xFFFFFFL)?.toInt()
                    if (colorInt != null) embed.put("color", colorInt)
                    // Invalid hex values are silently skipped — the embed is still sent without a color
                }
                val embeds = JSONArray()
                embeds.put(embed)
                payload.put("embeds", embeds)
            } else {
                payload.put("content", message)
            }

            val url = URL(webhookUrl)
            val connection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.write(payload.toString().toByteArray())
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                ToolExecutionResult("✅ Message sent to Discord successfully.")
            } else {
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.readText() }.getOrNull()
                ToolExecutionResult("Error: Discord responded with HTTP $responseCode: $errorBody", isError = true)
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error: Failed to send Discord message: ${e.message}", isError = true)
        }
    }
}
