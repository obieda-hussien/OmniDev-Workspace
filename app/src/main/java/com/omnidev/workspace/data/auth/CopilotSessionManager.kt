package com.omnidev.workspace.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.omnidev.workspace.OmniDevApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * OpenClaw-style GitHub Copilot session manager.
 *
 * Mirrors the approach used by VS Code and opencode (github.com/anomalyco/opencode):
 *
 *  1. OAuth Device Flow with empty scope → short-lived OAuth token (`gho_…`).
 *  2. Exchange the OAuth token for a Copilot session token via
 *     `GET https://api.github.com/copilot_internal/v2/token`.
 *     The session token (`tid=…`) lasts ~30 min and is stored persistently in
 *     SharedPreferences so it survives app restarts — exactly like opencode does
 *     with its config file on disk.
 *  3. Use the session token as `Authorization: Bearer <tid>` against
 *     `https://api.githubcopilot.com/chat/completions`.
 *  4. Fetch the available models list from
 *     `GET https://api.githubcopilot.com/models` immediately after login and
 *     cache the result so the model selector can show every model available
 *     on the user's Copilot subscription (Individual / Business / Enterprise).
 *
 * The application context is obtained from [OmniDevApp.instance], so callers
 * do not need to pass a context.  All network I/O runs on [Dispatchers.IO].
 */
object CopilotSessionManager {

    private const val TAG = "CopilotSessionManager"

    // SharedPreferences file names (private to app)
    private const val PREFS_NAME = "copilot_session_v1"
    private const val SECURE_PREFS_NAME = "copilot_session_secure_v1"

    // Preference keys
    private const val KEY_SESSION_TOKEN  = "session_token"
    private const val KEY_TOKEN_EXPIRY   = "token_expiry_ms"   // epoch millis
    private const val KEY_MODEL_IDS      = "model_ids"         // comma-separated

    // Copilot API endpoints
    private const val TOKEN_EXCHANGE_URL = "https://api.github.com/copilot_internal/v2/token"
    private const val MODELS_URL         = "https://api.githubcopilot.com/models"

    // HTTP timeouts
    private const val EXCHANGE_TIMEOUT_MS = 10_000
    private const val MODELS_TIMEOUT_MS   = 15_000

    // 60-second buffer — refresh the token before it actually expires
    private const val EXPIRY_BUFFER_MS = 60_000L

    // Mutex to prevent concurrent token-exchange races
    private val tokenMutex = Mutex()

    // In-memory fast-path cache (avoids SharedPreferences read on every request)
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedTokenExpiry: Long = 0L

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns a valid Copilot session token for [oauthToken], exchanging or refreshing
     * as needed.  Thread-safe; concurrent callers wait for the single in-flight exchange.
     *
     * Priority:
     *  1. In-memory cache (fastest — zero I/O)
     *  2. Persisted SharedPreferences (survives app restarts)
     *  3. Network exchange via `copilot_internal/v2/token` (slow path)
     *
     * @param oauthToken  The raw GitHub OAuth token (`gho_…`) from Device Flow.
     * @return A valid Copilot session token (`tid=…`).
     * @throws IOException if the token exchange fails.
     */
    suspend fun getSessionToken(oauthToken: String): String {
        // Fast path: in-memory cache is valid.
        val inMem = cachedToken
        if (inMem != null && System.currentTimeMillis() < cachedTokenExpiry - EXPIRY_BUFFER_MS) {
            return inMem
        }
        // Medium path: persisted cache from a previous app launch.
        val prefs = prefs()
        val persisted = prefs.getString(KEY_SESSION_TOKEN, null)
        val persistedExpiry = prefs.getLong(KEY_TOKEN_EXPIRY, 0L)
        if (!persisted.isNullOrBlank() && System.currentTimeMillis() < persistedExpiry - EXPIRY_BUFFER_MS) {
            cachedToken = persisted
            cachedTokenExpiry = persistedExpiry
            return persisted
        }

        // Slow path: exchange under lock.
        return tokenMutex.withLock {
            // Re-check after acquiring lock.
            val inMemLocked = cachedToken
            if (inMemLocked != null && System.currentTimeMillis() < cachedTokenExpiry - EXPIRY_BUFFER_MS) {
                return@withLock inMemLocked
            }

            val (token, expiryMs) = withContext(Dispatchers.IO) {
                exchangeOAuthForSessionToken(oauthToken)
            }

            // Persist to SharedPreferences for future app launches.
            prefs.edit()
                .putString(KEY_SESSION_TOKEN, token)
                .putLong(KEY_TOKEN_EXPIRY, expiryMs)
                .apply()

            // Update in-memory cache.
            cachedToken = token
            cachedTokenExpiry = expiryMs

            Log.d(TAG, "Session token refreshed. Expires at $expiryMs")
            token
        }
    }

    /**
     * Fetches all models available to the user's GitHub Copilot subscription from
     * `GET https://api.githubcopilot.com/models` and caches the result.
     *
     * Returns the full list of [CopilotModel] objects.  The model IDs are also
     * persisted in SharedPreferences so [getCachedModelIds] works synchronously
     * on subsequent launches without a network round-trip.
     *
     * Call this once after a successful Device Flow login and thereafter at the
     * user's explicit request (e.g., "Refresh models" button in Integrations).
     *
     * @param oauthToken The raw GitHub OAuth token from Device Flow.
     * @return List of Copilot models available on this subscription.
     */
    suspend fun fetchAndStoreAvailableModels(oauthToken: String): List<CopilotModel> {
        return withContext(Dispatchers.IO) {
            try {
                val sessionToken = getSessionToken(oauthToken)
                val models = fetchModelsFromApi(sessionToken)
                // Persist the IDs so they can be read synchronously on next launch.
                val ids = models.joinToString(",") { it.id }
                prefs().edit().putString(KEY_MODEL_IDS, ids).apply()
                Log.d(TAG, "Fetched ${models.size} Copilot models: ${models.take(5).map { it.id }}")
                models
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch Copilot models: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * Returns the cached list of Copilot model IDs from the last successful
     * [fetchAndStoreAvailableModels] call.  Returns an empty list if no models
     * have been fetched yet.
     */
    fun getCachedModelIds(): List<String> {
        val raw = prefs().getString(KEY_MODEL_IDS, null) ?: return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    /**
     * Clears all persisted Copilot session data (token, expiry, models).
     * Call on logout / token revocation.
     */
    fun clearSession() {
        prefs().edit().clear().apply()
        cachedToken = null
        cachedTokenExpiry = 0L
        Log.d(TAG, "Copilot session cleared")
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun prefs(): SharedPreferences = SecurePrefsHolder.getOrCreate(OmniDevApp.instance)

    private object SecurePrefsHolder {
        @Volatile
        private var cached: SharedPreferences? = null

        fun getOrCreate(context: Context): SharedPreferences {
            cached?.let { return it }
            return synchronized(this) {
                cached ?: run {
                    val securePrefs = try {
                        val masterKey = MasterKey.Builder(context)
                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                            .build()
                        EncryptedSharedPreferences.create(
                            context,
                            SECURE_PREFS_NAME,
                            masterKey,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to initialize encrypted prefs, using legacy prefs fallback", e)
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    }

                    migrateLegacyPrefsIfNeeded(context, securePrefs)
                    securePrefs.also { cached = it }
                }
            }
        }

        private fun migrateLegacyPrefsIfNeeded(context: Context, securePrefs: SharedPreferences) {
            val legacyPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (legacyPrefs.all.isEmpty()) return

            if (!securePrefs.contains(KEY_SESSION_TOKEN)) {
                legacyPrefs.getString(KEY_SESSION_TOKEN, null)?.let {
                    securePrefs.edit().putString(KEY_SESSION_TOKEN, it).apply()
                }
            }
            if (!securePrefs.contains(KEY_TOKEN_EXPIRY)) {
                val expiry = legacyPrefs.getLong(KEY_TOKEN_EXPIRY, 0L)
                if (expiry > 0L) securePrefs.edit().putLong(KEY_TOKEN_EXPIRY, expiry).apply()
            }
            if (!securePrefs.contains(KEY_MODEL_IDS)) {
                legacyPrefs.getString(KEY_MODEL_IDS, null)?.let {
                    securePrefs.edit().putString(KEY_MODEL_IDS, it).apply()
                }
            }

            legacyPrefs.edit().clear().apply()
        }
    }

    /**
     * Exchanges a GitHub OAuth token for a short-lived Copilot session token.
     *
     * Endpoint: GET https://api.github.com/copilot_internal/v2/token
     * Header:   Authorization: token <oauth_token>
     * Response: { "token": "tid=…", "expires_at": "2026-…", "refresh_in": 1800, … }
     *
     * This is the exact same approach used by VS Code, opencode, and aider.
     *
     * @return Pair of (session_token, expiry_epoch_ms)
     */
    private fun exchangeOAuthForSessionToken(oauthToken: String): Pair<String, Long> {
        val url = URL(TOKEN_EXCHANGE_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            connectTimeout = EXCHANGE_TIMEOUT_MS
            readTimeout = EXCHANGE_TIMEOUT_MS
            // GitHub REST API requires "token" scheme for OAuth tokens —
            // NOT "Bearer" (which is only used for the Copilot session token itself).
            setRequestProperty("Authorization", "token $oauthToken")
            setRequestProperty("Accept", "application/json")
            // Editor metadata — used by GitHub to route requests correctly.
            // "vscode-chat" is the widely accepted integration ID for third-party clients.
            setRequestProperty("Editor-Version", "vscode/1.0.0")
            setRequestProperty("Editor-Plugin-Version", "copilot-chat/0.1.0")
            setRequestProperty("Copilot-Integration-Id", "vscode-chat")
            setRequestProperty("User-Agent", "OmniDevWorkspace/1.0")
        }

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            val hint = when (responseCode) {
                401 -> "The GitHub OAuth token is invalid or expired. Re-authorize via Settings → Integrations → GitHub (Copilot)."
                403 -> "No active GitHub Copilot subscription on this account."
                404 -> "Your GitHub account may not have an active Copilot subscription, or the " +
                       "authorization needs to be refreshed. Go to Settings → Integrations → GitHub → " +
                       "'GitHub Copilot' and re-authorize, then ensure your account has Copilot access at " +
                       "github.com/settings/copilot."
                else -> "HTTP $responseCode. Details: $errorBody"
            }
            throw IOException("Copilot session token exchange failed ($responseCode). $hint")
        }

        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        conn.disconnect()

        val json = JSONObject(body)
        val token = json.optString("token").takeIf { it.isNotBlank() }
            ?: throw IOException("Copilot token exchange: missing 'token' field in response. Body: $body")

        // Compute expiry epoch.  Prefer `expires_at` (ISO-8601 string) if present;
        // fall back to `refresh_in` seconds from now.
        val expiryMs = parseExpiresAt(json.optString("expires_at"))
            ?: run {
                val refreshInSec = json.optLong("refresh_in", 1800L)
                System.currentTimeMillis() + refreshInSec * 1000L
            }

        return Pair(token, expiryMs)
    }

    /**
     * Fetches the list of models available to the Copilot subscription.
     *
     * Endpoint: GET https://api.githubcopilot.com/models
     * Header:   Authorization: Bearer <session_token>
     * Response: { "data": [ { "id": "gpt-4o", "display_name": "GPT-4o", … }, … ] }
     */
    private fun fetchModelsFromApi(sessionToken: String): List<CopilotModel> {
        val url = URL(MODELS_URL)
        val conn = url.openConnection() as HttpsURLConnection
        conn.apply {
            requestMethod = "GET"
            connectTimeout = MODELS_TIMEOUT_MS
            readTimeout = MODELS_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $sessionToken")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Copilot-Integration-Id", "vscode-chat")
            setRequestProperty("Editor-Version", "vscode/1.0.0")
            setRequestProperty("openai-organization", "github-copilot")
        }

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            throw IOException("Copilot models fetch failed ($responseCode). Body: $errorBody")
        }

        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        conn.disconnect()

        return parseModelsResponse(body)
    }

    /**
     * Parses the OpenAI-compatible `/models` response from the Copilot API.
     * Supports both `{ "data": [...] }` (list response) and a raw JSON array.
     */
    private fun parseModelsResponse(json: String): List<CopilotModel> {
        return try {
            val root = JSONObject(json)
            val dataArray: JSONArray = when {
                root.has("data") -> root.getJSONArray("data")
                else -> JSONArray(json)
            }
            (0 until dataArray.length()).mapNotNull { i ->
                val obj = dataArray.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val displayName = obj.optString("display_name")
                    .takeIf { it.isNotBlank() } ?: obj.optString("name")
                    .takeIf { it.isNotBlank() } ?: id
                val vendor = obj.optString("vendor").takeIf { it.isNotBlank() }
                val isPreview = obj.optBoolean("is_preview", false)
                    || id.contains("preview", ignoreCase = true)
                    || displayName.contains("preview", ignoreCase = true)
                val supportsVision = obj.optJSONObject("capabilities")
                    ?.optJSONObject("supports")?.optBoolean("vision") ?: false
                val supportsTools = obj.optJSONObject("capabilities")
                    ?.optJSONObject("supports")?.optBoolean("tool_calls") ?: true
                val contextWindow = obj.optJSONObject("capabilities")
                    ?.optJSONObject("limits")?.optInt("max_prompt_tokens") ?: 128_000
                CopilotModel(
                    id = id,
                    displayName = displayName,
                    vendor = vendor,
                    isPreview = isPreview,
                    supportsVision = supportsVision,
                    supportsFunctionCalling = supportsTools,
                    contextWindow = contextWindow
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse models response: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parses an ISO-8601 date string like "2026-03-09T22:30:00Z" to epoch millis.
     * Returns null if the string is blank or cannot be parsed.
     */
    private fun parseExpiresAt(expiresAt: String?): Long? {
        if (expiresAt.isNullOrBlank()) return null
        return try {
            // java.time is available on Android API 26+; fall back to simple heuristic on older.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                java.time.Instant.parse(expiresAt).toEpochMilli()
            } else {
                // Simple fallback: use refresh_in = 1800s from now (handled by caller)
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse expires_at '$expiresAt': ${e.message}")
            null
        }
    }

    // ── Data classes ─────────────────────────────────────────────────────────

    /**
     * A model entry returned by the GitHub Copilot `/models` endpoint.
     *
     * @property id                    Model ID as used in the `model` field of chat requests.
     * @property displayName           Human-readable name for the UI.
     * @property vendor                Model provider (e.g., "OpenAI", "Anthropic", "Google").
     * @property isPreview             True if the model is in preview / not GA yet.
     * @property supportsVision        True if the model accepts image inputs.
     * @property supportsFunctionCalling True if the model can call functions/tools.
     * @property contextWindow         Maximum number of prompt tokens.
     */
    data class CopilotModel(
        val id: String,
        val displayName: String,
        val vendor: String?,
        val isPreview: Boolean,
        val supportsVision: Boolean,
        val supportsFunctionCalling: Boolean,
        val contextWindow: Int
    )
}
