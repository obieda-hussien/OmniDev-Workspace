package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI-callable WhatsApp Business Cloud API tool.
 *
 * Uses Meta's official WhatsApp Business Cloud API (v20.0) to send and manage messages.
 *
 * Actions:
 *   send_message, send_image, send_document, send_location, send_contact,
 *   send_template, send_reaction, mark_read, get_profile
 *
 * Requires:
 *   - WhatsApp Phone Number ID (WHATSAPP_PHONE_NUMBER_ID in Settings → Integrations)
 *   - WhatsApp Access Token  (WHATSAPP_ACCESS_TOKEN in Settings → Integrations)
 *
 * Setup: Meta Business Suite → WhatsApp → Get Started → create app with WhatsApp Business API.
 */
object WhatsAppTool {

    private const val API_VERSION = "v20.0"
    private const val BASE = "https://graph.facebook.com/$API_VERSION"

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "whatsapp",
            description = "WhatsApp Business Cloud API integration. Actions: send_message, " +
                "send_image, send_document, send_location, send_contact, send_template, " +
                "send_reaction, mark_read, get_profile. " +
                "Requires WhatsApp Phone Number ID and Access Token in Settings → Integrations.",
            parameters = listOf(
                ToolParameter(
                    "action", "string",
                    "One of: send_message, send_image, send_document, send_location, " +
                        "send_contact, send_template, send_reaction, mark_read, get_profile.",
                    required = true
                ),
                ToolParameter("to", "string", "Recipient's WhatsApp number in international format e.g. 201012345678.", required = false),
                ToolParameter("message", "string", "Text body for send_message.", required = false),
                ToolParameter("message_id", "string", "Message ID for mark_read / send_reaction.", required = false),
                ToolParameter("emoji", "string", "Reaction emoji for send_reaction e.g. '❤️'.", required = false),
                ToolParameter("image_url", "string", "Public image URL for send_image.", required = false),
                ToolParameter("image_caption", "string", "Caption for send_image.", required = false),
                ToolParameter("document_url", "string", "Public document URL for send_document.", required = false),
                ToolParameter("document_name", "string", "Filename for send_document.", required = false),
                ToolParameter("document_caption", "string", "Caption for send_document.", required = false),
                ToolParameter("latitude", "string", "Latitude for send_location.", required = false),
                ToolParameter("longitude", "string", "Longitude for send_location.", required = false),
                ToolParameter("location_name", "string", "Location name for send_location.", required = false),
                ToolParameter("location_address", "string", "Location address for send_location.", required = false),
                ToolParameter("contact_name", "string", "Contact display name for send_contact.", required = false),
                ToolParameter("contact_phone", "string", "Contact phone for send_contact.", required = false),
                ToolParameter("template_name", "string", "Template name for send_template.", required = false),
                ToolParameter("template_lang", "string", "Template language code e.g. 'en_US' (default 'en').", required = false),
                ToolParameter("template_params", "string", "JSON array of template variable values e.g. '[\"John\",\"order123\"]'.", required = false)
            )
        )
    )

    suspend fun execute(phoneNumberId: String?, accessToken: String?, bridgeUrl: String?, args: Map<String, String>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val action = args["action"] ?: return@withContext ToolExecutionResult("action is required.", isError = true)

        // 1. Primary Path: Cloud API
        if (!phoneNumberId.isNullOrBlank() && !accessToken.isNullOrBlank()) {
            val res = executeCloud(phoneNumberId, accessToken, action, args)
            if (!res.isError) return@withContext res
            // Fall through if error
        }

        // 2. Fallback 1: WhatsAppBridgeService IPC
        if (com.omnidev.workspace.data.integration.WhatsAppBridgeService.isRunning) {
            val bridgeUrlFinal = bridgeUrl ?: "http://localhost:3000"
            val res = executeBridge(bridgeUrlFinal, action, args)
            if (!res.isError) return@withContext res
            // Fall through if error
        }

        // 3. Fallback 2: OmniAccessibilityService UI Automation
        return@withContext executeAccessibility(action, args)
    }

    private fun executeCloud(phoneNumberId: String, token: String, action: String, args: Map<String, String>): ToolExecutionResult {
        return when (action) {
            "send_message" -> sendMessage(phoneNumberId, token, args)
            "send_image" -> sendImage(phoneNumberId, token, args)
            "send_document" -> sendDocument(phoneNumberId, token, args)
            "send_location" -> sendLocation(phoneNumberId, token, args)
            "send_contact" -> sendContact(phoneNumberId, token, args)
            "send_template" -> sendTemplate(phoneNumberId, token, args)
            "react" -> sendReaction(phoneNumberId, token, args)
            "mark_read" -> markRead(phoneNumberId, token, args)
            "get_profile" -> getProfile(phoneNumberId, token)
            else -> ToolExecutionResult("Unknown action for Cloud API: '$action'.", isError = true)
        }
    }

    private suspend fun executeAccessibility(action: String, args: Map<String, String>): ToolExecutionResult {
        return when (action) {
            "send_message" -> {
                val to = args["to"] ?: return ToolExecutionResult("to is required.", isError = true)
                val msg = args["message"] ?: return ToolExecutionResult("message is required.", isError = true)
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage("com.whatsapp")
                    putExtra(android.content.Intent.EXTRA_TEXT, msg)
                    putExtra("jid", "$to@s.whatsapp.net")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }

                // Signal UI automation to click send via Accessibility
                val clickIntent = android.content.Intent("com.omnidev.workspace.ACCESSIBILITY_ACTION").apply {
                    putExtra("action", "click_whatsapp_send")
                    putExtra("target_package", "com.whatsapp")
                }

                val context = com.omnidev.workspace.OmniDevApp.instance.applicationContext
                try {
                    context.startActivity(intent)
                    kotlinx.coroutines.delay(1000)
                    context.sendBroadcast(clickIntent)
                    ToolExecutionResult("✅ UI Automation fallback triggered for sending message to $to")
                } catch(e: Exception) {
                    ToolExecutionResult("Fallback UI automation failed: ${e.message}", isError = true)
                }
            }
            else -> ToolExecutionResult("Action '$action' not supported in UI automation fallback.", isError = true)
        }
    }

    // ── Private action implementations ─────────────────────────────────────


    // ── Bridge UI Fallback (Partial via API if bridging) ──
    private suspend fun executeBridge(bridgeUrl: String, action: String, args: Map<String, String>): ToolExecutionResult {
        // Simple bridge stub for fallback
        return ToolExecutionResult("Fallback bridge currently only routes. Use UI automation if needed.", isError = true)
    }

    private fun sendMessage(phoneNumberId: String, token: String, args: Map<String, String>): ToolExecutionResult {
        val to   = args["to"]      ?: return ToolExecutionResult("to is required.", isError = true)
        val text = args["message"] ?: return ToolExecutionResult("message is required.", isError = true)
        val payload = JSONObject()
            .put("messaging_product", "whatsapp")
            .put("recipient_type", "individual")
            .put("to", to)
            .put("type", "text")
            .put("text", JSONObject().put("preview_url", false).put("body", text))
        return postMessage(phoneNumberId, token, payload)
    }

    private fun sendImage(phoneNumberId: String, token: String, args: Map<String, String>): ToolExecutionResult {
        val to       = args["to"]          ?: return ToolExecutionResult("to is required.", isError = true)
        val imageUrl = args["image_url"]   ?: return ToolExecutionResult("image_url is required.", isError = true)
        val image = JSONObject().put("link", imageUrl)
        args["image_caption"]?.let { image.put("caption", it) }
        val payload = JSONObject()
            .put("messaging_product", "whatsapp")
            .put("recipient_type", "individual")
            .put("to", to)
            .put("type", "image")
            .put("image", image)
        return postMessage(phoneNumberId, token, payload)
    }

    private fun sendDocument(phoneNumberId: String, token: String, args: Map<String, String>): ToolExecutionResult {
        val to  = args["to"]           ?: return ToolExecutionResult("to is required.", isError = true)
        val url = args["document_url"] ?: return ToolExecutionResult("document_url is required.", isError = true)
        val doc = JSONObject().put("link", url)
        args["document_name"]?.let    { doc.put("filename", it) }
        args["document_caption"]?.let { doc.put("caption", it) }
        val payload = JSONObject()
            .put("messaging_product", "whatsapp")
            .put("recipient_type", "individual")
            .put("to", to)
            .put("type", "document")
            .put("document", doc)
        return postMessage(phoneNumberId, token, payload)
    }

    private fun sendLocation(phoneNumberId: String, token: String, args: Map<String, String>): ToolExecutionResult {
        val to  = args["to"]        ?: return ToolExecutionResult("to is required.", isError = true)
        val lat = args["latitude"]  ?: return ToolExecutionResult("latitude is required.", isError = true)
        val lon = args["longitude"] ?: return ToolExecutionResult("longitude is required.", isError = true)
        val loc = JSONObject().put("latitude", lat).put("longitude", lon)
        args["location_name"]?.let    { loc.put("name", it) }
        args["location_address"]?.let { loc.put("address", it) }
        val payload = JSONObject()
            .put("messaging_product", "whatsapp")
            .put("recipient_type", "individual")
            .put("to", to)
            .put("type", "location")
            .put("location", loc)
        return postMessage(phoneNumberId, token, payload)
    }

    private fun sendContact(phoneNumberId: String, token: String, args: Map<String, String>): ToolExecutionResult {
        val to    = args["to"]            ?: return ToolExecutionResult("to is required.", isError = true)
        val name  = args["contact_name"]  ?: return ToolExecutionResult("contact_name is required.", isError = true)
        val phone = args["contact_phone"] ?: return ToolExecutionResult("contact_phone is required.", isError = true)
        val contact = JSONObject()
            .put("name", JSONObject().put("formatted_name", name).put("first_name", name.split(" ").first()))
            .put("phones", JSONArray().put(JSONObject().put("phone", phone).put("type", "CELL")))
        val payload = JSONObject()
            .put("messaging_product", "whatsapp")
            .put("to", to)
            .put("type", "contacts")
            .put("contacts", JSONArray().put(contact))
        return postMessage(phoneNumberId, token, payload)
    }

    private fun sendTemplate(phoneNumberId: String, token: String, args: Map<String, String>): ToolExecutionResult {
        val to       = args["to"]            ?: return ToolExecutionResult("to is required.", isError = true)
        val tmplName = args["template_name"] ?: return ToolExecutionResult("template_name is required.", isError = true)
        val lang     = args["template_lang"] ?: "en"
        val template = JSONObject()
            .put("name", tmplName)
            .put("language", JSONObject().put("code", lang))
        args["template_params"]?.let { paramsJson ->
            runCatching {
                val arr = JSONArray(paramsJson)
                val components = JSONArray().put(
                    JSONObject().put("type", "body").put("parameters",
                        JSONArray().let { params ->
                            for (i in 0 until arr.length())
                                params.put(JSONObject().put("type", "text").put("text", arr.getString(i)))
                            params
                        }
                    )
                )
                template.put("components", components)
            }
        }
        val payload = JSONObject()
            .put("messaging_product", "whatsapp")
            .put("to", to)
            .put("type", "template")
            .put("template", template)
        return postMessage(phoneNumberId, token, payload)
    }

    private fun sendReaction(phoneNumberId: String, token: String, args: Map<String, String>): ToolExecutionResult {
        val to    = args["to"]         ?: return ToolExecutionResult("to is required.", isError = true)
        val msgId = args["message_id"] ?: return ToolExecutionResult("message_id is required.", isError = true)
        val emoji = args["emoji"]      ?: return ToolExecutionResult("emoji is required.", isError = true)
        val payload = JSONObject()
            .put("messaging_product", "whatsapp")
            .put("recipient_type", "individual")
            .put("to", to)
            .put("type", "reaction")
            .put("reaction", JSONObject().put("message_id", msgId).put("emoji", emoji))
        return postMessage(phoneNumberId, token, payload)
    }

    private fun markRead(phoneNumberId: String, token: String, args: Map<String, String>): ToolExecutionResult {
        val msgId = args["message_id"] ?: return ToolExecutionResult("message_id is required.", isError = true)
        val payload = JSONObject()
            .put("messaging_product", "whatsapp")
            .put("status", "read")
            .put("message_id", msgId)
        val (code, body) = waPost("$BASE/$phoneNumberId/messages", token, payload)
        return if (code in 200..299) ToolExecutionResult("✅ Message marked as read.")
        else ToolExecutionResult("Error HTTP $code: $body", isError = true)
    }

    private fun getProfile(phoneNumberId: String, token: String): ToolExecutionResult {
        return try {
            val conn = (URL("$BASE/$phoneNumberId?fields=display_phone_number,verified_name,quality_rating").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (conn.responseCode in 200..299) {
                val obj = JSONObject(conn.inputStream.bufferedReader().readText())
                ToolExecutionResult(
                    "ID: ${obj.optString("id")}\n" +
                    "Phone: ${obj.optString("display_phone_number")}\n" +
                    "Name: ${obj.optString("verified_name")}\n" +
                    "Quality: ${obj.optString("quality_rating")}"
                )
            } else {
                ToolExecutionResult("Error HTTP ${conn.responseCode}", isError = true)
            }
        } catch (e: Exception) {
            ToolExecutionResult("Network error: ${e.message}", isError = true)
        }
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────

    private fun postMessage(phoneNumberId: String, token: String, payload: JSONObject): ToolExecutionResult {
        val (code, body) = waPost("$BASE/$phoneNumberId/messages", token, payload)
        if (code in 200..299) {
            return try {
                val obj = JSONObject(body ?: "{}")
                val msgId = obj.optJSONArray("messages")?.optJSONObject(0)?.optString("id", "") ?: ""
                ToolExecutionResult("✅ Message sent." + if (msgId.isNotBlank()) " ID: $msgId" else "")
            } catch (_: Exception) {
                ToolExecutionResult("✅ Message sent.")
            }
        }
        return try {
            val err = JSONObject(body ?: "{}").optJSONObject("error")
            ToolExecutionResult(
                "Error ${err?.optInt("code") ?: code}: ${err?.optString("message") ?: "Unknown error"}",
                isError = true
            )
        } catch (_: Exception) {
            ToolExecutionResult("Error HTTP $code", isError = true)
        }
    }

    private fun waPost(url: String, token: String, payload: JSONObject): Pair<Int, String?> = try {
        val body = payload.toString().toByteArray()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            outputStream.write(body)
        }
        val responseBody = runCatching {
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().readText()
            else conn.errorStream?.bufferedReader()?.readText()
        }.getOrNull()
        Pair(conn.responseCode, responseBody)
    } catch (e: Exception) {
        Pair(0, e.message)
    }
}
