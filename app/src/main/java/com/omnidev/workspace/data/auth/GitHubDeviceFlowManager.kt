package com.omnidev.workspace.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Implements GitHub Device Flow (RFC 8628) — the same standard used by GitHub CLI and VS Code.
 *
 * Supports TWO sub-modes:
 *  - [SubMode.COPILOT]  — uses the public opencode Client ID (Ov23li8tweQw6odWQebz), which
 *    works with any GitHub Copilot subscription (Individual, Business, Enterprise).
 *    Zero registration required — this is the same Client ID used by VS Code and
 *    opencode (github.com/anomalyco/opencode). The resulting OAuth token is stored under
 *    [ModelProvider.GITHUB_COPILOT]. [CompletionService] then exchanges it for a short-lived
 *    Copilot session token via GET https://api.github.com/copilot_internal/v2/token before
 *    each call to api.githubcopilot.com.
 *
 *  - [SubMode.MODELS]   — requires the user's own OAuth App Client ID.
 *    Stores the token under [ModelProvider.GITHUB_MODELS] and targets
 *    models.inference.ai.azure.com.
 */
object GitHubDeviceFlowManager {

    // ── Sub-mode constants ────────────────────────────────────────────────────

    /** Which GitHub provider the user wants to connect. */
    enum class SubMode(val displayName: String, val serializedName: String) {
        /** GitHub Copilot — zero registration, uses shared public Client ID. */
        COPILOT("GitHub Copilot", "copilot"),
        /** GitHub Models marketplace — requires your own OAuth App Client ID. */
        MODELS("GitHub Models", "models");

        companion object {
            fun fromSerializedName(name: String): SubMode =
                entries.firstOrNull { it.serializedName == name } ?: MODELS
        }
    }

    /**
     * Public Client ID used by opencode / VS Code for GitHub Copilot Device Flow.
     * Source: https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/plugin/copilot.ts
     * This is NOT a secret — it is hardcoded in public open-source tooling.
     * No registration or OAuth App setup is required for Copilot mode.
     */
    const val COPILOT_CLIENT_ID = "Ov23li8tweQw6odWQebz"

    /**
     * Client ID for the GitHub Models (Azure inference) provider.
     * Replace with your Client ID from https://github.com/settings/developers
     * Must be a Device Flow-enabled OAuth App (NOT a GitHub App).
     *
     * NOTE: This is a placeholder — substitute your real Client ID before shipping.
     */
    const val MODELS_CLIENT_ID = "Ov23liXXXXXXXXXXXXXX" // TODO: replace with real OAuth App Client ID

    private const val DEVICE_CODE_URL  = "https://github.com/login/device/code"
    private const val TOKEN_URL        = "https://github.com/login/oauth/access_token"
    const val VERIFICATION_URL = "https://github.com/login/device"

    private const val TAG = "GitHubDeviceFlow"

    /** Timeout (ms) for each HTTP request to GitHub's OAuth endpoints. */
    private const val HTTP_TIMEOUT_MS = 30_000

    /** Scope for Copilot: only read:user is needed. */
    private const val COPILOT_SCOPE = "read:user"

    /** Scope for GitHub Models: repo + email + models access. */
    private const val MODELS_SCOPE = "repo read:user user:email"

    /**
     * Comprehensive default scope used by [RequestGitHubAuthenticationTool] when
     * no specific scopes are requested.  Covers repository access, Actions/workflow
     * triggers, Gist creation, and basic user-profile reads.
     */
    const val DEFAULT_COMPREHENSIVE_SCOPE = "repo workflow gist read:user user:email"

    // ── DeviceFlowState ───────────────────────────────────────────────────────

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

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Initiates GitHub Device Flow and polls until the user authorizes (or flow expires).
     *
     * @param subMode       Which sub-mode to connect ([SubMode.COPILOT] or [SubMode.MODELS]).
     * @param overrideScope Optional OAuth scope string.  When non-null, overrides the
     *                      sub-mode default.  Used by [RequestGitHubAuthenticationTool]
     *                      to request dynamic scopes at the LLM's direction.  When null
     *                      the appropriate sub-mode default is used
     *                      (COPILOT → [COPILOT_SCOPE], MODELS → [MODELS_SCOPE]).
     *
     * Emits:
     * 1. [DeviceFlowState.AwaitingUserCode] immediately with the code to display
     * 2. [DeviceFlowState.Polling] on every poll interval while waiting
     * 3. [DeviceFlowState.Success] once the user authorizes
     * 4. [DeviceFlowState.Error] on failure / expiry / denial
     *
     * On success the token is stored:
     * - Always: [SettingsRepository.setGitHubOAuthToken] + [SettingsRepository.setGitHubPat]
     *   (used by GitHubManagerTool for repo operations)
     * - Copilot mode: [ApiKeyRepository] under [ModelProvider.GITHUB_COPILOT]
     * - Models mode:  [ApiKeyRepository] under [ModelProvider.GITHUB_MODELS]
     */
    fun startDeviceFlowAndPoll(
        settingsRepository: SettingsRepository,
        apiKeyRepository: ApiKeyRepository,
        subMode: SubMode = SubMode.MODELS,
        overrideScope: String? = null
    ): Flow<DeviceFlowState> = flow {
        val clientId = if (subMode == SubMode.COPILOT) COPILOT_CLIENT_ID else MODELS_CLIENT_ID
        val scope    = overrideScope?.takeIf { it.isNotBlank() }
            ?: if (subMode == SubMode.COPILOT) COPILOT_SCOPE else MODELS_SCOPE

        // ── Step 1: Request device + user codes ──────────────────────────────
        val codeResponse = withContext(Dispatchers.IO) { requestDeviceCode(clientId, scope) }
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
                pollForToken(clientId, deviceCode)
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
                                // Persist sub-mode so the UI can show the correct connected state
                                settingsRepository.setGitHubSubMode(subMode.serializedName)
                                // Store for GitHub Repos integration (GitHubManagerTool)
                                settingsRepository.setGitHubOAuthToken(token)
                                settingsRepository.setGitHubPat(token)
                                // Store the raw OAuth token under the correct AI provider key.
                                // For COPILOT: CompletionService exchanges this token for a
                                // short-lived Copilot session token on each API call
                                // (GET https://api.github.com/copilot_internal/v2/token).
                                // For MODELS: the OAuth token is used directly as Bearer.
                                val provider = if (subMode == SubMode.COPILOT)
                                    ModelProvider.GITHUB_COPILOT
                                else
                                    ModelProvider.GITHUB_MODELS
                                apiKeyRepository.setApiKey(provider, token)
                            }
                            emit(DeviceFlowState.Success(token))
                            return@flow
                        }
                    }
                }
            }.onFailure { e ->
                // Network timeouts (IOException, including SocketTimeoutException) during
                // polling are transient — log and wait for the next interval instead of
                // aborting the entire flow.
                if (e is IOException) {
                    Log.w(TAG, "Polling network error (will retry in ${currentInterval}s): ${e.message}")
                } else {
                    emit(DeviceFlowState.Error("Polling error: ${e.message}"))
                    return@flow
                }
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

    private fun requestDeviceCode(clientId: String, scope: String): Result<JSONObject> = runCatching {
        val postBody = "client_id=${clientId}&scope=${scope.replace(" ", "+")}"
        val url = URL(DEVICE_CODE_URL)
        val conn = url.openConnection() as HttpsURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = HTTP_TIMEOUT_MS
        conn.readTimeout    = HTTP_TIMEOUT_MS
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true
        conn.outputStream.write(postBody.toByteArray())
        conn.connect()
        val response = conn.inputStream.bufferedReader().readText()
        JSONObject(response)
    }

    private fun pollForToken(clientId: String, deviceCode: String): Result<JSONObject> = runCatching {
        val postBody = "client_id=${clientId}" +
            "&device_code=${deviceCode}" +
            "&grant_type=urn:ietf:params:oauth:grant-type:device_code"
        val url = URL(TOKEN_URL)
        val conn = url.openConnection() as HttpsURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = HTTP_TIMEOUT_MS
        conn.readTimeout    = HTTP_TIMEOUT_MS
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true
        conn.outputStream.write(postBody.toByteArray())
        conn.connect()
        // On some Android versions (< API 29), calling getResponseCode() on a POST request
        // that returns a 4xx status throws a fatal IOException instead of returning the
        // status code.  Wrap it in a try-catch so we can fall through to the error stream
        // and still parse the JSON body (which carries "error":"access_denied" etc.).
        val responseCode = try {
            conn.responseCode
        } catch (e: IOException) {
            // Log for debuggability — this indicates an older Android quirk.
            Log.w(TAG, "getResponseCode threw IOException (older Android); falling back to errorStream: ${e.message}")
            -1
        }
        // Prefer errorStream for non-2xx so the JSON error body is readable.
        // errorStream is null only when no error response was received (shouldn't happen
        // here since responseCode == -1 means connection already failed), so we fall
        // back to inputStream as a last resort to avoid a NullPointerException.
        val stream = when {
            responseCode in 200..299 -> conn.inputStream
            conn.errorStream != null -> conn.errorStream
            else -> conn.inputStream
        }
        val response = stream.bufferedReader().readText()
        JSONObject(response)
    }
}
