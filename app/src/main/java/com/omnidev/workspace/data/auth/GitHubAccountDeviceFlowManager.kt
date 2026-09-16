package com.omnidev.workspace.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * GitHub OAuth Device Flow dedicated to agent account control.
 *
 * IMPORTANT: this flow never reuses or overwrites the Copilot token. Copilot uses
 * a scope-less OAuth token for its own token exchange; account automation needs
 * explicit GitHub OAuth scopes and therefore lives in [GitHubAgentAccessStore].
 */
object GitHubAccountDeviceFlowManager {
    private const val TAG = "GitHubAccountFlow"
    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
    const val VERIFICATION_URL = "https://github.com/login/device"
    private const val HTTP_TIMEOUT_MS = 30_000

    sealed class State {
        data class AwaitingUserCode(
            val userCode: String,
            val verificationUri: String,
            val expiresInSeconds: Int
        ) : State()

        object Polling : State()
        data class Success(val grantedScopes: String) : State()
        data class Error(val message: String) : State()
    }

    fun startAndPoll(
        context: Context,
        clientId: String,
        scopes: String
    ): Flow<State> = flow {
        val cleanClientId = clientId.trim()
        val cleanScopes = scopes.trim().replace(Regex("\\s+"), " ")
        if (cleanClientId.isBlank() || cleanClientId.contains("XXXX", ignoreCase = true)) {
            emit(State.Error("A Device Flow-enabled GitHub OAuth App Client ID is required for Agent GitHub Access."))
            return@flow
        }
        if (cleanScopes.isBlank()) {
            emit(State.Error("At least one GitHub OAuth scope is required."))
            return@flow
        }

        val codeResponse = withContext(Dispatchers.IO) {
            requestDeviceCode(cleanClientId, cleanScopes)
        }.getOrElse { e ->
            emit(State.Error("Failed to start GitHub account authorization: ${e.message}"))
            return@flow
        }

        val deviceCode = codeResponse.optString("device_code")
        val userCode = codeResponse.optString("user_code")
        val verificationUri = codeResponse.optString("verification_uri", VERIFICATION_URL)
        val expiresIn = codeResponse.optInt("expires_in", 900)
        var intervalSec = codeResponse.optInt("interval", 5).coerceAtLeast(5)

        if (deviceCode.isBlank() || userCode.isBlank()) {
            emit(State.Error("GitHub returned an invalid Device Flow response."))
            return@flow
        }

        emit(State.AwaitingUserCode(userCode, verificationUri, expiresIn))
        val deadline = System.currentTimeMillis() + expiresIn * 1000L

        while (System.currentTimeMillis() < deadline) {
            delay(intervalSec * 1000L)
            emit(State.Polling)

            val result = withContext(Dispatchers.IO) { pollForToken(cleanClientId, deviceCode) }
            result.onSuccess { json ->
                when (val error = json.optString("error")) {
                    "authorization_pending" -> Unit
                    "slow_down" -> intervalSec += 5
                    "expired_token" -> {
                        emit(State.Error("GitHub device code expired. Start authorization again."))
                        return@flow
                    }
                    "access_denied" -> {
                        emit(State.Error("GitHub account access was denied by the user."))
                        return@flow
                    }
                    "" -> {
                        val token = json.optString("access_token")
                        if (token.isBlank()) {
                            emit(State.Error("GitHub authorized the device but did not return an access token."))
                            return@flow
                        }
                        val granted = json.optString("scope").ifBlank { cleanScopes }
                        GitHubAgentAccessStore(context).saveAuthorization(
                            token = token,
                            requestedScopes = cleanScopes,
                            grantedScopes = granted
                        )
                        emit(State.Success(granted))
                        return@flow
                    }
                    else -> {
                        emit(State.Error("GitHub authorization error: $error"))
                        return@flow
                    }
                }
            }.onFailure { e ->
                if (e is IOException) {
                    Log.w(TAG, "Transient polling error; retrying: ${e.message}")
                } else {
                    emit(State.Error("GitHub polling failed: ${e.message}"))
                    return@flow
                }
            }
        }

        emit(State.Error("GitHub Device Flow timed out."))
    }

    fun openVerificationPage(context: Context, verificationUri: String = VERIFICATION_URL) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(verificationUri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun requestDeviceCode(clientId: String, scopes: String): Result<JSONObject> = runCatching {
        val postBody = "client_id=${encode(clientId)}&scope=${encode(scopes)}"
        postJson(DEVICE_CODE_URL, postBody)
    }

    private fun pollForToken(clientId: String, deviceCode: String): Result<JSONObject> = runCatching {
        val postBody = "client_id=${encode(clientId)}" +
            "&device_code=${encode(deviceCode)}" +
            "&grant_type=${encode("urn:ietf:params:oauth:grant-type:device_code")}" 
        postJson(TOKEN_URL, postBody)
    }

    private fun postJson(endpoint: String, postBody: String): JSONObject {
        val conn = URL(endpoint).openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true
            conn.outputStream.use { it.write(postBody.toByteArray(Charsets.UTF_8)) }
            val code = runCatching { conn.responseCode }.getOrDefault(-1)
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
            return JSONObject(stream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
