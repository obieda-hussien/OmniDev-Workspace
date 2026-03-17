package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Full-featured Slack tool — rich bidirectional Slack Web API integration.
 *
 * Actions:
 *   send_message, send_reply, update_message, delete_message,
 *   get_messages, get_thread_replies, get_channels, get_users,
 *   create_channel, invite_user, pin_message, upload_file,
 *   get_user_info, set_channel_topic, search_messages
 *
 * Requires a Slack Bot Token in Settings → Integrations.
 */
object SlackTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "slack",
            description = "Full Slack Web API integration. Actions: send_message (channel+text), " +
                "send_reply (channel+text+thread_ts), update_message (channel+ts+text), " +
                "delete_message (channel+ts), get_messages (channel, limit), " +
                "get_thread_replies (channel+thread_ts), get_channels, get_users, " +
                "create_channel (channel_name, is_private), invite_user (channel+user_id), " +
                "pin_message (channel+ts), upload_file (channel+content+filename), " +
                "get_user_info (user_id), set_channel_topic (channel+topic), " +
                "search_messages (query, limit). " +
                "Requires Slack Bot Token in Settings → Integrations.",
            parameters = listOf(
                ToolParameter(
                    "action", "string",
                    "One of: send_message, send_reply, update_message, delete_message, " +
                        "get_messages, get_thread_replies, get_channels, get_users, " +
                        "create_channel, invite_user, pin_message, upload_file, " +
                        "get_user_info, set_channel_topic, search_messages.",
                    required = true
                ),
                ToolParameter("channel", "string", "Channel ID or name (e.g. C1234567 or #general).", required = false),
                ToolParameter("message", "string", "Message text to send or updated text.", required = false),
                ToolParameter("text", "string", "Alias for message — message text to send.", required = false),
                ToolParameter("thread_ts", "string", "Thread timestamp for send_reply / get_thread_replies.", required = false),
                ToolParameter("ts", "string", "Message timestamp for update_message, delete_message, pin_message.", required = false),
                ToolParameter("user_id", "string", "Slack user ID for invite_user or get_user_info.", required = false),
                ToolParameter("channel_name", "string", "New channel name for create_channel (lowercase, no spaces).", required = false),
                ToolParameter("query", "string", "Search query string for search_messages.", required = false),
                ToolParameter("limit", "string", "Max results to return (default 20).", required = false),
                ToolParameter("content", "string", "File/snippet content text for upload_file.", required = false),
                ToolParameter("filename", "string", "Filename for upload_file (e.g. snippet.txt).", required = false),
                ToolParameter("topic", "string", "Channel topic text for set_channel_topic.", required = false),
                ToolParameter("is_private", "string", "true/false — whether create_channel is private (default false).", required = false)
            )
        )
    )

    suspend fun execute(
        token: String?,
        args: Map<String, String>
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (token.isNullOrBlank()) {
            return@withContext ToolExecutionResult(
                "Slack Bot Token is not configured. Go to Settings → Integrations to set it up.",
                isError = true
            )
        }

        when (val action = args["action"]?.lowercase()?.trim() ?: "") {
            "send_message"       -> sendMessage(token, args)
            "send_reply"         -> sendReply(token, args)
            "update_message"     -> updateMessage(token, args)
            "delete_message"     -> deleteMessage(token, args)
            "get_messages"       -> getMessages(token, args)
            "get_thread_replies" -> getThreadReplies(token, args)
            "get_channels"       -> getChannels(token, args)
            "get_users"          -> getUsers(token, args)
            "create_channel"     -> createChannel(token, args)
            "invite_user"        -> inviteUser(token, args)
            "pin_message"        -> pinMessage(token, args)
            "upload_file"        -> uploadFile(token, args)
            "get_user_info"      -> getUserInfo(token, args)
            "set_channel_topic"  -> setChannelTopic(token, args)
            "search_messages"    -> searchMessages(token, args)
            else -> ToolExecutionResult(
                "Unknown action '$action'. Valid: send_message, send_reply, update_message, " +
                "delete_message, get_messages, get_thread_replies, get_channels, get_users, " +
                "create_channel, invite_user, pin_message, upload_file, " +
                "get_user_info, set_channel_topic, search_messages.",
                isError = true
            )
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────

    private suspend fun sendMessage(token: String, args: Map<String, String>): ToolExecutionResult {
        val channel = args["channel"] ?: return err("Missing: channel")
        val text    = args["message"] ?: args["text"] ?: return err("Missing: message or text")

        val body = JSONObject().apply {
            put("channel", channel)
            put("text", text)
        }
        return post(token, "chat.postMessage", body) { json ->
            val ts = json.optString("ts", "")
            "✅ Message sent. ts=$ts, channel=${json.optString("channel")}"
        }
    }

    private suspend fun sendReply(token: String, args: Map<String, String>): ToolExecutionResult {
        val channel  = args["channel"]   ?: return err("Missing: channel")
        val text     = args["message"]   ?: args["text"] ?: return err("Missing: message or text")
        val threadTs = args["thread_ts"] ?: return err("Missing: thread_ts")

        val body = JSONObject().apply {
            put("channel", channel)
            put("text", text)
            put("thread_ts", threadTs)
        }
        return post(token, "chat.postMessage", body) { json ->
            val ts = json.optString("ts", "")
            "✅ Reply sent in thread. ts=$ts"
        }
    }

    private suspend fun updateMessage(token: String, args: Map<String, String>): ToolExecutionResult {
        val channel = args["channel"] ?: return err("Missing: channel")
        val ts      = args["ts"]      ?: return err("Missing: ts")
        val text    = args["message"] ?: args["text"] ?: return err("Missing: message or text")

        val body = JSONObject().apply {
            put("channel", channel)
            put("ts", ts)
            put("text", text)
        }
        return post(token, "chat.update", body) { json ->
            "✅ Message updated. ts=${json.optString("ts")}"
        }
    }

    private suspend fun deleteMessage(token: String, args: Map<String, String>): ToolExecutionResult {
        val channel = args["channel"] ?: return err("Missing: channel")
        val ts      = args["ts"]      ?: return err("Missing: ts")

        val body = JSONObject().apply {
            put("channel", channel)
            put("ts", ts)
        }
        return post(token, "chat.delete", body) { "✅ Message $ts deleted from $channel." }
    }

    private suspend fun getMessages(token: String, args: Map<String, String>): ToolExecutionResult {
        val channel = args["channel"] ?: return err("Missing: channel")
        val limit   = args["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 20

        val body = JSONObject().apply {
            put("channel", channel)
            put("limit", limit)
        }
        return post(token, "conversations.history", body) { json ->
            val messages = json.optJSONArray("messages") ?: JSONArray()
            if (messages.length() == 0) return@post "No messages found in $channel."

            buildString {
                append("💬 ${messages.length()} message(s) from $channel:\n\n")
                for (i in 0 until messages.length()) {
                    val msg  = messages.getJSONObject(i)
                    val user = msg.optString("user", msg.optString("bot_id", "unknown"))
                    val ts   = msg.optString("ts", "")
                    val text = msg.optString("text", "[no text]")
                    val replyCount = msg.optInt("reply_count", 0)
                    append("[$ts] $user: $text")
                    if (replyCount > 0) append(" (🧵 $replyCount replies)")
                    append("\n")
                }
                val hasMore = json.optBoolean("has_more", false)
                if (hasMore) append("\n(more messages available)")
            }
        }
    }

    private suspend fun getThreadReplies(token: String, args: Map<String, String>): ToolExecutionResult {
        val channel  = args["channel"]   ?: return err("Missing: channel")
        val threadTs = args["thread_ts"] ?: return err("Missing: thread_ts")
        val limit    = args["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 20

        val body = JSONObject().apply {
            put("channel", channel)
            put("ts", threadTs)
            put("limit", limit)
        }
        return post(token, "conversations.replies", body) { json ->
            val messages = json.optJSONArray("messages") ?: JSONArray()
            if (messages.length() == 0) return@post "No replies found for thread $threadTs."

            buildString {
                append("🧵 ${messages.length()} message(s) in thread $threadTs:\n\n")
                for (i in 0 until messages.length()) {
                    val msg  = messages.getJSONObject(i)
                    val user = msg.optString("user", msg.optString("bot_id", "unknown"))
                    val ts   = msg.optString("ts", "")
                    val text = msg.optString("text", "[no text]")
                    val prefix = if (i == 0) "📌" else "↳"
                    append("$prefix [$ts] $user: $text\n")
                }
            }
        }
    }

    private suspend fun getChannels(token: String, args: Map<String, String>): ToolExecutionResult {
        val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 100

        val body = JSONObject().apply {
            put("types", "public_channel,private_channel")
            put("limit", limit)
            put("exclude_archived", true)
        }
        return post(token, "conversations.list", body) { json ->
            val channels = json.optJSONArray("channels") ?: JSONArray()
            if (channels.length() == 0) return@post "No channels found."

            buildString {
                append("📋 ${channels.length()} channel(s):\n\n")
                for (i in 0 until channels.length()) {
                    val ch      = channels.getJSONObject(i)
                    val id      = ch.optString("id")
                    val name    = ch.optString("name")
                    val isPriv  = ch.optBoolean("is_private", false)
                    val members = ch.optInt("num_members", 0)
                    val icon    = if (isPriv) "🔒" else "#"
                    append("$icon $name (id=$id, members=$members)\n")
                }
                val nextCursor = json.optJSONObject("response_metadata")?.optString("next_cursor", "")
                if (!nextCursor.isNullOrBlank()) append("\n(more channels available)")
            }
        }
    }

    private suspend fun getUsers(token: String, args: Map<String, String>): ToolExecutionResult {
        val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50

        val body = JSONObject().apply {
            put("limit", limit)
        }
        return post(token, "users.list", body) { json ->
            val members = json.optJSONArray("members") ?: JSONArray()
            if (members.length() == 0) return@post "No users found."

            buildString {
                val active = mutableListOf<String>()
                for (i in 0 until members.length()) {
                    val u = members.getJSONObject(i)
                    if (u.optBoolean("deleted", false) || u.optBoolean("is_bot", false)) continue
                    val id          = u.optString("id")
                    val name        = u.optString("name")
                    val realName    = u.optJSONObject("profile")?.optString("real_name", name) ?: name
                    val isAdmin     = u.optBoolean("is_admin", false)
                    val adminMark   = if (isAdmin) " 👑" else ""
                    active.add("• $realName (@$name, id=$id)$adminMark")
                }
                append("👥 ${active.size} active user(s):\n\n")
                append(active.joinToString("\n"))
                val nextCursor = json.optJSONObject("response_metadata")?.optString("next_cursor", "")
                if (!nextCursor.isNullOrBlank()) append("\n\n(more users available)")
            }
        }
    }

    private suspend fun createChannel(token: String, args: Map<String, String>): ToolExecutionResult {
        val name      = args["channel_name"] ?: return err("Missing: channel_name")
        val isPrivate = args["is_private"]?.lowercase() == "true"

        val body = JSONObject().apply {
            put("name", name)
            put("is_private", isPrivate)
        }
        return post(token, "conversations.create", body) { json ->
            val ch   = json.optJSONObject("channel") ?: return@post "Channel created."
            val id   = ch.optString("id")
            val icon = if (isPrivate) "🔒" else "#"
            "✅ Channel created: $icon$name (id=$id)"
        }
    }

    private suspend fun inviteUser(token: String, args: Map<String, String>): ToolExecutionResult {
        val channel = args["channel"] ?: return err("Missing: channel")
        val userId  = args["user_id"] ?: return err("Missing: user_id")

        val body = JSONObject().apply {
            put("channel", channel)
            put("users", userId)
        }
        return post(token, "conversations.invite", body) { json ->
            val chName = json.optJSONObject("channel")?.optString("name", channel) ?: channel
            "✅ User $userId invited to #$chName."
        }
    }

    private suspend fun pinMessage(token: String, args: Map<String, String>): ToolExecutionResult {
        val channel = args["channel"] ?: return err("Missing: channel")
        val ts      = args["ts"]      ?: return err("Missing: ts")

        val body = JSONObject().apply {
            put("channel", channel)
            put("timestamp", ts)
        }
        return post(token, "pins.add", body) { "📌 Message $ts pinned in $channel." }
    }

    private suspend fun uploadFile(token: String, args: Map<String, String>): ToolExecutionResult {
        val channel  = args["channel"]  ?: return err("Missing: channel")
        val content  = args["content"]  ?: return err("Missing: content")
        val filename = args["filename"] ?: "snippet.txt"

        val body = JSONObject().apply {
            put("channels", channel)
            put("content", content)
            put("filename", filename)
        }
        return post(token, "files.upload", body) { json ->
            val file = json.optJSONObject("file")
            val id   = file?.optString("id", "") ?: ""
            val name = file?.optString("name", filename) ?: filename
            "✅ File uploaded: $name (id=$id) to $channel."
        }
    }

    private suspend fun getUserInfo(token: String, args: Map<String, String>): ToolExecutionResult {
        val userId = args["user_id"] ?: return err("Missing: user_id")

        val body = JSONObject().apply {
            put("user", userId)
        }
        return post(token, "users.info", body) { json ->
            val u       = json.optJSONObject("user") ?: return@post "No user info returned."
            val profile = u.optJSONObject("profile")
            buildString {
                append("👤 User Info:\n")
                append("  id: ${u.optString("id")}\n")
                append("  name: @${u.optString("name")}\n")
                append("  real_name: ${profile?.optString("real_name", "") ?: ""}\n")
                append("  email: ${profile?.optString("email", "(hidden)") ?: "(hidden)"}\n")
                append("  title: ${profile?.optString("title", "") ?: ""}\n")
                append("  is_admin: ${u.optBoolean("is_admin", false)}\n")
                append("  is_bot: ${u.optBoolean("is_bot", false)}\n")
                append("  deleted: ${u.optBoolean("deleted", false)}")
            }
        }
    }

    private suspend fun setChannelTopic(token: String, args: Map<String, String>): ToolExecutionResult {
        val channel = args["channel"] ?: return err("Missing: channel")
        val topic   = args["topic"]   ?: return err("Missing: topic")

        val body = JSONObject().apply {
            put("channel", channel)
            put("topic", topic)
        }
        return post(token, "conversations.setTopic", body) { json ->
            val newTopic = json.optString("topic", topic)
            "✅ Channel topic set: \"$newTopic\""
        }
    }

    private suspend fun searchMessages(token: String, args: Map<String, String>): ToolExecutionResult {
        val query = args["query"] ?: return err("Missing: query")
        val count = args["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20

        val body = JSONObject().apply {
            put("query", query)
            put("count", count)
            put("sort", "timestamp")
        }
        return post(token, "search.messages", body) { json ->
            val matches = json.optJSONObject("messages")?.optJSONArray("matches") ?: JSONArray()
            if (matches.length() == 0) return@post "No messages found for query: \"$query\"."

            buildString {
                val total = json.optJSONObject("messages")?.optJSONObject("paging")?.optInt("total", matches.length()) ?: matches.length()
                append("🔍 Found $total result(s) for \"$query\" (showing ${matches.length()}):\n\n")
                for (i in 0 until matches.length()) {
                    val msg     = matches.getJSONObject(i)
                    val channel = msg.optJSONObject("channel")?.optString("name", "?") ?: "?"
                    val user    = msg.optString("username", msg.optString("user", "unknown"))
                    val ts      = msg.optString("ts", "")
                    val text    = msg.optString("text", "[no text]")
                    append("#$channel [$ts] $user: $text\n")
                }
            }
        }
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
            val conn = URL("https://slack.com/api/$method")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout    = 15_000

            conn.outputStream.use { out: OutputStream ->
                out.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code     = conn.responseCode
            val respBody = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                           else conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            conn.disconnect()

            if (code !in 200..299) {
                return@withContext ToolExecutionResult(
                    "Slack API HTTP error ($code): $respBody", isError = true)
            }

            val json = JSONObject(respBody)
            if (!json.optBoolean("ok", false)) {
                val error   = json.optString("error", "unknown_error")
                val warning = json.optString("warning", "")
                val detail  = if (warning.isNotBlank()) "$error (warning: $warning)" else error
                return@withContext ToolExecutionResult(
                    "Slack API error: $detail", isError = true)
            }

            ToolExecutionResult(onSuccess(json))
        } catch (e: IOException) {
            ToolExecutionResult("Slack $method failed: ${e.message}", isError = true)
        }
    }
}
