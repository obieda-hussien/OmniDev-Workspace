package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Full-featured Discord Bot tool — rich bidirectional Discord Bot API integration.
 *
 * Actions:
 *   send_message, reply_to, send_embed, add_reaction, delete_message,
 *   get_messages, get_channels, get_guilds, get_guild_members,
 *   get_channel_info, get_user, send_dm, pin_message, create_thread,
 *   search_messages
 *
 * Requires DISCORD_BOT_TOKEN in Settings → Integrations.
 */
object DiscordBotTool {

    private const val BASE = "https://discord.com/api/v10"

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "discord_bot",
            description = "Full Discord Bot API integration. Actions: send_message, reply_to, " +
                "send_embed, add_reaction, delete_message, pin_message, get_messages, " +
                "get_channels, get_guilds, get_guild_members, get_channel_info, " +
                "get_user, send_dm, create_thread, search_messages. " +
                "Requires Discord Bot Token in Settings → Integrations.",
            parameters = listOf(
                ToolParameter(
                    "action", "string",
                    "One of: send_message, reply_to, send_embed, add_reaction, delete_message, " +
                        "pin_message, get_messages, get_channels, get_guilds, get_guild_members, " +
                        "get_channel_info, get_user, send_dm, create_thread, search_messages.",
                    required = true
                ),
                ToolParameter("channel_id", "string", "Target channel ID.", required = false),
                ToolParameter("guild_id", "string", "Server/Guild ID.", required = false),
                ToolParameter("message_id", "string", "Message ID (reply_to/delete/pin/react/thread).", required = false),
                ToolParameter("user_id", "string", "User ID (get_user / send_dm).", required = false),
                ToolParameter("message", "string", "Text content to send.", required = false),
                ToolParameter("title", "string", "Embed title for send_embed.", required = false),
                ToolParameter("description", "string", "Embed description for send_embed.", required = false),
                ToolParameter("color", "string", "Embed color hex e.g. FF5733 for send_embed.", required = false),
                ToolParameter("emoji", "string", "Emoji for add_reaction (e.g. '👍' or 'custom_name:id').", required = false),
                ToolParameter("limit", "string", "Max messages to fetch (1-100, default 20).", required = false),
                ToolParameter("before", "string", "Fetch messages before this message ID.", required = false),
                ToolParameter("after", "string", "Fetch messages after this message ID.", required = false),
                ToolParameter("thread_name", "string", "Name for create_thread.", required = false),
                ToolParameter("query", "string", "Search query string for search_messages.", required = false),
                ToolParameter("auto_archive_duration", "string", "Thread auto-archive duration in minutes (60/1440/4320/10080).", required = false),
                ToolParameter("image_url", "string", "Image URL for send_embed.", required = false),
                ToolParameter("footer", "string", "Footer text for send_embed.", required = false)
            )
        )
    )

    suspend fun execute(
        botToken: String?,
        args: Map<String, String>
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (botToken.isNullOrBlank()) {
            return@withContext ToolExecutionResult(
                "Discord Bot Token is not configured. Go to Settings → Integrations to set it up.",
                isError = true
            )
        }

        when (val action = args["action"]?.lowercase()?.trim() ?: "") {
            "send_message"       -> sendMessage(botToken, args)
            "reply_to"           -> replyTo(botToken, args)
            "send_embed"         -> sendEmbed(botToken, args)
            "add_reaction"       -> addReaction(botToken, args)
            "delete_message"     -> deleteMessage(botToken, args)
            "pin_message"        -> pinMessage(botToken, args)
            "get_messages"       -> getMessages(botToken, args)
            "get_channels"       -> getChannels(botToken, args)
            "get_guilds"         -> getGuilds(botToken)
            "get_guild_members"  -> getGuildMembers(botToken, args)
            "get_channel_info"   -> getChannelInfo(botToken, args)
            "get_user"           -> getUser(botToken, args)
            "send_dm"            -> sendDm(botToken, args)
            "create_thread"      -> createThread(botToken, args)
            "search_messages"    -> searchMessages(botToken, args)
            else -> ToolExecutionResult(
                "Unknown action '$action'. Valid: send_message, reply_to, send_embed, add_reaction, " +
                "delete_message, pin_message, get_messages, get_channels, get_guilds, " +
                "get_guild_members, get_channel_info, get_user, send_dm, create_thread, search_messages.",
                isError = true
            )
        }
    }

    // ── Private action implementations ─────────────────────────────────────

    private fun sendMessage(token: String, args: Map<String, String>): ToolExecutionResult {
        val channelId = args["channel_id"] ?: return ToolExecutionResult("channel_id is required.", isError = true)
        val text = args["message"] ?: return ToolExecutionResult("message is required.", isError = true)
        val payload = JSONObject().put("content", text)
        val resp = discordPost("$BASE/channels/$channelId/messages", token, payload) ?: return networkError()
        return if (resp.optString("id").isNotBlank())
            ToolExecutionResult("✅ Message sent. ID: ${resp.optString("id")}")
        else ToolExecutionResult("Error: ${resp.optString("message", "Unknown error")}", isError = true)
    }

    private fun replyTo(token: String, args: Map<String, String>): ToolExecutionResult {
        val channelId = args["channel_id"] ?: return ToolExecutionResult("channel_id is required.", isError = true)
        val msgId    = args["message_id"] ?: return ToolExecutionResult("message_id is required.", isError = true)
        val text     = args["message"] ?: return ToolExecutionResult("message is required.", isError = true)
        val payload  = JSONObject()
            .put("content", text)
            .put("message_reference", JSONObject()
                .put("message_id", msgId)
                .put("channel_id", channelId)
                .put("fail_if_not_exists", false))
        val resp = discordPost("$BASE/channels/$channelId/messages", token, payload) ?: return networkError()
        return if (resp.optString("id").isNotBlank())
            ToolExecutionResult("✅ Reply sent. ID: ${resp.optString("id")}")
        else ToolExecutionResult("Error: ${resp.optString("message", "Unknown error")}", isError = true)
    }

    private fun sendEmbed(token: String, args: Map<String, String>): ToolExecutionResult {
        val channelId = args["channel_id"] ?: return ToolExecutionResult("channel_id is required.", isError = true)
        val embed = JSONObject()
        args["title"]?.let { embed.put("title", it) }
        args["description"]?.let { embed.put("description", it) }
        args["image_url"]?.let { embed.put("image", JSONObject().put("url", it)) }
        args["footer"]?.let { embed.put("footer", JSONObject().put("text", it)) }
        args["color"]?.let { hex ->
            hex.replace("#", "").toLongOrNull(16)?.and(0xFFFFFFL)?.toInt()?.let { embed.put("color", it) }
        }
        val payload = JSONObject().put("embeds", JSONArray().put(embed))
        val resp = discordPost("$BASE/channels/$channelId/messages", token, payload) ?: return networkError()
        return if (resp.optString("id").isNotBlank())
            ToolExecutionResult("✅ Embed sent. ID: ${resp.optString("id")}")
        else ToolExecutionResult("Error: ${resp.optString("message", "Unknown error")}", isError = true)
    }

    private fun addReaction(token: String, args: Map<String, String>): ToolExecutionResult {
        val channelId = args["channel_id"] ?: return ToolExecutionResult("channel_id is required.", isError = true)
        val msgId    = args["message_id"] ?: return ToolExecutionResult("message_id is required.", isError = true)
        val emoji    = args["emoji"] ?: return ToolExecutionResult("emoji is required.", isError = true)
        val encoded  = java.net.URLEncoder.encode(emoji, "UTF-8")
        val code = discordPut("$BASE/channels/$channelId/messages/$msgId/reactions/$encoded/@me", token)
        return if (code in 200..299)
            ToolExecutionResult("✅ Reaction $emoji added.")
        else ToolExecutionResult("Error: HTTP $code while adding reaction.", isError = true)
    }

    private fun deleteMessage(token: String, args: Map<String, String>): ToolExecutionResult {
        val channelId = args["channel_id"] ?: return ToolExecutionResult("channel_id is required.", isError = true)
        val msgId    = args["message_id"] ?: return ToolExecutionResult("message_id is required.", isError = true)
        val code = discordDelete("$BASE/channels/$channelId/messages/$msgId", token)
        return if (code in 200..299)
            ToolExecutionResult("✅ Message $msgId deleted.")
        else ToolExecutionResult("Error: HTTP $code while deleting message.", isError = true)
    }

    private fun pinMessage(token: String, args: Map<String, String>): ToolExecutionResult {
        val channelId = args["channel_id"] ?: return ToolExecutionResult("channel_id is required.", isError = true)
        val msgId    = args["message_id"] ?: return ToolExecutionResult("message_id is required.", isError = true)
        val code = discordPut("$BASE/channels/$channelId/pins/$msgId", token)
        return if (code in 200..299)
            ToolExecutionResult("✅ Message $msgId pinned.")
        else ToolExecutionResult("Error: HTTP $code while pinning message.", isError = true)
    }

    private fun getMessages(token: String, args: Map<String, String>): ToolExecutionResult {
        val channelId = args["channel_id"] ?: return ToolExecutionResult("channel_id is required.", isError = true)
        val limit  = args["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        var query  = "?limit=$limit"
        args["before"]?.let { query += "&before=$it" }
        args["after"]?.let  { query += "&after=$it"  }
        val arr = discordGetArray("$BASE/channels/$channelId/messages$query", token) ?: return networkError()
        if (arr.length() == 0) return ToolExecutionResult("No messages found.")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val m  = arr.getJSONObject(i)
            val id = m.optString("id")
            val author = m.optJSONObject("author")?.optString("username", "unknown") ?: "unknown"
            val content = m.optString("content", "(no text)")
            sb.appendLine("[$id] @$author: $content")
        }
        return ToolExecutionResult(sb.trim().toString())
    }

    private fun getChannels(token: String, args: Map<String, String>): ToolExecutionResult {
        val guildId = args["guild_id"] ?: return ToolExecutionResult("guild_id is required.", isError = true)
        val arr = discordGetArray("$BASE/guilds/$guildId/channels", token) ?: return networkError()
        if (arr.length() == 0) return ToolExecutionResult("No channels found.")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            sb.appendLine("${c.optString("id")} — #${c.optString("name")} (type ${c.optInt("type")})")
        }
        return ToolExecutionResult(sb.trim().toString())
    }

    private fun getGuilds(token: String): ToolExecutionResult {
        val arr = discordGetArray("$BASE/users/@me/guilds", token) ?: return networkError()
        if (arr.length() == 0) return ToolExecutionResult("Bot is not in any guild.")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i)
            sb.appendLine("${g.optString("id")} — ${g.optString("name")}")
        }
        return ToolExecutionResult(sb.trim().toString())
    }

    private fun getGuildMembers(token: String, args: Map<String, String>): ToolExecutionResult {
        val guildId = args["guild_id"] ?: return ToolExecutionResult("guild_id is required.", isError = true)
        val limit   = args["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val arr = discordGetArray("$BASE/guilds/$guildId/members?limit=$limit", token) ?: return networkError()
        if (arr.length() == 0) return ToolExecutionResult("No members found.")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val user = m.optJSONObject("user")
            val username = user?.optString("username", "unknown") ?: "unknown"
            val userId   = user?.optString("id", "") ?: ""
            val nick     = m.optString("nick", "")
            val display  = if (nick.isNotBlank()) "$nick ($username)" else username
            sb.appendLine("$userId — $display")
        }
        return ToolExecutionResult(sb.trim().toString())
    }

    private fun getChannelInfo(token: String, args: Map<String, String>): ToolExecutionResult {
        val channelId = args["channel_id"] ?: return ToolExecutionResult("channel_id is required.", isError = true)
        val obj = discordGet("$BASE/channels/$channelId", token) ?: return networkError()
        val info = buildString {
            appendLine("ID: ${obj.optString("id")}")
            appendLine("Name: #${obj.optString("name")}")
            appendLine("Type: ${obj.optInt("type")}")
            appendLine("Guild ID: ${obj.optString("guild_id", "N/A")}")
            appendLine("Topic: ${obj.optString("topic", "(none)")}")
            appendLine("NSFW: ${obj.optBoolean("nsfw")}")
        }
        return ToolExecutionResult(info.trim())
    }

    private fun getUser(token: String, args: Map<String, String>): ToolExecutionResult {
        val userId = args["user_id"] ?: return ToolExecutionResult("user_id is required.", isError = true)
        val obj = discordGet("$BASE/users/$userId", token) ?: return networkError()
        val info = buildString {
            appendLine("ID: ${obj.optString("id")}")
            appendLine("Username: ${obj.optString("username")}")
            appendLine("Bot: ${obj.optBoolean("bot")}")
        }
        return ToolExecutionResult(info.trim())
    }

    private fun sendDm(token: String, args: Map<String, String>): ToolExecutionResult {
        val userId  = args["user_id"]  ?: return ToolExecutionResult("user_id is required.", isError = true)
        val message = args["message"]  ?: return ToolExecutionResult("message is required.", isError = true)
        // Step 1: Create DM channel
        val dmChannel = discordPost("$BASE/users/@me/channels", token, JSONObject().put("recipient_id", userId))
            ?: return networkError()
        val channelId = dmChannel.optString("id")
        if (channelId.isBlank()) return ToolExecutionResult("Error: could not create DM channel.", isError = true)
        // Step 2: Send message
        val resp = discordPost("$BASE/channels/$channelId/messages", token, JSONObject().put("content", message))
            ?: return networkError()
        return if (resp.optString("id").isNotBlank())
            ToolExecutionResult("✅ DM sent. Message ID: ${resp.optString("id")}")
        else ToolExecutionResult("Error: ${resp.optString("message", "Unknown error")}", isError = true)
    }

    private fun createThread(token: String, args: Map<String, String>): ToolExecutionResult {
        val channelId  = args["channel_id"]  ?: return ToolExecutionResult("channel_id is required.", isError = true)
        val threadName = args["thread_name"] ?: return ToolExecutionResult("thread_name is required.", isError = true)
        val autoArchive = args["auto_archive_duration"]?.toIntOrNull() ?: 1440
        val payload = JSONObject()
            .put("name", threadName)
            .put("auto_archive_duration", autoArchive)
            .put("type", 11) // GUILD_PUBLIC_THREAD
        // If message_id provided, create a thread on that message
        val msgId = args["message_id"]
        val url = if (msgId != null)
            "$BASE/channels/$channelId/messages/$msgId/threads"
        else
            "$BASE/channels/$channelId/threads"
        val resp = discordPost(url, token, payload) ?: return networkError()
        return if (resp.optString("id").isNotBlank())
            ToolExecutionResult("✅ Thread created: '${resp.optString("name")}' ID: ${resp.optString("id")}")
        else ToolExecutionResult("Error: ${resp.optString("message", "Unknown error")}", isError = true)
    }

    private fun searchMessages(token: String, args: Map<String, String>): ToolExecutionResult {
        val guildId = args["guild_id"]
        val channelId = args["channel_id"]
        val query = args["query"] ?: return ToolExecutionResult("query is required.", isError = true)
        val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 25) ?: 10
        val url = when {
            guildId != null   -> "$BASE/guilds/$guildId/messages/search?content=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=$limit"
            channelId != null -> "$BASE/channels/$channelId/messages/search?content=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=$limit"
            else -> return ToolExecutionResult("Either guild_id or channel_id is required.", isError = true)
        }
        val obj = discordGet(url, token) ?: return networkError()
        val messages = obj.optJSONArray("messages")
        if (messages == null || messages.length() == 0) return ToolExecutionResult("No messages found for '$query'.")
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val group = messages.getJSONArray(i)
            if (group.length() == 0) continue
            val m  = group.getJSONObject(0)
            val id = m.optString("id")
            val author = m.optJSONObject("author")?.optString("username", "?") ?: "?"
            val content = m.optString("content", "(no text)").take(200)
            sb.appendLine("[$id] @$author: $content")
        }
        return ToolExecutionResult(if (sb.isEmpty()) "No results." else sb.trim().toString())
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────

    private fun discordGet(url: String, token: String): JSONObject? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bot $token")
            setRequestProperty("Content-Type", "application/json")
        }
        if (conn.responseCode in 200..299)
            JSONObject(conn.inputStream.bufferedReader().readText())
        else null
    } catch (_: Exception) { null }

    private fun discordGetArray(url: String, token: String): JSONArray? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bot $token")
            setRequestProperty("Content-Type", "application/json")
        }
        if (conn.responseCode in 200..299)
            JSONArray(conn.inputStream.bufferedReader().readText())
        else null
    } catch (_: Exception) { null }

    private fun discordPost(url: String, token: String, payload: JSONObject): JSONObject? = try {
        val body = payload.toString().toByteArray()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bot $token")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            outputStream.write(body)
        }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        JSONObject(stream?.bufferedReader()?.readText() ?: "{}")
    } catch (_: Exception) { null }

    private fun discordPut(url: String, token: String): Int = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            setRequestProperty("Authorization", "Bot $token")
            setRequestProperty("Content-Length", "0")
        }
        conn.responseCode
    } catch (_: Exception) { 0 }

    private fun discordDelete(url: String, token: String): Int = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            setRequestProperty("Authorization", "Bot $token")
        }
        conn.responseCode
    } catch (_: Exception) { 0 }

    private fun networkError() =
        ToolExecutionResult("Network error: could not reach Discord API.", isError = true)
}
