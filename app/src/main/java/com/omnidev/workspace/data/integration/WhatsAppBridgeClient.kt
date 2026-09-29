package com.omnidev.workspace.data.integration

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** REST contract with the local Termux bridge. Deliberately restricted to same-device loopback. */
class WhatsAppBridgeClient(private val baseUrl: String, private val apiKey: String) {
    init {
        val url = URL(baseUrl.trimEnd('/'))
        require(url.protocol == "http" && url.host in setOf("127.0.0.1", "localhost") &&
            (url.path.isEmpty() || url.path == "/") && url.userInfo == null && url.query == null && url.ref == null) {
            "Use http://127.0.0.1:3000 for the Termux bridge on this phone."
        }
        require(apiKey.matches(Regex("[a-f0-9]{64}"))) { "Enter the 64-character API key printed by the Termux installer." }
    }

    class BridgeException(val status: Int, message: String) : Exception(message)

    fun status(): JSONObject = request("GET", "/status")
    fun messages(after: Long): JSONObject = request("GET", "/messages?after=$after")
    fun pair(phone: String): JSONObject = request("POST", "/pair", JSONObject().put("phone", phone))
    fun send(to: String, message: String, requestId: String): JSONObject =
        request("POST", "/send", JSONObject().put("to", to).put("message", message).put("requestId", requestId))

    private fun request(method: String, path: String, payload: JSONObject? = null): JSONObject {
        val conn = URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 5_000
            conn.readTimeout = 15_000
            if (payload != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val response = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: "{}"
            val json = JSONObject(response)
            if (code !in 200..299) throw BridgeException(code, json.optString("error", "Bridge HTTP $code"))
            return json
        } finally {
            conn.disconnect()
        }
    }
}
