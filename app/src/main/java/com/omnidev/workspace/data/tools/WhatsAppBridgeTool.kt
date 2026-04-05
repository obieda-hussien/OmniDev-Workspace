package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI-callable WhatsApp integration via a self-hosted **Baileys bridge server**.
 *
 * Baileys (https://github.com/WhiskeySockets/Baileys) is an open-source Node.js library
 * implementing the WhatsApp Web protocol. The bridge server exposes REST endpoints that this
 * tool calls from the Android app.
 *
 * ## Bridge Server Setup (Node.js)
 * Run `npx @omnidev/whatsapp-bridge` or use the bundled companion server.
 * The server exposes:
 *   - `POST /pair`           — request pairing code for a phone number
 *   - `GET  /status`         — connection status (connecting/connected/disconnected)
 *   - `GET  /messages`       — fetch recent messages (optional `?since=<timestamp>`)
 *   - `POST /send`           — send a text message
 *   - `POST /send-image`     — send an image with optional caption
 *   - `POST /send-document`  — send a document/file
 *   - `POST /send-location`  — send a location pin
 *   - `POST /send-contact`   — send a contact card
 *   - `POST /mark-read`      — mark messages as read
 *   - `POST /react`          — react to a message with emoji
 *   - `DELETE /session`      — logout / reset the Baileys session
 *
 * ## Tool Actions
 *   get_status, send_message, send_image, send_document, send_location,
 *   send_contact, mark_read, react, get_messages, logout
 */
object WhatsAppBridgeTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "whatsapp_bridge",
            description = "WhatsApp integration via a self-hosted Baileys bridge server. " +
                "Supports sending messages, images, documents, locations, contacts, reactions. " +
                "Also reads incoming messages. " +
                "Actions: get_status, send_message, send_image, send_document, send_location, " +
                "send_contact, mark_read, react, get_messages, logout. " +
                "Requires WhatsApp Bridge URL in Settings → Integrations → WhatsApp Bridge.",
            parameters = listOf(
                ToolParameter(
                    "action", "string",
                    "One of: get_status, send_message, send_image, send_document, " +
                        "send_location, send_contact, mark_read, react, get_messages, logout.",
                    required = true
                ),
                ToolParameter("to", "string", "Recipient's phone number in international format e.g. '201012345678' or JID e.g. '201012345678@s.whatsapp.net'.", required = false),
                ToolParameter("message", "string", "Text message body.", required = false),
                ToolParameter("message_id", "string", "Message ID for mark_read or react.", required = false),
                ToolParameter("emoji", "string", "Emoji for react action e.g. '❤️'.", required = false),
                ToolParameter("image_url", "string", "Public URL of image to send.", required = false),
                ToolParameter("caption", "string", "Caption for image or document.", required = false),
                ToolParameter("document_url", "string", "Public URL of document to send.", required = false),
                ToolParameter("filename", "string", "Filename for the document.", required = false),
                ToolParameter("latitude", "string", "Latitude for send_location.", required = false),
                ToolParameter("longitude", "string", "Longitude for send_location.", required = false),
                ToolParameter("location_name", "string", "Name/label for the location.", required = false),
                ToolParameter("contact_name", "string", "Full name of contact for send_contact.", required = false),
                ToolParameter("contact_phone", "string", "Phone number of contact for send_contact.", required = false),
                ToolParameter("since", "string", "Unix timestamp in ms to fetch messages since (for get_messages).", required = false),
                ToolParameter("limit", "string", "Max number of messages to return for get_messages (default 20).", required = false)
            )
        )
    )

    suspend fun execute(bridgeUrl: String?, args: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            if (bridgeUrl.isNullOrBlank()) {
                return@withContext ToolExecutionResult(
                    "WhatsApp Bridge is not configured. Go to Settings → Integrations → " +
                        "WhatsApp Bridge and enter your bridge server URL.",
                    isError = true
                )
            }
            val base = bridgeUrl.trimEnd('/')

            when (val action = args["action"]?.lowercase()?.trim() ?: "") {
                "get_status" -> getStatus(base)
                "send_message" -> {
                    val to = args["to"] ?: return@withContext ToolExecutionResult("Missing 'to' parameter.", isError = true)
                    val msg = args["message"] ?: return@withContext ToolExecutionResult("Missing 'message' parameter.", isError = true)
                    sendText(base, to, msg)
                }
                "send_image" -> {
                    val to = args["to"] ?: return@withContext ToolExecutionResult("Missing 'to' parameter.", isError = true)
                    val url = args["image_url"] ?: return@withContext ToolExecutionResult("Missing 'image_url' parameter.", isError = true)
                    sendImage(base, to, url, args["caption"])
                }
                "send_document" -> {
                    val to = args["to"] ?: return@withContext ToolExecutionResult("Missing 'to' parameter.", isError = true)
                    val url = args["document_url"] ?: return@withContext ToolExecutionResult("Missing 'document_url' parameter.", isError = true)
                    sendDocument(base, to, url, args["filename"], args["caption"])
                }
                "send_location" -> {
                    val to = args["to"] ?: return@withContext ToolExecutionResult("Missing 'to' parameter.", isError = true)
                    val lat = args["latitude"] ?: return@withContext ToolExecutionResult("Missing 'latitude' parameter.", isError = true)
                    val lon = args["longitude"] ?: return@withContext ToolExecutionResult("Missing 'longitude' parameter.", isError = true)
                    sendLocation(base, to, lat, lon, args["location_name"])
                }
                "send_contact" -> {
                    val to = args["to"] ?: return@withContext ToolExecutionResult("Missing 'to' parameter.", isError = true)
                    val name = args["contact_name"] ?: return@withContext ToolExecutionResult("Missing 'contact_name' parameter.", isError = true)
                    val phone = args["contact_phone"] ?: return@withContext ToolExecutionResult("Missing 'contact_phone' parameter.", isError = true)
                    sendContact(base, to, name, phone)
                }
                "mark_read" -> {
                    val msgId = args["message_id"] ?: return@withContext ToolExecutionResult("Missing 'message_id' parameter.", isError = true)
                    markRead(base, msgId)
                }
                "react" -> {
                    val msgId = args["message_id"] ?: return@withContext ToolExecutionResult("Missing 'message_id' parameter.", isError = true)
                    val emoji = args["emoji"] ?: return@withContext ToolExecutionResult("Missing 'emoji' parameter.", isError = true)
                    react(base, msgId, emoji)
                }
                "get_messages" -> getMessages(base, args["since"], args["limit"]?.toIntOrNull() ?: 20)
                "logout" -> logout(base)
                else -> ToolExecutionResult("Unknown action: '$action'. Valid actions: get_status, send_message, send_image, send_document, send_location, send_contact, mark_read, react, get_messages, logout.", isError = true)
            }
        }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun getStatus(base: String): ToolExecutionResult {
        val obj = getJson("$base/status") ?: return ToolExecutionResult("Bridge unreachable — check that the Baileys bridge server is running.", isError = true)
        val status = obj.optString("status", "unknown")
        val phone = obj.optString("phone", "")
        val displayName = obj.optString("displayName", "")
        return ToolExecutionResult(
            "WhatsApp Bridge status: $status" +
                (if (phone.isNotBlank()) "\nPhone: $phone" else "") +
                (if (displayName.isNotBlank()) "\nName: $displayName" else "")
        )
    }

    private fun sendText(base: String, to: String, message: String): ToolExecutionResult {
        val body = JSONObject().apply {
            put("to", to)
            put("message", message)
        }
        val obj = postJson("$base/send", body) ?: return ToolExecutionResult("Failed to send message — bridge unreachable.", isError = true)
        val msgId = obj.optString("messageId", "")
        return ToolExecutionResult("✅ Message sent to $to" + (if (msgId.isNotBlank()) "\nID: $msgId" else ""))
    }

    private fun sendImage(base: String, to: String, imageUrl: String, caption: String?): ToolExecutionResult {
        val body = JSONObject().apply {
            put("to", to)
            put("imageUrl", imageUrl)
            if (!caption.isNullOrBlank()) put("caption", caption)
        }
        val obj = postJson("$base/send-image", body) ?: return ToolExecutionResult("Failed to send image — bridge unreachable.", isError = true)
        val msgId = obj.optString("messageId", "")
        return ToolExecutionResult("✅ Image sent to $to" + (if (msgId.isNotBlank()) "\nID: $msgId" else ""))
    }

    private fun sendDocument(base: String, to: String, docUrl: String, filename: String?, caption: String?): ToolExecutionResult {
        val body = JSONObject().apply {
            put("to", to)
            put("documentUrl", docUrl)
            if (!filename.isNullOrBlank()) put("filename", filename)
            if (!caption.isNullOrBlank()) put("caption", caption)
        }
        val obj = postJson("$base/send-document", body) ?: return ToolExecutionResult("Failed to send document — bridge unreachable.", isError = true)
        return ToolExecutionResult("✅ Document sent to $to")
    }

    private fun sendLocation(base: String, to: String, lat: String, lon: String, name: String?): ToolExecutionResult {
        val body = JSONObject().apply {
            put("to", to)
            put("latitude", lat.toDoubleOrNull() ?: 0.0)
            put("longitude", lon.toDoubleOrNull() ?: 0.0)
            if (!name.isNullOrBlank()) put("name", name)
        }
        val obj = postJson("$base/send-location", body) ?: return ToolExecutionResult("Failed to send location — bridge unreachable.", isError = true)
        return ToolExecutionResult("✅ Location sent to $to\n$lat, $lon${if (!name.isNullOrBlank()) " ($name)" else ""}")
    }

    private fun sendContact(base: String, to: String, contactName: String, contactPhone: String): ToolExecutionResult {
        val body = JSONObject().apply {
            put("to", to)
            put("contactName", contactName)
            put("contactPhone", contactPhone)
        }
        val obj = postJson("$base/send-contact", body) ?: return ToolExecutionResult("Failed to send contact — bridge unreachable.", isError = true)
        return ToolExecutionResult("✅ Contact '$contactName' sent to $to")
    }

    private fun markRead(base: String, messageId: String): ToolExecutionResult {
        val body = JSONObject().apply { put("messageId", messageId) }
        postJson("$base/mark-read", body) ?: return ToolExecutionResult("Failed to mark as read — bridge unreachable.", isError = true)
        return ToolExecutionResult("✅ Message $messageId marked as read.")
    }

    private fun react(base: String, messageId: String, emoji: String): ToolExecutionResult {
        val body = JSONObject().apply {
            put("messageId", messageId)
            put("emoji", emoji)
        }
        postJson("$base/react", body) ?: return ToolExecutionResult("Failed to react — bridge unreachable.", isError = true)
        return ToolExecutionResult("✅ Reacted with $emoji to message $messageId")
    }

    private fun getMessages(base: String, since: String?, limit: Int): ToolExecutionResult {
        val url = "$base/messages?limit=$limit" + (if (!since.isNullOrBlank()) "&since=$since" else "")
        val obj = getJson(url) ?: return ToolExecutionResult("Failed to fetch messages — bridge unreachable.", isError = true)
        val msgs = obj.optJSONArray("messages") ?: JSONArray()
        if (msgs.length() == 0) return ToolExecutionResult("No messages found.")
        val sb = StringBuilder("📨 ${msgs.length()} messages:\n\n")
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            val from = m.optString("from", "unknown")
            val body = m.optString("body", "[media]")
            val ts = m.optString("timestamp", "")
            sb.appendLine("[$ts] $from: $body")
        }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private fun logout(base: String): ToolExecutionResult {
        val url = URL("$base/session")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        return try {
            conn.connect()
            ToolExecutionResult("✅ WhatsApp session logged out and reset.")
        } catch (e: Exception) {
            ToolExecutionResult("Failed to logout: ${e.message}", isError = true)
        } finally {
            conn.disconnect()
        }
    }

    // ── HTTP utilities ─────────────────────────────────────────────────────

    private fun getJson(urlStr: String): JSONObject? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.connect()
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(text)
        } catch (_: Exception) { null }
    }

    private fun postJson(urlStr: String, body: JSONObject): JSONObject? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.connect()
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(text)
        } catch (_: Exception) { null }
    }
}
