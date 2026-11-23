package com.omnidev.workspace.data.auth

import android.content.Context
import android.net.Uri
import com.omnidev.workspace.data.repository.SettingsRepository

/** Legacy GitHub OAuth is deliberately disabled until a state-bound PKCE or backend flow is available. */
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


    /**
     * Launches the GitHub OAuth authorization page in Chrome Custom Tabs.
     * The user will be redirected to [CALLBACK_URI] after authorization.
     */
    fun launchGitHubAuth(context: Context) {
        // Disabled until a verified state + PKCE or confidential backend flow
        // exists. Do not initiate a login that this client cannot finish safely.
        android.util.Log.w("OAuthManager", "Legacy GitHub OAuth is disabled; secure login is not configured")
    }

    /**
     * Extracts the OAuth code from a deep link callback URI.
     * Returns null if the URI is not a valid OAuth callback.
     */
    @Suppress("UNUSED_PARAMETER")
    fun extractCodeFromCallback(uri: Uri): String? {
        // Unsolicited or replayed callbacks cannot be trusted without a stored
        // one-time authorization state. Legacy callbacks are rejected.
        return null
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
    @Suppress("UNUSED_PARAMETER")
    suspend fun handleGitHubCallback(
        code: String,
        clientSecret: String,
        settingsRepository: SettingsRepository
    ): Result<String> {
        // Never transmit a client secret from a distributable APK or persist a
        // token obtained from an unbound callback. No network or storage side effects.
        return Result.failure(IllegalStateException(
            "GitHub OAuth is disabled until state-bound PKCE or secure backend exchange is implemented."
        ))
    }
}
