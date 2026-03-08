package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Publishes messages to a Telegram channel/group via the Telegram Bot API.
 *
 * Requires the user to configure `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID`
 * in the Integrations settings screen.
 *
 * @param botToken The Telegram Bot API token (from BotFather).
 * @param chatId The target chat/channel/group ID or @username.
 */
object TelegramPublisherTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "telegram_publish",
            description = "Publishes a message to a configured Telegram channel or group via the Bot API. " +
                "Requires Telegram Bot Token and Chat ID to be configured in Integrations settings.",
            parameters = listOf(
                ToolParameter("message", "string", "The message text to send.", required = true),
                ToolParameter("parseMode", "string", "Parse mode: Markdown or HTML. Default: Markdown.", required = false)
            )
        )
    )

    suspend fun execute(
        botToken: String?,
        chatId: String?,
        message: String,
        parseMode: String = "Markdown"
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (botToken.isNullOrBlank()) {
            return@withContext ToolExecutionResult(
                "Telegram Bot Token is not configured. Go to Settings → Integrations to set it up.",
                isError = true
            )
        }
        if (chatId.isNullOrBlank()) {
            return@withContext ToolExecutionResult(
                "Telegram Chat ID is not configured. Go to Settings → Integrations to set it up.",
                isError = true
            )
        }

        try {
            val url = URL("https://api.telegram.org/bot$botToken/sendMessage")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000

            val body = """{"chat_id":${escapeJson(chatId)},"text":${escapeJson(message)},"parse_mode":${escapeJson(parseMode)}}"""
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val responseBody = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            }

            if (code in 200..299) {
                ToolExecutionResult("✅ Message published to Telegram successfully.")
            } else {
                ToolExecutionResult("Telegram API error ($code): $responseBody", isError = true)
            }
        } catch (e: IOException) {
            ToolExecutionResult("Telegram publish failed: ${e.message}", isError = true)
        }
    }

    private fun escapeJson(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
