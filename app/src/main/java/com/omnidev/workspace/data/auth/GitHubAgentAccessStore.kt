package com.omnidev.workspace.data.auth

import android.content.Context

/**
 * Local authorization policy for GitHub account control by the OmniDev agent.
 *
 * This is deliberately separate from GitHub Copilot/Models credentials. A user may
 * use GitHub as an AI provider without granting the agent any authority over their
 * repositories/account. Agent access is deny-by-default and can only be enabled from
 * Integrations & Linked Accounts.
 */
class GitHubAgentAccessStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Policy(
        val enabled: Boolean,
        val writeEnabled: Boolean,
        val destructiveEnabled: Boolean,
        val organizationAdminEnabled: Boolean,
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

    fun policy(): Policy = Policy(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        writeEnabled = prefs.getBoolean(KEY_WRITE, false),
        destructiveEnabled = prefs.getBoolean(KEY_DESTRUCTIVE, false),
        organizationAdminEnabled = prefs.getBoolean(KEY_ORG_ADMIN, false),
        oauthClientId = prefs.getString(KEY_CLIENT_ID, "").orEmpty(),
        token = prefs.getString(KEY_TOKEN, null),
        requestedScopes = prefs.getString(KEY_REQUESTED_SCOPES, DEFAULT_BASE_SCOPES).orEmpty(),
        grantedScopes = prefs.getString(KEY_GRANTED_SCOPES, "").orEmpty()
    )

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

    fun setOAuthClientId(clientId: String) {
        prefs.edit().putString(KEY_CLIENT_ID, clientId.trim()).apply()
    }

    fun saveAuthorization(token: String, requestedScopes: String, grantedScopes: String = requestedScopes) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_REQUESTED_SCOPES, requestedScopes.trim())
            .putString(KEY_GRANTED_SCOPES, grantedScopes.trim())
            .apply()
    }

    fun clearAuthorization() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_GRANTED_SCOPES)
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
            .apply()
    }

    /**
     * Computes OAuth scopes from the persisted explicit capability switches.
     * Local policy still applies an independent read/write/delete/admin gate even
     * when GitHub's OAuth scope is broader (notably `repo`).
     */
    fun scopesForCurrentPolicy(): String {
        val p = policy()
        return buildScopes(
            writeEnabled = p.writeEnabled,
            destructiveEnabled = p.destructiveEnabled,
            organizationAdminEnabled = p.organizationAdminEnabled
        )
    }

    companion object {
        private const val PREFS_NAME = "omnidev_github_agent_access"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_WRITE = "write_enabled"
        private const val KEY_DESTRUCTIVE = "destructive_enabled"
        private const val KEY_ORG_ADMIN = "organization_admin_enabled"
        private const val KEY_CLIENT_ID = "oauth_client_id"
        private const val KEY_TOKEN = "oauth_token"
        private const val KEY_REQUESTED_SCOPES = "requested_scopes"
        private const val KEY_GRANTED_SCOPES = "granted_scopes"

        const val DEFAULT_BASE_SCOPES = "repo read:user user:email notifications read:org read:project read:packages"

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
