package com.omnidev.workspace.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Implements GitHub Device Flow (RFC 8628) — the same standard used by GitHub CLI and VS Code.
 *
 * No client_secret is required. The user sees an 8-character code (XXXX-XXXX) and enters
 * it at https://github.com/login/device in a browser while the app polls for authorization.
 *
 * SETUP INSTRUCTIONS — Run once per deployment:
 * 1. Go to: https://github.com/settings/developers → "OAuth Apps" → "New OAuth App"
 * 2. Fill in:
 *    - Application name: "OmniDev Workspace"
 *    - Homepage URL: https://github.com/obieda-hussien/DevSwarm
 *    - Authorization callback URL: (leave blank — device flow doesn't need one)
 *    - ✅ Enable Device Flow checkbox
 * 3. Copy the generated Client ID
 * 4. Replace GITHUB_CLIENT_ID below with your Client ID
 * 5. Do NOT generate a Client Secret — device flow is a public client
 */
object GitHubDeviceFlowManager {

    /**
     * GitHub OAuth App Client ID.
     * Replace with your Client ID from https://github.com/settings/developers
     * Must be a Device Flow-enabled OAuth App (NOT a GitHub App).
     *
     * NOTE: This is a placeholder — substitute your real Client ID before shipping.
     * In CI/CD environments, inject via BuildConfig:
     *   buildConfigField("String", "GITHUB_CLIENT_ID", "\"${project.findProperty("githubClientId")}\"")
     */
    const val GITHUB_CLIENT_ID = "Ov23liXXXXXXXXXXXXXX" // TODO: replace with real OAuth App Client ID

    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val TOKEN_URL       = "https://github.com/login/oauth/access_token"
    private const val VERIFICATION_URL = "https://github.com/login/device"

    // Scopes needed for GitHub Repos integration AND GitHub AI Models API
    private const val SCOPES = "repo read:user user:email models:read"

    /** Possible states emitted by [startDeviceFlowAndPoll]. */
    sealed class DeviceFlowState {
        /** Waiting for the user to enter the code. Contains the code to display. */
        data class AwaitingUserCode(
            val userCode: String,
            val verificationUri: String,
            val expiresInSeconds: Int
        ) : DeviceFlowState()

        /** Still polling — user hasn't entered the code yet. */
        object Polling : DeviceFlowState()

        /** User successfully authorized — token stored. */
        data class Success(val token: String) : DeviceFlowState()

        /** An error occurred (expired, denied, network, etc.). */
        data class Error(val message: String) : DeviceFlowState()
    }

    /**
     * Initiates GitHub Device Flow and polls until the user authorizes (or flow expires).
     *
     * Emits:
     * 1. [DeviceFlowState.AwaitingUserCode] immediately with the code to display
     * 2. [DeviceFlowState.Polling] on every poll interval while waiting
     * 3. [DeviceFlowState.Success] once the user authorizes
     * 4. [DeviceFlowState.Error] on failure / expiry / denial
     *
     * On success, the token is saved to [SettingsRepository] (for GitHub Repos integration)
     * AND to [ApiKeyRepository] under [ModelProvider.GITHUB_MODELS] (for the AI Models API).
     */
    fun startDeviceFlowAndPoll(
        settingsRepository: SettingsRepository,
        apiKeyRepository: ApiKeyRepository
    ): Flow<DeviceFlowState> = flow {
        // ── Step 1: Request device + user codes ──────────────────────────────
        val codeResponse = withContext(Dispatchers.IO) { requestDeviceCode() }
            .getOrElse { e ->
                emit(DeviceFlowState.Error("Failed to start device flow: ${e.message}"))
                return@flow
            }

        val deviceCode   = codeResponse.optString("device_code")
        val userCode     = codeResponse.optString("user_code")
        val verificationUri = codeResponse.optString("verification_uri", VERIFICATION_URL)
        val expiresIn    = codeResponse.optInt("expires_in", 900)
        val intervalSec  = codeResponse.optInt("interval", 5).coerceAtLeast(5)

        if (deviceCode.isBlank() || userCode.isBlank()) {
            emit(DeviceFlowState.Error("Invalid response from GitHub: missing codes"))
            return@flow
        }

        // ── Step 2: Show the code to the user ────────────────────────────────
        emit(DeviceFlowState.AwaitingUserCode(userCode, verificationUri, expiresIn))

        // ── Step 3: Poll until success or expiry ─────────────────────────────
        val deadline = System.currentTimeMillis() + (expiresIn * 1000L)
        var currentInterval = intervalSec

        while (System.currentTimeMillis() < deadline) {
            delay(currentInterval * 1000L)
            emit(DeviceFlowState.Polling)

            val pollResult = withContext(Dispatchers.IO) {
                pollForToken(deviceCode)
            }

            pollResult.onSuccess { json ->
                val error = json.optString("error")
                when {
                    error == "authorization_pending" -> { /* keep polling */ }
                    error == "slow_down" -> {
                        currentInterval += 5
                    }
                    error == "expired_token" -> {
                        emit(DeviceFlowState.Error("Device code expired. Please try again."))
                        return@flow
                    }
                    error == "access_denied" -> {
                        emit(DeviceFlowState.Error("Authorization denied by user."))
                        return@flow
                    }
                    error.isNotBlank() -> {
                        emit(DeviceFlowState.Error("GitHub error: $error"))
                        return@flow
                    }
                    else -> {
                        val token = json.optString("access_token")
                        if (token.isNotBlank()) {
                            withContext(Dispatchers.IO) {
                                // Store token for GitHub Repos integration (GitHubManagerTool)
                                settingsRepository.setGitHubOAuthToken(token)
                                settingsRepository.setGitHubPat(token)
                                // Store token for GitHub AI Models provider
                                apiKeyRepository.setApiKey(ModelProvider.GITHUB_MODELS, token)
                            }
                            emit(DeviceFlowState.Success(token))
                            return@flow
                        }
                    }
                }
            }.onFailure { e ->
                emit(DeviceFlowState.Error("Polling error: ${e.message}"))
                return@flow
            }
        }

        emit(DeviceFlowState.Error("Device flow timed out. Please try again."))
    }

    /**
     * Opens the GitHub device verification page in the default browser.
     * Call this when the user taps "Open GitHub" from the Device Flow UI.
     */
    fun openVerificationPage(context: Context, verificationUri: String = VERIFICATION_URL) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(verificationUri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun requestDeviceCode(): Result<JSONObject> = runCatching {
        val postBody = "client_id=${GITHUB_CLIENT_ID}&scope=${SCOPES.replace(" ", "+")}"
        val url = URL(DEVICE_CODE_URL)
        val conn = url.openConnection() as HttpsURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true
        conn.outputStream.write(postBody.toByteArray())
        conn.connect()
        val response = conn.inputStream.bufferedReader().readText()
        JSONObject(response)
    }

    private fun pollForToken(deviceCode: String): Result<JSONObject> = runCatching {
        val postBody = "client_id=${GITHUB_CLIENT_ID}" +
            "&device_code=${deviceCode}" +
            "&grant_type=urn:ietf:params:oauth:grant-type:device_code"
        val url = URL(TOKEN_URL)
        val conn = url.openConnection() as HttpsURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true
        conn.outputStream.write(postBody.toByteArray())
        conn.connect()
        // Use error stream for 4xx responses that still carry JSON
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val response = (stream ?: conn.inputStream).bufferedReader().readText()
        JSONObject(response)
    }
}
