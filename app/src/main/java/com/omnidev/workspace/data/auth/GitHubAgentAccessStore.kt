package com.omnidev.workspace.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Local authorization policy for GitHub account control by the OmniDev agent.
 *
 * This is deliberately separate from GitHub Copilot/Models credentials. A user may
 * use GitHub as an AI provider without granting the agent any authority over their
 * repositories/account. Agent access is deny-by-default and can only be enabled from
 * Integrations & Linked Accounts.
 *
 * The account-control token and policy are encrypted at rest with Android
 * Keystore-backed EncryptedSharedPreferences. If secure storage cannot be created,
 * initialization fails closed rather than falling back to plaintext token storage.
 */
class GitHubAgentAccessStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = securePrefs(appContext)

    enum class AuthMethod(val serializedName: String, val displayName: String) {
        OAUTH_DEVICE_FLOW("oauth_device_flow", "Connect with GitHub"),
        PERSONAL_ACCESS_TOKEN("personal_access_token", "Personal Access Token");

        companion object {
            fun fromSerializedName(value: String?): AuthMethod =
                entries.firstOrNull { it.serializedName == value } ?: OAUTH_DEVICE_FLOW
        }
    }

    data class Policy(
        val enabled: Boolean,
        val writeEnabled: Boolean,
        val destructiveEnabled: Boolean,
        val organizationAdminEnabled: Boolean,
        val authMethod: AuthMethod,
        val accountLogin: String?,
        val oauthClientId: String,
        val token: String?,
        val requestedScopes: String,
        val grantedScopes: String
    ) {
        val connected: Boolean get() = !token.isNullOrBlank()
        val canRead: Boolean get() = enabled && connected
        val canWrite: Boolean get() = canRead && writeEnabled
        val canDelete: Boolean get() = canWrite && destructiveEnabled
    }

    data class PublicPolicy(
        val enabled: Boolean,
        val writeEnabled: Boolean,
        val destructiveEnabled: Boolean,
        val organizationAdminEnabled: Boolean,
        val authMethod: AuthMethod,
        val accountLogin: String?,
        val connected: Boolean,
        val oauthClientId: String,
        val requestedScopes: String,
        val grantedScopes: String
    )

    fun policy(): Policy = Policy(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        writeEnabled = prefs.getBoolean(KEY_WRITE, false),
        destructiveEnabled = prefs.getBoolean(KEY_DESTRUCTIVE, false),
        organizationAdminEnabled = prefs.getBoolean(KEY_ORG_ADMIN, false),
        authMethod = AuthMethod.fromSerializedName(prefs.getString(KEY_AUTH_METHOD, null)),
        accountLogin = prefs.getString(KEY_ACCOUNT_LOGIN, null),
        oauthClientId = prefs.getString(KEY_CLIENT_ID, "").orEmpty(),
        token = prefs.getString(KEY_TOKEN, null),
        requestedScopes = prefs.getString(KEY_REQUESTED_SCOPES, DEFAULT_BASE_SCOPES).orEmpty(),
        grantedScopes = prefs.getString(KEY_GRANTED_SCOPES, "").orEmpty()
    )

    /** Safe projection for UI/diagnostics — never exposes the account token. */
    fun publicPolicy(): PublicPolicy = policy().let { p ->
        PublicPolicy(
            enabled = p.enabled,
            writeEnabled = p.writeEnabled,
            destructiveEnabled = p.destructiveEnabled,
            organizationAdminEnabled = p.organizationAdminEnabled,
            authMethod = p.authMethod,
            accountLogin = p.accountLogin,
            connected = p.connected,
            oauthClientId = p.oauthClientId,
            requestedScopes = p.requestedScopes,
            grantedScopes = p.grantedScopes
        )
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setWriteEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_WRITE, enabled)
            .also { if (!enabled) it.putBoolean(KEY_DESTRUCTIVE, false) }
            .apply()
    }

    fun setDestructiveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DESTRUCTIVE, enabled).apply()
    }

    fun setOrganizationAdminEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ORG_ADMIN, enabled).apply()
    }

    fun setAuthMethod(method: AuthMethod) {
        prefs.edit().putString(KEY_AUTH_METHOD, method.serializedName).apply()
    }

    fun setOAuthClientId(clientId: String) {
        prefs.edit().putString(KEY_CLIENT_ID, clientId.trim()).apply()
    }

    fun saveAuthorization(
        token: String,
        requestedScopes: String,
        grantedScopes: String = requestedScopes,
        authMethod: AuthMethod = AuthMethod.OAUTH_DEVICE_FLOW,
        accountLogin: String? = null
    ) {
        require(token.isNotBlank()) { "GitHub account token is blank." }
        prefs.edit()
            .putString(KEY_TOKEN, token.trim())
            .putString(KEY_REQUESTED_SCOPES, requestedScopes.trim())
            .putString(KEY_GRANTED_SCOPES, grantedScopes.trim())
            .putString(KEY_AUTH_METHOD, authMethod.serializedName)
            .apply {
                if (accountLogin.isNullOrBlank()) remove(KEY_ACCOUNT_LOGIN)
                else putString(KEY_ACCOUNT_LOGIN, accountLogin.trim())
            }
            .apply()
    }

    fun savePersonalAccessToken(
        token: String,
        accountLogin: String,
        reportedScopes: String = ""
    ) {
        saveAuthorization(
            token = token,
            requestedScopes = "PAT permissions are managed on GitHub",
            grantedScopes = reportedScopes,
            authMethod = AuthMethod.PERSONAL_ACCESS_TOKEN,
            accountLogin = accountLogin
        )
    }

    fun clearAuthorization() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_GRANTED_SCOPES)
            .remove(KEY_ACCOUNT_LOGIN)
            .apply()
    }

    fun revokeAgentAccess() {
        prefs.edit()
            .putBoolean(KEY_ENABLED, false)
            .putBoolean(KEY_WRITE, false)
            .putBoolean(KEY_DESTRUCTIVE, false)
            .putBoolean(KEY_ORG_ADMIN, false)
            .remove(KEY_TOKEN)
            .remove(KEY_GRANTED_SCOPES)
            .remove(KEY_ACCOUNT_LOGIN)
            .apply()
    }

    fun scopesForCurrentPolicy(): String {
        val p = policy()
        return buildScopes(
            writeEnabled = p.writeEnabled,
            destructiveEnabled = p.destructiveEnabled,
            organizationAdminEnabled = p.organizationAdminEnabled
        )
    }

    companion object {
        private const val LEGACY_PREFS_NAME = "omnidev_github_agent_access"
        private const val SECURE_PREFS_NAME = "omnidev_github_agent_access_secure_v1"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_WRITE = "write_enabled"
        private const val KEY_DESTRUCTIVE = "destructive_enabled"
        private const val KEY_ORG_ADMIN = "organization_admin_enabled"
        private const val KEY_AUTH_METHOD = "auth_method"
        private const val KEY_ACCOUNT_LOGIN = "account_login"
        private const val KEY_CLIENT_ID = "oauth_client_id"
        private const val KEY_TOKEN = "oauth_token"
        private const val KEY_REQUESTED_SCOPES = "requested_scopes"
        private const val KEY_GRANTED_SCOPES = "granted_scopes"

        const val DEFAULT_BASE_SCOPES = "repo read:user user:email notifications read:org read:project read:packages"

        @Volatile
        private var cachedPrefs: SharedPreferences? = null

        private fun securePrefs(context: Context): SharedPreferences = synchronized(this) {
            cachedPrefs ?: run {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val encrypted = EncryptedSharedPreferences.create(
                    context,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                migrateLegacyPrefs(context, encrypted)
                encrypted.also { cachedPrefs = it }
            }
        }

        /**
         * Migrates any token/policy written by an earlier development build, then
         * clears the plaintext file only after the encrypted commit succeeds.
         */
        private fun migrateLegacyPrefs(context: Context, encrypted: SharedPreferences) {
            val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            if (legacy.all.isEmpty()) return

            val editor = encrypted.edit()
            if (!encrypted.contains(KEY_ENABLED) && legacy.contains(KEY_ENABLED)) {
                editor.putBoolean(KEY_ENABLED, legacy.getBoolean(KEY_ENABLED, false))
            }
            if (!encrypted.contains(KEY_WRITE) && legacy.contains(KEY_WRITE)) {
                editor.putBoolean(KEY_WRITE, legacy.getBoolean(KEY_WRITE, false))
            }
            if (!encrypted.contains(KEY_DESTRUCTIVE) && legacy.contains(KEY_DESTRUCTIVE)) {
                editor.putBoolean(KEY_DESTRUCTIVE, legacy.getBoolean(KEY_DESTRUCTIVE, false))
            }
            if (!encrypted.contains(KEY_ORG_ADMIN) && legacy.contains(KEY_ORG_ADMIN)) {
                editor.putBoolean(KEY_ORG_ADMIN, legacy.getBoolean(KEY_ORG_ADMIN, false))
            }
            if (!encrypted.contains(KEY_CLIENT_ID)) {
                legacy.getString(KEY_CLIENT_ID, null)?.let { editor.putString(KEY_CLIENT_ID, it) }
            }
            if (!encrypted.contains(KEY_TOKEN)) {
                legacy.getString(KEY_TOKEN, null)?.let { editor.putString(KEY_TOKEN, it) }
            }
            if (!encrypted.contains(KEY_REQUESTED_SCOPES)) {
                legacy.getString(KEY_REQUESTED_SCOPES, null)?.let { editor.putString(KEY_REQUESTED_SCOPES, it) }
            }
            if (!encrypted.contains(KEY_GRANTED_SCOPES)) {
                legacy.getString(KEY_GRANTED_SCOPES, null)?.let { editor.putString(KEY_GRANTED_SCOPES, it) }
            }

            check(editor.commit()) { "Could not migrate GitHub Agent Access into encrypted storage." }
            check(legacy.edit().clear().commit()) { "Could not clear legacy plaintext GitHub Agent Access storage." }
        }

        fun buildScopes(
            writeEnabled: Boolean,
            destructiveEnabled: Boolean,
            organizationAdminEnabled: Boolean
        ): String {
            // Private repository source access in OAuth Apps is coarse-grained: GitHub's
            // `repo` scope itself is read/write. OmniDev's local gate enforces read-only
            // mode when writeEnabled=false.
            val scopes = linkedSetOf("repo", "notifications")

            if (writeEnabled) {
                scopes += listOf(
                    "user",
                    "workflow",
                    "gist",
                    "project",
                    "write:packages",
                    "codespace",
                    "write:public_key",
                    "write:gpg_key",
                    "write:ssh_signing_key"
                )
            } else {
                scopes += listOf(
                    "read:user",
                    "user:email",
                    "read:project",
                    "read:packages",
                    "read:public_key",
                    "read:gpg_key",
                    "read:ssh_signing_key"
                )
            }

            if (organizationAdminEnabled) {
                scopes += listOf(
                    "admin:org",
                    "admin:org_hook",
                    "admin:public_key",
                    "admin:gpg_key",
                    "admin:ssh_signing_key"
                )
            } else {
                scopes += "read:org"
            }

            if (destructiveEnabled) {
                scopes += listOf("delete_repo", "delete:packages")
            }
            return scopes.joinToString(" ")
        }
    }
}
