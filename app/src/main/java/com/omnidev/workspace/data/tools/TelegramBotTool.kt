package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Full-featured Telegram Bot tool — rich bidirectional Telegram Bot API integration.
 *
 * Actions:
 *   send_message, reply_to, forward_message, get_updates,
 *   get_bot_info, get_chat_info, delete_message, pin_message, set_commands,
 *   react, send_photo, send_document, send_location, send_live_location,
 *   send_contact, get_file
 *
 * Requires TELEGRAM_BOT_TOKEN in Settings → Integrations.
 */
object TelegramBotTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "telegram_bot",
            description = "Full Telegram Bot API integration. Actions: send_message, reply_to, " +
                "forward_message, get_updates, get_bot_info, get_chat_info, delete_message, " +
                "pin_message, set_commands, react, send_photo, send_document, send_location, " +
                "send_live_location, send_contact, get_file. " +
                "Requires Telegram Bot Token in Settings → Integrations.",
            parameters = listOf(
                ToolParameter(
                    "action", "string",
                    "One of: send_message, reply_to, forward_message, get_updates, get_bot_info, " +
                        "get_chat_info, delete_message, pin_message, set_commands, react, send_photo, " +
                        "send_document, send_location, send_live_location, send_contact, get_file.",
                    required = true
                ),
                ToolParameter("chat_id", "string", "Target chat / group / channel ID or @username.", required = false),
                ToolParameter("from_chat_id", "string", "Source chat ID for forward_message.", required = false),
                ToolParameter("message", "string", "Text to send (send_message / reply_to).", required = false),
                ToolParameter("message_id", "string", "Message ID (reply_to/delete/pin/react/forward/get_file).", required = false),
                ToolParameter("parse_mode", "string", "Markdown (default) or HTML.", required = false),
                ToolParameter("offset", "string", "Offset for get_updates (last update_id + 1).", required = false),
                ToolParameter("limit", "string", "Max updates to fetch (1-100, default 20).", required = false),
                ToolParameter("timeout", "string", "Long-poll timeout seconds for get_updates (default 0).", required = false),
                ToolParameter("commands", "string", "JSON array of {command,description} for set_commands.", required = false),
                ToolParameter("emoji", "string", "Reaction emoji for react (e.g. '👍','❤️','🔥','😂').", required = false),
                ToolParameter("photo", "string", "Photo URL or file_id for send_photo.", required = false),
                ToolParameter("document", "string", "Document URL or file_id for send_document.", required = false),
                ToolParameter("caption", "string", "Caption text for send_photo / send_document.", required = false),
                ToolParameter("latitude", "string", "Latitude for send_location / send_live_location.", required = false),
                ToolParameter("longitude", "string", "Longitude for send_location / send_live_location.", required = false),
                ToolParameter("live_period", "string", "Live period seconds for send_live_location (60-86400).", required = false),
                ToolParameter("phone_number", "string", "Phone number for send_contact.", required = false),
                ToolParameter("first_name", "string", "First name for send_contact.", required = false),
                ToolParameter("last_name", "string", "Last name (optional) for send_contact.", required = false),
                ToolParameter("file_id", "string", "File ID for get_file action.", required = false)
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
            "send_message"       -> sendMessage(botToken, args)
            "reply_to"           -> replyTo(botToken, args)
            "forward_message"    -> forwardMessage(botToken, args)
            "get_updates"        -> getUpdates(botToken, args)
            "get_bot_info"       -> getBotInfo(botToken)
            "get_chat_info"      -> getChatInfo(botToken, args)
            "delete_message"     -> deleteMessage(botToken, args)
            "pin_message"        -> pinMessage(botToken, args)
            "set_commands"       -> setMyCommands(botToken, args)
            "react"              -> reactMessage(botToken, args)
            "send_photo"         -> sendPhoto(botToken, args)
            "send_document"      -> sendDocument(botToken, args)
            "send_location"      -> sendLocation(botToken, args)
            "send_live_location" -> sendLiveLocation(botToken, args)
            "send_contact"       -> sendContact(botToken, args)
            "get_file"           -> getFile(botToken, args)
            else -> ToolExecutionResult(
                "Unknown action '$action'. Valid: send_message, reply_to, forward_message, " +
                "get_updates, get_bot_info, get_chat_info, delete_message, pin_message, " +
                "set_commands, react, send_photo, send_document, send_location, " +
                "send_live_location, send_contact, get_file.",
                isError = true
            )
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────

    private suspend fun sendMessage(token: String, args: Map<String, String>): ToolExecutionResult {
        val chatId = args["chat_id"] ?: return err("Missing: chat_id")
        val text   = args["message"]  ?: return err("Missing: message")
        val parseMode  = args["parse_mode"] ?: "Markdown"
        val replyToId  = args["message_id"]?.toLongOrNull()

        val body = JSONObject().apply {
            put("chat_id",    chatId)
            put("text",       text)
            put("parse_mode", parseMode)
            if (replyToId != null) {
                put("reply_parameters", JSONObject().put("message_id", replyToId))
            }
        }
        return post(token, "sendMessage", body) { json ->
            val msgId = json.optJSONObject("result")?.optLong("message_id")
            "✅ Message sent. message_id=$msgId"
        }
    }

    private suspend fun replyTo(token: String, args: Map<String, String>): ToolExecutionResult {
        val chatId     = args["chat_id"]  ?: return err("Missing: chat_id")
        val text       = args["message"]  ?: return err("Missing: message")
        val replyMsgId = args["message_id"]?.toLongOrNull()
            ?: return err("Missing: message_id (integer)")
        val parseMode  = args["parse_mode"] ?: "Markdown"

        val body = JSONObject().apply {
            put("chat_id",    chatId)
            put("text",       text)
            put("parse_mode", parseMode)
            put("reply_parameters", JSONObject().put("message_id", replyMsgId))
        }
        return post(token, "sendMessage", body) { json ->
            val msgId = json.optJSONObject("result")?.optLong("message_id")
            "✅ Reply sent. message_id=$msgId"
        }
    }

    private suspend fun forwardMessage(token: String, args: Map<String, String>): ToolExecutionResult {
        val chatId     = args["chat_id"]      ?: return err("Missing: chat_id")
        val fromChatId = args["from_chat_id"] ?: return err("Missing: from_chat_id")
        val msgId      = args["message_id"]?.toLongOrNull()
            ?: return err("Missing: message_id (integer)")

        val body = JSONObject().apply {
            put("chat_id",      chatId)
            put("from_chat_id", fromChatId)
            put("message_id",   msgId)
        }
        return post(token, "forwardMessage", body) { json ->
            val newId = json.optJSONObject("result")?.optLong("message_id")
            "✅ Message forwarded. new_message_id=$newId"
        }
    }

    /** Send an emoji reaction to a message (requires Bot API 7.0+). */
    private suspend fun reactMessage(token: String, args: Map<String, String>): ToolExecutionResult {
        val chatId = args["chat_id"]  ?: return err("Missing: chat_id")
        val msgId  = args["message_id"]?.toLongOrNull()
            ?: return err("Missing: message_id (integer)")
        val emoji  = args["emoji"] ?: "👍"

        val body = JSONObject().apply {
            put("chat_id",    chatId)
            put("message_id", msgId)
            put("reaction",   JSONArray().put(
                JSONObject().put("type", "emoji").put("emoji", emoji)
            ))
            put("is_big", false)
        }
        return post(token, "setMessageReaction", body) {
            "✅ Reacted with $emoji to message $msgId."
        }
    }

    /** Send a photo by URL or file_id. */
    private suspend fun sendPhoto(token: String, args: Map<String, String>): ToolExecutionResult {
        val chatId    = args["chat_id"] ?: return err("Missing: chat_id")
        val photo     = args["photo"]   ?: return err("Missing: photo (URL or file_id)")
        val caption   = args["caption"] ?: ""
        val replyToId = args["message_id"]?.toLongOrNull()

        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("photo",   photo)
            if (caption.isNotBlank()) { put("caption", caption); put("parse_mode", "Markdown") }
            if (replyToId != null) {
                put("reply_parameters", JSONObject().put("message_id", replyToId))
            }
        }
        return post(token, "sendPhoto", body) { json ->
            val msgId = json.optJSONObject("result")?.optLong("message_id")
            "✅ Photo sent. message_id=$msgId"
        }
    }

    /** Send a document/file by URL or file_id. */
    private suspend fun sendDocument(token: String, args: Map<String, String>): ToolExecutionResult {
        val chatId    = args["chat_id"]  ?: return err("Missing: chat_id")
        val document  = args["document"] ?: return err("Missing: document (URL or file_id)")
        val caption   = args["caption"]  ?: ""
        val replyToId = args["message_id"]?.toLongOrNull()

        val body = JSONObject().apply {
            put("chat_id",  chatId)
            put("document", document)
            if (caption.isNotBlank()) { put("caption", caption); put("parse_mode", "Markdown") }
            if (replyToId != null) {
                put("reply_parameters", JSONObject().put("message_id", replyToId))
            }
        }
        return post(token, "sendDocument", body) { json ->
            val msgId = json.optJSONObject("result")?.optLong("message_id")
            "✅ Document sent. message_id=$msgId"
        }
    }

    /** Send a static map location. */
    private suspend fun sendLocation(token: String, args: Map<String, String>): ToolExecutionResult {
        val chatId = args["chat_id"]   ?: return err("Missing: chat_id")
        val lat    = args["latitude"]?.toDoubleOrNull()  ?: return err("Missing: latitude")
        val lon    = args["longitude"]?.toDoubleOrNull() ?: return err("Missing: longitude")

        val body = JSONObject().apply {
            put("chat_id",   chatId)
            put("latitude",  lat)
            put("longitude", lon)
        }
        return post(token, "sendLocation", body) { json ->
            val msgId = json.optJSONObject("result")?.optLong("message_id")
            "✅ Location sent. message_id=$msgId"
        }
    }

    /** Send a live location (updates in real-time for [live_period] seconds). */
    private suspend fun sendLiveLocation(token: String, args: Map<String, String>): ToolExecutionResult {
        val chatId     = args["chat_id"]    ?: return err("Missing: chat_id")
        val lat        = args["latitude"]?.toDoubleOrNull()  ?: return err("Missing: latitude")
        val lon        = args["longitude"]?.toDoubleOrNull() ?: return err("Missing: longitude")
        val livePeriod = args["live_period"]?.toIntOrNull()?.coerceIn(60, 86400) ?: 3600

        val body = JSONObject().apply {
            put("chat_id",     chatId)
            put("latitude",    lat)
            put("longitude",   lon)
            put("live_period", livePeriod)
        }
        return post(token, "sendLocation", body) { json ->
            val msgId = json.optJSONObject("result")?.optLong("message_id")
            "✅ Live location sent (${livePeriod}s). message_id=$msgId"
        }
    }

    /** Send a contact card. */
    private suspend fun sendContact(token: String, args: Map<String, String>): ToolExecutionResult {
        val chatId    = args["chat_id"]     ?: return err("Missing: chat_id")
        val phone     = args["phone_number"] ?: return err("Missing: phone_number")
        val firstName = args["first_name"]  ?: return err("Missing: first_name")
        val lastName  = args["last_name"]   ?: ""

        val body = JSONObject().apply {
            put("chat_id",      chatId)
            put("phone_number", phone)
            put("first_name",   firstName)
            if (lastName.isNotBlank()) put("last_name", lastName)
        }
        return post(token, "sendContact", body) { json ->
            val msgId = json.optJSONObject("result")?.optLong("message_id")
            "✅ Contact sent. message_id=$msgId"
        }
    }

    /** Get file info and a direct download URL for any file_id. */
    private suspend fun getFile(token: String, args: Map<String, String>): ToolExecutionResult {
        val fileId = args["file_id"] ?: args["message_id"]
            ?: return err("Missing: file_id")

        return get(token, "getFile?file_id=${encode(fileId)}") { json ->
            val file = json.optJSONObject("result") ?: return@get "No file info"
            val path = file.optString("file_path", "")
            val size = file.optLong("file_size", 0)
            val downloadUrl = if (path.isNotBlank())
                "https://api.telegram.org/file/bot$token/$path"
            else "(unavailable)"
            buildString {
                append("📄 File Info:\n")
                append("  file_id: ${file.optString("file_id")}\n")
                append("  size: $size bytes\n")
                append("  download_url: $downloadUrl")
            }
        }
    }

    private suspend fun getUpdates(token: String, args: Map<String, String>): ToolExecutionResult {
        val offset  = args["offset"]?.toLongOrNull()
        val limit   = args["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
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
                conn.readTimeout    = (timeout + 15) * 1_000

                val code = conn.responseCode
                val body = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                           else conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                conn.disconnect()

                if (code !in 200..299) {
                    return@withContext ToolExecutionResult(
                        "Telegram getUpdates error ($code): $body", isError = true)
                }
                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) {
                    return@withContext ToolExecutionResult(
                        "Telegram API error: ${json.optString("description", "unknown")}",
                        isError = true)
                }

                val updates = json.optJSONArray("result") ?: JSONArray()
                if (updates.length() == 0) return@withContext ToolExecutionResult("No new messages.")

                val sb = StringBuilder("📨 ${updates.length()} update(s):\n\n")
                for (i in 0 until updates.length()) {
                    val update   = updates.getJSONObject(i)
                    val updateId = update.optLong("update_id")
                    val msg      = update.optJSONObject("message")
                        ?: update.optJSONObject("edited_message")
                        ?: update.optJSONObject("channel_post")
                    if (msg != null) {
                        val from  = msg.optJSONObject("from")
                        val chat  = msg.optJSONObject("chat")
                        val sender = from?.let {
                            val first    = it.optString("first_name", "")
                            val username = it.optString("username", "")
                            if (username.isNotBlank()) "@$username ($first)" else first
                        } ?: "unknown"
                        val chatTitle = chat?.let {
                            it.optString("title", "").ifBlank {
                                it.optString("username", it.optString("first_name", ""))
                            }
                        } ?: "unknown"
                        val chatId = chat?.optLong("id") ?: 0L
                        val msgId  = msg.optLong("message_id")

                        val content = when {
                            msg.has("text")       -> "💬 ${msg.optString("text")}"
                            msg.has("photo")      -> {
                                val cap = msg.optString("caption", "")
                                "📷 Photo${if (cap.isNotBlank()) ": $cap" else ""}"
                            }
                            msg.has("document")   -> "📄 ${msg.optJSONObject("document")?.optString("file_name", "document")}"
                            msg.has("location")   -> {
                                val loc = msg.optJSONObject("location")
                                val isLive = loc?.has("live_period") == true
                                "${if (isLive) "📍 Live location" else "📍 Location"}: " +
                                    "lat=${loc?.optDouble("latitude")}, lon=${loc?.optDouble("longitude")}"
                            }
                            msg.has("contact")    -> {
                                val c = msg.optJSONObject("contact")
                                "👤 Contact: ${c?.optString("first_name")} ${c?.optString("phone_number")}"
                            }
                            msg.has("sticker")    -> "🎭 Sticker ${msg.optJSONObject("sticker")?.optString("emoji", "")}"
                            msg.has("video")      -> "🎥 Video"
                            msg.has("audio")      -> "🎵 Audio"
                            msg.has("voice")      -> "🎤 Voice note"
                            msg.has("video_note") -> "📹 Video note"
                            else                  -> "[other]"
                        }
                        sb.append("update_id=$updateId\n")
                        sb.append("  from: $sender\n")
                        sb.append("  chat: $chatTitle (id=$chatId)\n")
                        sb.append("  message_id: $msgId\n")
                        sb.append("  $content\n\n")
                    } else {
                        sb.append("update_id=$updateId [non-message update]\n\n")
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
        val chatId = args["chat_id"] ?: return err("Missing: chat_id")
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
        val chatId = args["chat_id"]  ?: return err("Missing: chat_id")
        val msgId  = args["message_id"]?.toLongOrNull()
            ?: return err("Missing: message_id (integer)")
        val body = JSONObject().apply {
            put("chat_id",    chatId)
            put("message_id", msgId)
        }
        return post(token, "deleteMessage", body) { "✅ Message $msgId deleted." }
    }

    private suspend fun pinMessage(token: String, args: Map<String, String>): ToolExecutionResult {
        val chatId = args["chat_id"]  ?: return err("Missing: chat_id")
        val msgId  = args["message_id"]?.toLongOrNull()
            ?: return err("Missing: message_id (integer)")
        val body = JSONObject().apply {
            put("chat_id",              chatId)
            put("message_id",           msgId)
            put("disable_notification", false)
        }
        return post(token, "pinChatMessage", body) { "📌 Message $msgId pinned." }
    }

    private suspend fun setMyCommands(token: String, args: Map<String, String>): ToolExecutionResult {
        val commandsJson = args["commands"]
            ?: return err("Missing: commands (JSON array)")
        val body = JSONObject().apply { put("commands", JSONArray(commandsJson)) }
        return post(token, "setMyCommands", body) { "✅ Bot commands updated." }
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────

    private fun err(msg: String) = ToolExecutionResult(msg, isError = true)

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
            conn.readTimeout    = 15_000
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code     = conn.responseCode
            val respBody = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                           else conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            conn.disconnect()

            if (code !in 200..299) {
                return@withContext ToolExecutionResult("Telegram API error ($code): $respBody", isError = true)
            }
            val json = JSONObject(respBody)
            if (!json.optBoolean("ok", false)) {
                return@withContext ToolExecutionResult(
                    "Telegram API error: ${json.optString("description", respBody)}", isError = true)
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
            conn.readTimeout    = 15_000

            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                       else conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            conn.disconnect()

            if (code !in 200..299) {
                return@withContext ToolExecutionResult("Telegram API error ($code): $body", isError = true)
            }
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) {
                return@withContext ToolExecutionResult(
                    "Telegram API error: ${json.optString("description", body)}", isError = true)
            }
            ToolExecutionResult(onSuccess(json))
        } catch (e: IOException) {
            ToolExecutionResult("Telegram request failed: ${e.message}", isError = true)
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}

