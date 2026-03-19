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
 * SendGrid v3 email tool — covers transactional mail, dynamic templates,
 * send statistics, and marketing contacts management.
 *
 * Actions:
 *   send_email, send_template, get_stats,
 *   create_contact, get_contacts, delete_contact, validate_email
 *
 * Requires a SendGrid API key in Settings → Integrations.
 */
object SendGridEmailTool {

    private const val BASE_URL = "https://api.sendgrid.com/v3"

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "sendgrid_email",
            description = "SendGrid v3 email integration. Actions: " +
                "send_email (to_email, from_email, subject, body — optional: to_name, from_name, is_html, cc, bcc, reply_to), " +
                "send_template (to_email, from_email, template_id — optional: to_name, from_name, dynamic_data JSON), " +
                "get_stats (start_date YYYY-MM-DD — optional: end_date, aggregated_by day/week/month), " +
                "create_contact (email — optional: first_name, last_name, phone), " +
                "get_contacts (optional: query, page_size), " +
                "delete_contact (email), " +
                "validate_email (email). " +
                "Requires SendGrid API Key in Settings → Integrations.",
            parameters = listOf(
                ToolParameter(
                    "action", "string",
                    "One of: send_email, send_template, get_stats, create_contact, get_contacts, delete_contact, validate_email.",
                    required = true
                ),
                ToolParameter("to_email",      "string", "Recipient email address.",                                              required = false),
                ToolParameter("from_email",    "string", "Sender email address (must be verified in SendGrid).",                 required = false),
                ToolParameter("subject",       "string", "Email subject line.",                                                  required = false),
                ToolParameter("body",          "string", "Email body — plain text or HTML depending on is_html.",                required = false),
                ToolParameter("to_name",       "string", "Recipient display name (optional).",                                   required = false),
                ToolParameter("from_name",     "string", "Sender display name (optional).",                                      required = false),
                ToolParameter("is_html",       "string", "true to send HTML body; false (default) for plain text.",              required = false),
                ToolParameter("cc",            "string", "CC email address (optional).",                                         required = false),
                ToolParameter("bcc",           "string", "BCC email address (optional).",                                        required = false),
                ToolParameter("reply_to",      "string", "Reply-to email address (optional).",                                   required = false),
                ToolParameter("template_id",   "string", "SendGrid dynamic template ID (d-xxxx) for send_template.",             required = false),
                ToolParameter("dynamic_data",  "string", "JSON string of template substitution data, e.g. {\"name\":\"Alice\"}.", required = false),
                ToolParameter("start_date",    "string", "Stats start date in YYYY-MM-DD format.",                               required = false),
                ToolParameter("end_date",      "string", "Stats end date in YYYY-MM-DD format (optional).",                      required = false),
                ToolParameter("aggregated_by", "string", "Stats aggregation: day, week, or month (default: day).",               required = false),
                ToolParameter("first_name",    "string", "Contact first name for create_contact.",                               required = false),
                ToolParameter("last_name",     "string", "Contact last name for create_contact.",                                required = false),
                ToolParameter("phone",         "string", "Contact phone number for create_contact.",                             required = false),
                ToolParameter("email",         "string", "Email address for contact operations or validate_email.",              required = false),
                ToolParameter("query",         "string", "Search query string for get_contacts (e.g. email LIKE '%@example.com').", required = false),
                ToolParameter("page_size",     "string", "Max contacts to return for get_contacts (default 25).",                required = false)
            )
        )
    )

    suspend fun execute(
        apiKey: String?,
        args: Map<String, String>
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (apiKey.isNullOrBlank()) {
            return@withContext ToolExecutionResult(
                "SendGrid API Key is not configured. Go to Settings → Integrations to set it up.",
                isError = true
            )
        }

        when (val action = args["action"]?.lowercase()?.trim() ?: "") {
            "send_email"      -> sendEmail(apiKey, args)
            "send_template"   -> sendTemplate(apiKey, args)
            "get_stats"       -> getStats(apiKey, args)
            "create_contact"  -> createContact(apiKey, args)
            "get_contacts"    -> getContacts(apiKey, args)
            "delete_contact"  -> deleteContact(apiKey, args)
            "validate_email"  -> validateEmail(apiKey, args)
            else -> ToolExecutionResult(
                "Unknown action '$action'. Valid: send_email, send_template, get_stats, " +
                "create_contact, get_contacts, delete_contact, validate_email.",
                isError = true
            )
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────

    private suspend fun sendEmail(apiKey: String, args: Map<String, String>): ToolExecutionResult {
        val toEmail   = args["to_email"]   ?: return err("Missing: to_email")
        val fromEmail = args["from_email"] ?: return err("Missing: from_email")
        val subject   = args["subject"]    ?: return err("Missing: subject")
        val body      = args["body"]       ?: return err("Missing: body")

        val isHtml    = args["is_html"]?.lowercase() == "true"
        val toName    = args["to_name"]
        val fromName  = args["from_name"]
        val cc        = args["cc"]
        val bcc       = args["bcc"]
        val replyTo   = args["reply_to"]

        val toObj = JSONObject().apply {
            put("email", toEmail)
            if (!toName.isNullOrBlank()) put("name", toName)
        }
        val personalization = JSONObject().apply {
            put("to", JSONArray().apply { put(toObj) })
            if (!cc.isNullOrBlank()) put("cc", JSONArray().apply {
                put(JSONObject().put("email", cc))
            })
            if (!bcc.isNullOrBlank()) put("bcc", JSONArray().apply {
                put(JSONObject().put("email", bcc))
            })
        }
        val fromObj = JSONObject().apply {
            put("email", fromEmail)
            if (!fromName.isNullOrBlank()) put("name", fromName)
        }
        val contentType = if (isHtml) "text/html" else "text/plain"
        val payload = JSONObject().apply {
            put("personalizations", JSONArray().apply { put(personalization) })
            put("from", fromObj)
            put("subject", subject)
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", contentType)
                    put("value", body)
                })
            })
            if (!replyTo.isNullOrBlank()) put("reply_to", JSONObject().put("email", replyTo))
        }

        return postNoContent(apiKey, "$BASE_URL/mail/send", payload) {
            val type = if (isHtml) "HTML" else "plain text"
            "✅ Email sent ($type) to $toEmail — subject: \"$subject\""
        }
    }

    private suspend fun sendTemplate(apiKey: String, args: Map<String, String>): ToolExecutionResult {
        val toEmail    = args["to_email"]    ?: return err("Missing: to_email")
        val fromEmail  = args["from_email"]  ?: return err("Missing: from_email")
        val templateId = args["template_id"] ?: return err("Missing: template_id")

        val toName    = args["to_name"]
        val fromName  = args["from_name"]
        val dynData   = args["dynamic_data"]

        val dynDataJson: JSONObject? = if (!dynData.isNullOrBlank()) {
            try {
                JSONObject(dynData)
            } catch (_: Exception) {
                return err(
                    "dynamic_data is not valid JSON: $dynData. " +
                    "Provide a JSON object, e.g. {\"name\":\"Alice\"}."
                )
            }
        } else null

        val toObj = JSONObject().apply {
            put("email", toEmail)
            if (!toName.isNullOrBlank()) put("name", toName)
        }
        val personalization = JSONObject().apply {
            put("to", JSONArray().apply { put(toObj) })
            if (dynDataJson != null) put("dynamic_template_data", dynDataJson)
        }
        val fromObj = JSONObject().apply {
            put("email", fromEmail)
            if (!fromName.isNullOrBlank()) put("name", fromName)
        }
        val payload = JSONObject().apply {
            put("personalizations", JSONArray().apply { put(personalization) })
            put("from", fromObj)
            put("template_id", templateId)
        }

        return postNoContent(apiKey, "$BASE_URL/mail/send", payload) {
            "✅ Template email sent to $toEmail using template $templateId"
        }
    }

    private suspend fun getStats(apiKey: String, args: Map<String, String>): ToolExecutionResult {
        val startDate    = args["start_date"]    ?: return err("Missing: start_date")
        val endDate      = args["end_date"]
        val aggregatedBy = args["aggregated_by"] ?: "day"

        val params = buildString {
            append("start_date=${encode(startDate)}")
            if (!endDate.isNullOrBlank()) append("&end_date=${encode(endDate)}")
            append("&aggregated_by=${encode(aggregatedBy)}")
        }

        return get(apiKey, "$BASE_URL/stats?$params") { body ->
            val stats = try { JSONArray(body) } catch (_: Exception) {
                return@get "Stats response: $body"
            }
            if (stats.length() == 0) return@get "No stats found for the given date range."

            buildString {
                append("📊 SendGrid stats ($aggregatedBy) from $startDate")
                if (!endDate.isNullOrBlank()) append(" to $endDate")
                append(":\n\n")
                for (i in 0 until stats.length()) {
                    val entry  = stats.getJSONObject(i)
                    val date   = entry.optString("date", "?")
                    val metric = entry.optJSONArray("stats")?.optJSONObject(0)?.optJSONObject("metrics")
                    if (metric != null) {
                        val requests  = metric.optInt("requests", 0)
                        val delivered = metric.optInt("delivered", 0)
                        val opens     = metric.optInt("opens", 0)
                        val clicks    = metric.optInt("clicks", 0)
                        val bounces   = metric.optInt("bounces", 0)
                        val spam      = metric.optInt("spam_reports", 0)
                        append("📅 $date — requests=$requests, delivered=$delivered, " +
                               "opens=$opens, clicks=$clicks, bounces=$bounces, spam=$spam\n")
                    } else {
                        append("📅 $date — (no metrics)\n")
                    }
                }
            }
        }
    }

    private suspend fun createContact(apiKey: String, args: Map<String, String>): ToolExecutionResult {
        val email     = args["email"]      ?: return err("Missing: email")
        val firstName = args["first_name"]
        val lastName  = args["last_name"]
        val phone     = args["phone"]

        val contact = JSONObject().apply {
            put("email", email)
            if (!firstName.isNullOrBlank()) put("first_name", firstName)
            if (!lastName.isNullOrBlank())  put("last_name", lastName)
            if (!phone.isNullOrBlank())     put("phone_number", phone)
        }
        val payload = JSONObject().apply {
            put("contacts", JSONArray().apply { put(contact) })
        }

        return put(apiKey, "$BASE_URL/marketing/contacts", payload) { json ->
            val jobId = json.optString("job_id", "")
            buildString {
                append("✅ Contact upserted: $email")
                if (jobId.isNotBlank()) append(" (job_id=$jobId)")
            }
        }
    }

    private suspend fun getContacts(apiKey: String, args: Map<String, String>): ToolExecutionResult {
        val query    = args["query"]
        val pageSize = args["page_size"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 25

        val payload = JSONObject().apply {
            if (!query.isNullOrBlank()) put("query", query)
            put("page_size", pageSize)
        }

        return post(apiKey, "$BASE_URL/marketing/contacts/search", payload) { json ->
            val contacts = json.optJSONArray("result") ?: JSONArray()
            val total    = json.optInt("contact_count", contacts.length())
            if (contacts.length() == 0) return@post "No contacts found."

            buildString {
                append("👥 $total contact(s) (showing ${contacts.length()}):\n\n")
                for (i in 0 until contacts.length()) {
                    val c         = contacts.getJSONObject(i)
                    val cEmail    = c.optString("email", "?")
                    val firstName = c.optString("first_name", "")
                    val lastName  = c.optString("last_name", "")
                    val name      = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
                    val display   = if (name.isNotBlank()) "$cEmail ($name)" else cEmail
                    append("• $display\n")
                }
            }
        }
    }

    private suspend fun deleteContact(apiKey: String, args: Map<String, String>): ToolExecutionResult {
        val email = args["email"] ?: return err("Missing: email")

        // Step 1: search for the contact by email to get its ID
        val searchUrl = "$BASE_URL/marketing/contacts/search?query=email%3D%22${encode(email)}%22"
        val searchResult = getRaw(apiKey, searchUrl)
        if (searchResult.isError) return searchResult

        val contactId = try {
            val json = JSONObject(searchResult.output)
            json.optJSONArray("result")?.optJSONObject(0)?.optString("id", "")
        } catch (_: Exception) { "" }

        if (contactId.isNullOrBlank()) {
            return ToolExecutionResult("Contact not found for email: $email", isError = true)
        }

        // Step 2: delete by ID
        return delete(apiKey, "$BASE_URL/marketing/contacts?ids=${encode(contactId)}") { _ ->
            "✅ Contact deleted: $email (id=$contactId)"
        }
    }

    private suspend fun validateEmail(apiKey: String, args: Map<String, String>): ToolExecutionResult {
        val email = args["email"] ?: return err("Missing: email")

        return get(apiKey, "$BASE_URL/validations/email?email=${encode(email)}") { body ->
            val json = try { JSONObject(body) } catch (_: Exception) {
                return@get "Validation response: $body"
            }
            val result     = json.optJSONObject("result")
            val verdict    = result?.optString("verdict", "unknown") ?: "unknown"
            val score      = result?.optDouble("score", 0.0) ?: 0.0
            val suggestion = result?.optString("suggestion", "") ?: ""
            buildString {
                append("📧 Email validation for $email:\n")
                append("  verdict: $verdict\n")
                append("  score: ${"%.2f".format(score)}\n")
                if (suggestion.isNotBlank()) append("  suggestion: $suggestion")
            }
        }
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────

    private fun err(msg: String) = ToolExecutionResult(msg, isError = true)

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    /** POST with JSON body; expects a non-empty JSON response body on success. */
    private suspend fun post(
        apiKey: String,
        url: String,
        body: JSONObject,
        onSuccess: (JSONObject) -> String
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val conn = openConn(url, "POST", apiKey)
            writeBody(conn, body)

            val code     = conn.responseCode
            val respBody = readBody(conn, code)
            conn.disconnect()

            if (code !in 200..299) {
                return@withContext ToolExecutionResult(
                    "SendGrid API error ($code): $respBody", isError = true)
            }
            ToolExecutionResult(onSuccess(JSONObject(respBody)))
        } catch (e: IOException) {
            ToolExecutionResult("SendGrid request failed: ${e.message}", isError = true)
        }
    }

    /**
     * POST with JSON body where a 2xx response may have no body (e.g. 202 Accepted
     * for mail/send). [onSuccess] receives no JSON object.
     */
    private suspend fun postNoContent(
        apiKey: String,
        url: String,
        body: JSONObject,
        onSuccess: () -> String
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val conn = openConn(url, "POST", apiKey)
            writeBody(conn, body)

            val code     = conn.responseCode
            val respBody = readBody(conn, code)
            conn.disconnect()

            if (code !in 200..299) {
                return@withContext ToolExecutionResult(
                    "SendGrid API error ($code): $respBody", isError = true)
            }
            ToolExecutionResult(onSuccess())
        } catch (e: IOException) {
            ToolExecutionResult("SendGrid request failed: ${e.message}", isError = true)
        }
    }

    /** GET; [onSuccess] receives the raw response body string. */
    private suspend fun get(
        apiKey: String,
        url: String,
        onSuccess: (String) -> String
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val conn = openConn(url, "GET", apiKey)
            val code     = conn.responseCode
            val respBody = readBody(conn, code)
            conn.disconnect()

            if (code !in 200..299) {
                return@withContext ToolExecutionResult(
                    "SendGrid API error ($code): $respBody", isError = true)
            }
            ToolExecutionResult(onSuccess(respBody))
        } catch (e: IOException) {
            ToolExecutionResult("SendGrid request failed: ${e.message}", isError = true)
        }
    }

    /** GET that returns a [ToolExecutionResult] directly (used for intermediate lookups). */
    private suspend fun getRaw(
        apiKey: String,
        url: String
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val conn = openConn(url, "GET", apiKey)
            val code     = conn.responseCode
            val respBody = readBody(conn, code)
            conn.disconnect()

            if (code !in 200..299) {
                ToolExecutionResult("SendGrid API error ($code): $respBody", isError = true)
            } else {
                ToolExecutionResult(respBody)
            }
        } catch (e: IOException) {
            ToolExecutionResult("SendGrid request failed: ${e.message}", isError = true)
        }
    }

    /** PUT with JSON body; expects a JSON response body on success. */
    private suspend fun put(
        apiKey: String,
        url: String,
        body: JSONObject,
        onSuccess: (JSONObject) -> String
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val conn = openConn(url, "PUT", apiKey)
            writeBody(conn, body)

            val code     = conn.responseCode
            val respBody = readBody(conn, code)
            conn.disconnect()

            if (code !in 200..299) {
                return@withContext ToolExecutionResult(
                    "SendGrid API error ($code): $respBody", isError = true)
            }
            val json = try { JSONObject(respBody) } catch (_: Exception) { JSONObject() }
            ToolExecutionResult(onSuccess(json))
        } catch (e: IOException) {
            ToolExecutionResult("SendGrid request failed: ${e.message}", isError = true)
        }
    }

    /** DELETE; [onSuccess] receives the raw response body string. */
    private suspend fun delete(
        apiKey: String,
        url: String,
        onSuccess: (String) -> String
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val conn = openConn(url, "DELETE", apiKey)
            val code     = conn.responseCode
            val respBody = readBody(conn, code)
            conn.disconnect()

            if (code !in 200..299) {
                return@withContext ToolExecutionResult(
                    "SendGrid API error ($code): $respBody", isError = true)
            }
            ToolExecutionResult(onSuccess(respBody))
        } catch (e: IOException) {
            ToolExecutionResult("SendGrid request failed: ${e.message}", isError = true)
        }
    }

    private fun openConn(url: String, method: String, apiKey: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.connectTimeout = 15_000
        conn.readTimeout    = 15_000
        if (method in setOf("POST", "PUT", "PATCH")) conn.doOutput = true
        return conn
    }

    private fun writeBody(conn: HttpURLConnection, body: JSONObject) {
        conn.outputStream.use { out: OutputStream ->
            out.write(body.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String =
        if (code in 200..299) conn.inputStream?.bufferedReader()?.readText() ?: ""
        else conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
}
