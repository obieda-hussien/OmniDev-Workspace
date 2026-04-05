package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.auth.GitHubDeviceFlowManager
import com.omnidev.workspace.data.auth.GitHubDeviceFlowManager.DeviceFlowState
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.SettingsRepository

/**
 * LLM-callable tool that autonomously initiates GitHub Device Flow authentication.
 *
 * The agent calls this tool when it encounters a 401 Unauthorized response or detects
 * that a required GitHub scope (e.g., `workflow`, `gist`) is missing.
 *
 * Behaviour:
 * 1. Starts the Device Flow — GitHub issues a short user-code.
 * 2. Returns the code and verification URL in its result so the LLM can relay them to
 *    the user ("go to github.com/login/device and enter XXXX-XXXX").
 * 3. Polls until the user completes authorization, then stores the token and returns a
 *    success message so the agent can resume its original task.
 *
 * @param settingsRepository Persists the resulting OAuth / PAT token.
 * @param apiKeyRepository   Stores the token under the correct [com.omnidev.workspace.data.model.ModelProvider] key.
 * @param subMode            Which GitHub integration sub-mode to authenticate (default: COPILOT).
 */
class RequestGitHubAuthenticationTool(
    private val settingsRepository: SettingsRepository,
    private val apiKeyRepository: ApiKeyRepository,
    private val subMode: GitHubDeviceFlowManager.SubMode = GitHubDeviceFlowManager.SubMode.COPILOT
) {

    companion object {
        fun getToolDefinitions(): List<ToolDefinition> = listOf(
            ToolDefinition(
                name = "request_github_auth",
                description = "Initiates GitHub Device Flow authentication. " +
                    "Call this when you encounter a 401 Unauthorized error or when a GitHub " +
                    "operation fails due to missing OAuth scopes. " +
                    "The tool returns the user-code and verification URL so you can relay " +
                    "them to the user, then blocks until the user completes authorization " +
                    "(or the code expires in ~15 minutes). " +
                    "On success the OAuth token is stored automatically — you can then " +
                    "retry the original GitHub operation.",
                parameters = listOf(
                    ToolParameter(
                        name = "requested_scopes",
                        type = "string",
                        description = "Space-separated GitHub OAuth scopes to request " +
                            "(e.g., \"repo workflow gist\"). " +
                            "Omit or leave blank to use the default comprehensive scope: " +
                            "\"${GitHubDeviceFlowManager.DEFAULT_COMPREHENSIVE_SCOPE}\".",
                        required = false
                    )
                )
            )
        )
    }

    /**
     * Runs the Device Flow to completion (blocking suspend).
     *
     * @param requestedScopes Optional scope override; falls back to [GitHubDeviceFlowManager.DEFAULT_COMPREHENSIVE_SCOPE].
     * @return A [ToolExecutionResult] that includes the user-code and the final auth outcome.
     */
    suspend fun execute(requestedScopes: String?): ToolExecutionResult {
        val scope = requestedScopes?.takeIf { it.isNotBlank() }
            ?: GitHubDeviceFlowManager.DEFAULT_COMPREHENSIVE_SCOPE

        var userCode: String? = null
        var verificationUri: String? = null
        var authResult: ToolExecutionResult? = null

        GitHubDeviceFlowManager.startDeviceFlowAndPoll(
            settingsRepository = settingsRepository,
            apiKeyRepository = apiKeyRepository,
            subMode = subMode,
            overrideScope = scope
        ).collect { state ->
            when (state) {
                is DeviceFlowState.AwaitingUserCode -> {
                    // Capture the code so it can be included in the final result message.
                    userCode = state.userCode
                    verificationUri = state.verificationUri
                }
                is DeviceFlowState.Polling -> { /* waiting — keep collecting */ }
                is DeviceFlowState.Success -> {
                    val codeHint = userCode?.let { " (authorization code: $it at $verificationUri)" } ?: ""
                    authResult = ToolExecutionResult(
                        "GitHub authentication successful$codeHint. " +
                            "OAuth token stored. You can now retry the original GitHub operation."
                    )
                }
                is DeviceFlowState.Error -> {
                    val codeHint = userCode?.let { " Code was: $it at $verificationUri." } ?: ""
                    authResult = ToolExecutionResult(
                        "GitHub authentication failed: ${state.message}.$codeHint",
                        isError = true
                    )
                }
            }
        }

        // The flow always terminates with either Success or Error, so authResult will be
        // non-null here.  The fallback below is a defensive safety net for any future
        // flow paths that don't set a terminal state.
        return authResult ?: ToolExecutionResult(
            "GitHub authentication flow ended without a result.",
            isError = true
        )
    }
}
