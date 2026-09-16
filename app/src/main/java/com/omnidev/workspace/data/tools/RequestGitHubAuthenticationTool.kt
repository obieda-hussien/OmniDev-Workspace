package com.omnidev.workspace.data.tools

import com.omnidev.workspace.OmniDevApp
import com.omnidev.workspace.data.auth.GitHubAgentAccessStore
import com.omnidev.workspace.data.auth.GitHubDeviceFlowManager
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.SettingsRepository

/**
 * GitHub authorization guard exposed to the agent.
 *
 * The old implementation allowed the LLM to request arbitrary OAuth scopes and
 * launch a new Device Flow itself. That violates the linked-account permission
 * boundary: an agent must never be able to escalate its own account privileges.
 *
 * Authorization is now exclusively user-driven from Settings → Integrations &
 * Linked Accounts. This tool only reports whether the user-enabled account-control
 * token is ready. The `requested_scopes` argument remains for wire compatibility
 * with older prompts but is intentionally ignored.
 */
class RequestGitHubAuthenticationTool(
    @Suppress("UNUSED_PARAMETER") private val settingsRepository: SettingsRepository,
    @Suppress("UNUSED_PARAMETER") private val apiKeyRepository: ApiKeyRepository,
    @Suppress("UNUSED_PARAMETER") private val subMode: GitHubDeviceFlowManager.SubMode = GitHubDeviceFlowManager.SubMode.COPILOT
) {

    companion object {
        fun getToolDefinitions(): List<ToolDefinition> = listOf(
            ToolDefinition(
                name = "request_github_auth",
                description = "Checks whether the user has explicitly authorized GitHub Agent Access. " +
                    "This tool cannot grant or expand OAuth scopes. If access is missing, tell the user to open Settings → Integrations & Linked Accounts → GitHub Agent Access and authorize there.",
                parameters = listOf(
                    ToolParameter(
                        name = "requested_scopes",
                        type = "string",
                        description = "Legacy compatibility field. Ignored; the agent cannot choose or expand its own GitHub OAuth scopes.",
                        required = false
                    )
                )
            )
        )
    }

    suspend fun execute(@Suppress("UNUSED_PARAMETER") requestedScopes: String?): ToolExecutionResult {
        val policy = GitHubAgentAccessStore(OmniDevApp.instance.applicationContext).policy()
        if (!policy.enabled) {
            return ToolExecutionResult(
                "GitHub Agent Access is disabled by the user. Open Settings → Integrations & Linked Accounts → GitHub Agent Access to enable it. " +
                    "I cannot enable it or expand its scopes myself.",
                isError = true
            )
        }
        if (!policy.connected) {
            return ToolExecutionResult(
                "GitHub Agent Access is enabled but the account-control OAuth session is not authorized. " +
                    "Complete Device Flow from Settings → Integrations & Linked Accounts. I cannot authorize myself.",
                isError = true
            )
        }
        return ToolExecutionResult(
            "✅ GitHub Agent Access is user-authorized. " +
                "write=${policy.writeEnabled}, destructive=${policy.destructiveEnabled}, " +
                "orgAdmin=${policy.organizationAdminEnabled}, scopes=${policy.grantedScopes.ifBlank { "unknown" }}. " +
                "Use github_manager for GitHub operations."
        )
    }
}
