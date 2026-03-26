package com.omnidev.workspace.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * Manages OAuth 2.0 authentication flows for third-party platform integrations.
 *
 * GitHub OAuth 2.0 flow:
 * 1. `launchGitHubAuth(context)` opens Chrome Custom Tabs to GitHub authorize URL
 * 2. User approves → GitHub redirects to `omnidev://oauth/callback?code=...`
 * 3. `MainActivity` intercepts the deep link and calls `handleGitHubCallback(code)`
 * 4. `handleGitHubCallback` exchanges the code for an access token via POST to GitHub
 * 5. Token is saved in `SettingsRepository`
 *
 * IMPORTANT: In a production app, the client_secret MUST be kept on a secure backend server.
 * The token exchange should happen server-side. This implementation includes the exchange
 * client-side for development purposes only — in production, replace the direct POST with
 * a call to your own backend endpoint.
 */
object OAuthManager {

    /** Deep link URI scheme registered in AndroidManifest.xml */
    const val DEEP_LINK_SCHEME = "omnidev"
    const val DEEP_LINK_HOST = "oauth"
    const val CALLBACK_PATH = "/callback"
    const val CALLBACK_URI = "$DEEP_LINK_SCHEME://$DEEP_LINK_HOST$CALLBACK_PATH"

    /**
     * Replace with your actual GitHub OAuth App credentials.
     * In production: store client_id in BuildConfig, never commit client_secret to source.
     */
    private const val GITHUB_CLIENT_ID = "YOUR_GITHUB_CLIENT_ID"
    private const val GITHUB_SCOPES = "repo,user,gist"

    private const val GITHUB_AUTH_URL = "https://github.com/login/oauth/authorize"
    private const val GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token"

    /**
     * Launches the GitHub OAuth authorization page in Chrome Custom Tabs.
     * The user will be redirected to [CALLBACK_URI] after authorization.
     */
    fun launchGitHubAuth(context: Context) {
        val authUrl = Uri.parse(GITHUB_AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", GITHUB_CLIENT_ID)
            .appendQueryParameter("scope", GITHUB_SCOPES)
            .appendQueryParameter("redirect_uri", CALLBACK_URI)
            .build()
            .toString()

        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.launchUrl(context, Uri.parse(authUrl))
        } catch (e: Exception) {
            // Fallback to plain browser if Chrome Custom Tabs not available
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(browserIntent)
        }
    }

    /**
     * Extracts the OAuth code from a deep link callback URI.
     * Returns null if the URI is not a valid OAuth callback.
     */
    fun extractCodeFromCallback(uri: Uri): String? {
        return if (uri.scheme == DEEP_LINK_SCHEME &&
            uri.host == DEEP_LINK_HOST &&
            uri.path == CALLBACK_PATH
        ) {
            uri.getQueryParameter("code")
        } else null
    }

    /**
     * Exchanges an OAuth authorization [code] for a GitHub access token.
     * Saves the token to [settingsRepository] on success.
     *
     * NOTE: In production, this POST should be made to YOUR backend server,
     * which holds the client_secret securely. Never expose client_secret in the APK.
     *
     * @return Result wrapping the access token string, or an exception on failure.
     */
    suspend fun handleGitHubCallback(
        code: String,
        clientSecret: String,
        settingsRepository: SettingsRepository
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val postBody = buildString {
                append("client_id=").append(URLEncoder.encode(GITHUB_CLIENT_ID, "UTF-8"))
                append("&client_secret=").append(URLEncoder.encode(clientSecret, "UTF-8"))
                append("&code=").append(URLEncoder.encode(code, "UTF-8"))
                append("&redirect_uri=").append(URLEncoder.encode(CALLBACK_URI, "UTF-8"))
            }
            val url = URL(GITHUB_TOKEN_URL)
            val connection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            connection.outputStream.write(postBody.toByteArray())
            connection.connect()

            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val token = json.optString("access_token")
            if (token.isNotBlank()) {
                settingsRepository.setGitHubOAuthToken(token)
                // Also update legacy PAT field for backward compatibility with GitHubManagerTool
                settingsRepository.setGitHubPat(token)
                Result.success(token)
            } else {
                val error = json.optString("error_description", "Unknown error")
                Result.failure(Exception("GitHub OAuth failed: $error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
