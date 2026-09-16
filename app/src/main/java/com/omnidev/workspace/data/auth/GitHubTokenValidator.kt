package com.omnidev.workspace.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Validates a user-supplied GitHub access token without persisting or logging it.
 *
 * Both fine-grained and classic personal access tokens are supported. We deliberately
 * do not validate token prefixes/lengths because GitHub token formats can evolve; the
 * authoritative check is an authenticated request to GET /user.
 */
object GitHubTokenValidator {
    private const val USER_URL = "https://api.github.com/user"
    private const val API_VERSION = "2022-11-28"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    data class VerifiedToken(
        val login: String,
        val userId: Long,
        val scopes: String,
        val tokenExpiration: String?
    )

    suspend fun validate(token: String): Result<VerifiedToken> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = token.trim()
            require(trimmed.isNotBlank()) { "GitHub token is empty." }

            val request = Request.Builder()
                .url(USER_URL)
                .header("Authorization", "Bearer $trimmed")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", "OmniDev-Workspace")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val apiMessage = runCatching {
                        JSONObject(body).optString("message")
                    }.getOrNull().orEmpty()
                    val hint = when (response.code) {
                        401 -> "The token is invalid, expired, or revoked."
                        403 -> "GitHub rejected the token or the account is temporarily restricted/rate-limited."
                        else -> "GitHub returned HTTP ${response.code}."
                    }
                    throw IOException(
                        buildString {
                            append(hint)
                            if (apiMessage.isNotBlank()) append(" ").append(apiMessage)
                        }
                    )
                }

                val json = JSONObject(body)
                val login = json.optString("login").trim()
                require(login.isNotBlank()) { "GitHub returned no account login for this token." }

                VerifiedToken(
                    login = login,
                    userId = json.optLong("id", 0L),
                    scopes = response.header("X-OAuth-Scopes").orEmpty().trim(),
                    tokenExpiration = response.header("GitHub-Authentication-Token-Expiration")
                )
            }
        }
    }
}
