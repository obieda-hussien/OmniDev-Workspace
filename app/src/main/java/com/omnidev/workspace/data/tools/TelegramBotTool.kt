package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Extended Telegram Bot tool — inspired by OpenClaw's Telegram channel integration.
 *
 * Provides the AI agent with bidirectional Telegram capabilities:
 *   - send_message   → send a message to any chat
 *   - get_updates    → poll for incoming messages (getUpdates long-polling)
 *   - reply_to       → reply to a specific message_id
 *   - get_bot_info   → return the bot's own profile (@username, name, id)
 *   - get_chat_info  → return info about a chat/group/channel
 *   - delete_message → delete a previously sent message
 *   - pin_message    → pin a message in a group/channel
 *   - set_commands   → register bot slash-commands
 *
 * All actions require TELEGRAM_BOT_TOKEN (set in Settings → Integrations).
 */
object TelegramBotTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "telegram_bot",
            description = "Bidirectional Telegram Bot API tool. Actions: send_message, get_updates, " +
                "reply_to, get_bot_info, get_chat_info, delete_message, pin_message, set_commands. " +
                "Requires Telegram Bot Token in Settings → Integrations.",
            parameters = listOf(
                ToolParameter(
                    "action", "string",
                    "One of: send_message, get_updates, reply_to, get_bot_info, " +
                        "get_chat_info, delete_message, pin_message, set_commands.",
                    required = true
                ),
                ToolParameter("chat_id", "string", "Target chat / group / channel ID or @username.", required = false),
                ToolParameter("message", "string", "Text to send (for send_message / reply_to).", required = false),
                ToolParameter("message_id", "string", "Message ID (for reply_to / delete_message / pin_message).", required = false),
                ToolParameter("parse_mode", "string", "Markdown (default) or HTML.", required = false),
                ToolParameter("offset", "string", "Offset for get_updates (pass last update_id + 1).", required = false),
                ToolParameter("limit", "string", "Max updates to fetch (1-100, default 20).", required = false),
                ToolParameter("timeout", "string", "Long-poll timeout in seconds for get_updates (default 0 = short poll).", required = false),
                ToolParameter("commands", "string", "JSON array of {command, description} objects for set_commands.", required = false)
            )
        )
    )

    suspend fun execute(
        botToken: String?,
        args: Map<String, String>
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (botToken.isNullOrBlank()) {
            return@withContext ToolExecutionResult(
                "Telegram Bot Token is not configured. Go to Settings → Integrations to set it up.",
                isError = true
            )
        }

        when (val action = args["action"]?.lowercase()?.trim() ?: "") {
            "send_message" -> sendMessage(botToken, args)
            "reply_to" -> replyTo(botToken, args)
            "get_updates" -> getUpdates(botToken, args)
            "get_bot_info" -> getBotInfo(botToken)
            "get_chat_info" -> getChatInfo(botToken, args)
            "delete_message" -> deleteMessage(botToken, args)
            "pin_message" -> pinMessage(botToken, args)
            "set_commands" -> setMyCommands(botToken, args)
            else -> ToolExecutionResult(
                "Unknown action '$action'. Valid actions: send_message, get_updates, reply_to, " +
                    "get_bot_info, get_chat_info, delete_message, pin_message, set_commands.",
                isError = true
            )
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────

    private suspend fun sendMessage(token: String, args: Map<String, String>): ToolExecutionResult {
        val chatId = args["chat_id"]
            ?: return ToolExecutionResult("Missing required parameter: chat_id", isError = true)
        val text = args["message"]
            ?: return ToolExecutionResult("Missing required parameter: message", isError = true)
        val parseMode = args["parse_mode"] ?: "Markdown"

        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            put("parse_mode", parseMode)
        }
        return post(token, "sendMessage", body) { json ->
            val msgId = json.optJSONObject("result")?.optLong("message_id")
            "✅ Message sent. message_id=$msgId"
        }
    }

    private suspend fun replyTo(token: String, args: Map<String, String>): ToolExecutionResult {
        val chatId = args["chat_id"]
            ?: return ToolExecutionResult("Missing required parameter: chat_id", isError = true)
        val text = args["message"]
            ?: return ToolExecutionResult("Missing required parameter: message", isError = true)
        val replyMsgId = args["message_id"]?.toLongOrNull()
            ?: return ToolExecutionResult("Missing required parameter: message_id (integer)", isError = true)
        val parseMode = args["parse_mode"] ?: "Markdown"

        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            put("parse_mode", parseMode)
            put("reply_to_message_id", replyMsgId)
        }
        return post(token, "sendMessage", body) { json ->
            val msgId = json.optJSONObject("result")?.optLong("message_id")
            "✅ Reply sent. message_id=$msgId"
        }
    }

    private suspend fun getUpdates(token: String, args: Map<String, String>): ToolExecutionResult {
        val offset = args["offset"]?.toLongOrNull()
        val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val timeout = args["timeout"]?.toIntOrNull() ?: 0

        val urlStr = buildString {
            append("https://api.telegram.org/bot$token/getUpdates?limit=$limit&timeout=$timeout")
            if (offset != null) append("&offset=$offset")
        }

        return withContext(Dispatchers.IO) {
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = (timeout + 10) * 1_000
                conn.readTimeout = (timeout + 15) * 1_000

                val code = conn.responseCode
                val body = if (code in 200..299) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                }
                conn.disconnect()

                if (code !in 200..299) {
                    return@withContext ToolExecutionResult("Telegram getUpdates error ($code): $body", isError = true)
                }

                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) {
                    return@withContext ToolExecutionResult(
                        "Telegram API error: ${json.optString("description", "unknown")}",
                        isError = true
                    )
                }

                val updates = json.optJSONArray("result") ?: JSONArray()
                if (updates.length() == 0) {
                    return@withContext ToolExecutionResult("No new messages.")
                }

                val sb = StringBuilder("📨 ${updates.length()} update(s):\n\n")
                for (i in 0 until updates.length()) {
                    val update = updates.getJSONObject(i)
                    val updateId = update.optLong("update_id")
                    val msg = update.optJSONObject("message")
                        ?: update.optJSONObject("edited_message")
                        ?: update.optJSONObject("channel_post")
                    if (msg != null) {
                        val from = msg.optJSONObject("from")
                        val chat = msg.optJSONObject("chat")
                        val sender = from?.let {
                            val first = it.optString("first_name", "")
                            val username = it.optString("username", "")
                            if (username.isNotBlank()) "@$username ($first)" else first
                        } ?: "unknown"
                        val chatTitle = chat?.let {
                            it.optString("title", "").ifBlank { it.optString("username", it.optString("first_name", "")) }
                        } ?: "unknown"
                        val text = msg.optString("text", "[no text]")
                        val msgId = msg.optLong("message_id")
                        val chatId = chat?.optLong("id") ?: 0L
                        sb.append("update_id=$updateId\n")
                        sb.append("  from: $sender\n")
                        sb.append("  chat: $chatTitle (id=$chatId)\n")
                        sb.append("  message_id: $msgId\n")
                        sb.append("  text: $text\n\n")
                    } else {
                        sb.append("update_id=$updateId [non-text update]\n\n")
                    }
                }
                sb.append("Next offset: ${updates.getJSONObject(updates.length() - 1).optLong("update_id") + 1}")
                ToolExecutionResult(sb.toString())
            } catch (e: Exception) {
                ToolExecutionResult("getUpdates failed: ${e.message}", isError = true)
            }
        }
    }

    private suspend fun getBotInfo(token: String): ToolExecutionResult {
        return get(token, "getMe") { json ->
            val r = json.optJSONObject("result") ?: return@get "No result"
            buildString {
                append("🤖 Bot Info:\n")
                append("  id: ${r.optLong("id")}\n")
                append("  name: ${r.optString("first_name")}\n")
                append("  username: @${r.optString("username")}\n")
                append("  can_join_groups: ${r.optBoolean("can_join_groups")}\n")
                append("  supports_inline_queries: ${r.optBoolean("supports_inline_queries")}")
            }
        }
    }

    private suspend fun getChatInfo(token: String, args: Map<String, String>): ToolExecutionResult {
        val chatId = args["chat_id"]
            ?: return ToolExecutionResult("Missing required parameter: chat_id", isError = true)
        return get(token, "getChat?chat_id=${encode(chatId)}") { json ->
            val r = json.optJSONObject("result") ?: return@get "No result"
            buildString {
                append("💬 Chat Info:\n")
                append("  id: ${r.optLong("id")}\n")
                append("  type: ${r.optString("type")}\n")
                val title = r.optString("title", "").ifBlank { r.optString("first_name", "") }
                append("  title/name: $title\n")
                val username = r.optString("username", "")
                if (username.isNotBlank()) append("  username: @$username\n")
                val desc = r.optString("description", "")
                if (desc.isNotBlank()) append("  description: $desc\n")
                append("  members_count: ${r.optInt("members_count", -1)}")
            }
        }
    }

    private suspend fun deleteMessage(token: String, args: Map<String, String>): ToolExecutionResult {
        val chatId = args["chat_id"]
            ?: return ToolExecutionResult("Missing required parameter: chat_id", isError = true)
        val msgId = args["message_id"]?.toLongOrNull()
            ?: return ToolExecutionResult("Missing required parameter: message_id (integer)", isError = true)

        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("message_id", msgId)
        }
        return post(token, "deleteMessage", body) { "✅ Message $msgId deleted." }
    }

    private suspend fun pinMessage(token: String, args: Map<String, String>): ToolExecutionResult {
        val chatId = args["chat_id"]
            ?: return ToolExecutionResult("Missing required parameter: chat_id", isError = true)
        val msgId = args["message_id"]?.toLongOrNull()
            ?: return ToolExecutionResult("Missing required parameter: message_id (integer)", isError = true)

        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("message_id", msgId)
            put("disable_notification", false)
        }
        return post(token, "pinChatMessage", body) { "📌 Message $msgId pinned." }
    }

    private suspend fun setMyCommands(token: String, args: Map<String, String>): ToolExecutionResult {
        val commandsJson = args["commands"]
            ?: return ToolExecutionResult("Missing required parameter: commands (JSON array)", isError = true)
        val body = JSONObject().apply {
            put("commands", JSONArray(commandsJson))
        }
        return post(token, "setMyCommands", body) { "✅ Bot commands updated." }
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────

    private suspend fun post(
        token: String,
        method: String,
        body: JSONObject,
        onSuccess: (JSONObject) -> String
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val conn = URL("https://api.telegram.org/bot$token/$method")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val respBody = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            }
            conn.disconnect()

            if (code !in 200..299) {
                return@withContext ToolExecutionResult("Telegram API error ($code): $respBody", isError = true)
            }
            val json = JSONObject(respBody)
            if (!json.optBoolean("ok", false)) {
                return@withContext ToolExecutionResult(
                    "Telegram API error: ${json.optString("description", respBody)}",
                    isError = true
                )
            }
            ToolExecutionResult(onSuccess(json))
        } catch (e: IOException) {
            ToolExecutionResult("Telegram $method failed: ${e.message}", isError = true)
        }
    }

    private suspend fun get(
        token: String,
        methodAndParams: String,
        onSuccess: (JSONObject) -> String
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val conn = URL("https://api.telegram.org/bot$token/$methodAndParams")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000

            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            }
            conn.disconnect()

            if (code !in 200..299) {
                return@withContext ToolExecutionResult("Telegram API error ($code): $body", isError = true)
            }
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) {
                return@withContext ToolExecutionResult(
                    "Telegram API error: ${json.optString("description", body)}",
                    isError = true
                )
            }
            ToolExecutionResult(onSuccess(json))
        } catch (e: IOException) {
            ToolExecutionResult("Telegram request failed: ${e.message}", isError = true)
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
