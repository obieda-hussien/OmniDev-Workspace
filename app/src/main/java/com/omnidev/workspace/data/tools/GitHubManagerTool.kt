package com.omnidev.workspace.data.tools

import com.omnidev.workspace.OmniDevApp
import com.omnidev.workspace.data.auth.GitHubAgentAccessStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * User-authorized GitHub account control surface.
 *
 * Copilot/Models credentials are NOT used here. The user must explicitly enable
 * "GitHub Agent Access" in Integrations & Linked Accounts and authorize a separate
 * scoped OAuth token. Local policy then applies a second gate on top of GitHub's
 * OAuth scopes: read, write, destructive, and organization-admin capabilities.
 *
 * `api_request` intentionally provides a future-proof relative GitHub REST API
 * bridge while pinning the host to api.github.com so the OAuth token can never be
 * sent to an arbitrary host.
 */
object GitHubManagerTool {
    private const val API_BASE = "https://api.github.com"
    private const val API_VERSION = "2022-11-28"
    private const val MAX_RESPONSE_CHARS = 40_000

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "github_manager",
            description = "User-authorized GitHub account control. The user must enable GitHub Agent Access in Settings → Integrations & Linked Accounts. " +
                "Actions: policy_status, whoami, create_issue, create_pull_request, api_request. " +
                "api_request can call any relative GitHub REST API endpoint on api.github.com using the separately authorized account token; local read/write/destructive/org-admin gates still apply. " +
                "IMPORTANT legacy router compatibility: repo, title, and body must always be supplied; pass an empty string when an action does not use one.",
            parameters = listOf(
                ToolParameter("action", "string", "policy_status | whoami | create_issue | create_pull_request | api_request", required = true),
                ToolParameter(
                    "repo", "string",
                    "For issue/PR: owner/name. For api_request: relative REST path such as /user/repos or /repos/owner/repo/actions/runs. For policy_status/whoami pass an empty string.",
                    required = true
                ),
                ToolParameter(
                    "title", "string",
                    "Issue/PR title. For api_request this is the HTTP method: GET, POST, PUT, PATCH, DELETE. For policy_status/whoami pass an empty string.",
                    required = true
                ),
                ToolParameter(
                    "body", "string",
                    "Issue/PR body. For api_request this is optional JSON request body; pass an empty string for requests without a body.",
                    required = true
                ),
                ToolParameter("head", "string", "Source branch for create_pull_request.", required = false),
                ToolParameter("base", "string", "Target branch for create_pull_request (default main).", required = false)
            )
        )
    )

    suspend fun execute(
        @Suppress("UNUSED_PARAMETER") pat: String?,
        action: String,
        repo: String,
        title: String,
        body: String,
        head: String? = null,
        base: String? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val store = GitHubAgentAccessStore(OmniDevApp.instance.applicationContext)
        val policy = store.policy()

        if (action == "policy_status") {
            return@withContext ToolExecutionResult(policySummary(policy))
        }

        if (!policy.enabled) {
            return@withContext ToolExecutionResult(
                "GITHUB_AGENT_ACCESS_DISABLED: The user has not allowed GitHub account control. " +
                    "Enable it explicitly in Settings → Integrations & Linked Accounts → GitHub Agent Access.",
                isError = true
            )
        }
        val token = policy.token?.takeIf { it.isNotBlank() }
            ?: return@withContext ToolExecutionResult(
                "GITHUB_AGENT_NOT_AUTHORIZED: GitHub Agent Access is enabled but no separate account-control OAuth token is connected. " +
                    "Authorize it from Settings → Integrations & Linked Accounts.",
                isError = true
            )

        try {
            when (action.lowercase().trim()) {
                "whoami" -> apiRequest(policy, token, "GET", "/user", null)
                "create_issue" -> {
                    requireWrite(policy) ?: createIssue(policy, token, repo, title, body)
                }
                "create_pull_request" -> {
                    requireWrite(policy) ?: createPullRequest(policy, token, repo, title, body, head, base ?: "main")
                }
                "api_request" -> {
                    val method = title.trim().uppercase()
                    val payload = body.takeIf { it.isNotBlank() }
                    apiRequest(policy, token, method, normalizeEndpoint(repo), payload)
                }
                else -> ToolExecutionResult(
                    "Unknown GitHub action '$action'. Use policy_status, whoami, create_issue, create_pull_request, or api_request.",
                    isError = true
                )
            }
        } catch (e: IllegalArgumentException) {
            ToolExecutionResult("GitHub request rejected: ${e.message}", isError = true)
        } catch (e: IOException) {
            ToolExecutionResult("GitHub API I/O error: ${e.message}", isError = true)
        } catch (e: Exception) {
            ToolExecutionResult("GitHub API failure: ${e.javaClass.simpleName}: ${e.message}", isError = true)
        }
    }

    private fun createIssue(
        policy: GitHubAgentAccessStore.Policy,
        token: String,
        repo: String,
        title: String,
        body: String
    ): ToolExecutionResult {
        validateRepo(repo)
        val payload = JSONObject().put("title", title).put("body", body).toString()
        return apiRequest(policy, token, "POST", "/repos/$repo/issues", payload)
    }

    private fun createPullRequest(
        policy: GitHubAgentAccessStore.Policy,
        token: String,
        repo: String,
        title: String,
        body: String,
        head: String?,
        base: String
    ): ToolExecutionResult {
        validateRepo(repo)
        require(!head.isNullOrBlank()) { "head branch is required for create_pull_request." }
        val payload = JSONObject()
            .put("title", title)
            .put("body", body)
            .put("head", head)
            .put("base", base)
            .toString()
        return apiRequest(policy, token, "POST", "/repos/$repo/pulls", payload)
    }

    private fun apiRequest(
        policy: GitHubAgentAccessStore.Policy,
        token: String,
        method: String,
        endpoint: String,
        jsonBody: String?
    ): ToolExecutionResult {
        val normalizedMethod = method.uppercase()
        require(normalizedMethod in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) {
            "Unsupported HTTP method '$method'."
        }
        val safeEndpoint = normalizeEndpoint(endpoint)
        enforceLocalPolicy(policy, normalizedMethod, safeEndpoint)
        if (!jsonBody.isNullOrBlank()) {
            require(runCatching { JSONObject(jsonBody) }.isSuccess || jsonBody.trim().startsWith("[")) {
                "api_request body must be valid JSON."
            }
        }

        val conn = URL(API_BASE + safeEndpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = normalizedMethod
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            conn.setRequestProperty("User-Agent", "OmniDev-Workspace")
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000

            if (!jsonBody.isNullOrBlank() && normalizedMethod in setOf("POST", "PUT", "PATCH")) {
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            val response = if (code in 200..299) {
                conn.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            val scopes = conn.getHeaderField("X-OAuth-Scopes").orEmpty()
            val accepted = conn.getHeaderField("X-Accepted-OAuth-Scopes").orEmpty()
            val permissions = conn.getHeaderField("X-Accepted-GitHub-Permissions").orEmpty()
            val clipped = response.take(MAX_RESPONSE_CHARS)

            return if (code in 200..299) {
                ToolExecutionResult(
                    buildString {
                        append("✅ GitHub $normalizedMethod $safeEndpoint → HTTP $code")
                        if (clipped.isNotBlank()) append("\n").append(clipped)
                        if (response.length > clipped.length) append("\n[response truncated]")
                    }
                )
            } else {
                ToolExecutionResult(
                    buildString {
                        append("GitHub API error HTTP $code for $normalizedMethod $safeEndpoint")
                        if (clipped.isNotBlank()) append("\n").append(clipped)
                        if (scopes.isNotBlank()) append("\nToken scopes: ").append(scopes)
                        if (accepted.isNotBlank()) append("\nAccepted OAuth scopes: ").append(accepted)
                        if (permissions.isNotBlank()) append("\nAccepted GitHub permissions: ").append(permissions)
                        append("\nThe agent cannot expand its own authorization; change GitHub Agent Access in Settings if additional access is desired.")
                    },
                    isError = true
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun enforceLocalPolicy(
        policy: GitHubAgentAccessStore.Policy,
        method: String,
        endpoint: String
    ) {
        if (method in setOf("POST", "PUT", "PATCH") && !policy.writeEnabled) {
            throw IllegalArgumentException("GitHub write access is disabled by the user in Integrations settings.")
        }
        if (method == "DELETE" && !policy.destructiveEnabled) {
            throw IllegalArgumentException("GitHub destructive access is disabled by the user in Integrations settings.")
        }

        // Organization mutations can alter memberships, teams, projects, Actions policy,
        // webhooks, etc. Require the explicit org-admin toggle in addition to write access.
        if (endpoint.startsWith("/orgs/") && method != "GET" && !policy.organizationAdminEnabled) {
            throw IllegalArgumentException("GitHub organization-admin access is disabled by the user.")
        }

        // Repository deletion is too consequential to depend only on generic DELETE.
        if (method == "DELETE" && Regex("^/repos/[^/]+/[^/]+/?$").matches(endpoint) && !policy.destructiveEnabled) {
            throw IllegalArgumentException("Repository deletion is disabled by the user.")
        }
    }

    private fun requireWrite(policy: GitHubAgentAccessStore.Policy): ToolExecutionResult? =
        if (policy.writeEnabled) null else ToolExecutionResult(
            "GITHUB_WRITE_DISABLED: The user allowed GitHub read access but disabled write operations.",
            isError = true
        )

    private fun normalizeEndpoint(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "GitHub REST endpoint is empty." }
        require(!trimmed.contains("\r") && !trimmed.contains("\n")) { "Invalid endpoint." }
        require(!trimmed.contains("..")) { "Path traversal is not allowed." }
        require(!trimmed.contains("://")) { "Only relative api.github.com endpoints are allowed." }
        val endpoint = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
        require(endpoint.length <= 2_048) { "Endpoint is too long." }
        return endpoint
    }

    private fun validateRepo(repo: String) {
        require(repo.matches(Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$"))) {
            "Invalid repo format '$repo'; expected owner/name."
        }
    }

    private fun policySummary(policy: GitHubAgentAccessStore.Policy): String = buildString {
        appendLine("GitHub Agent Access policy")
        appendLine("Enabled: ${policy.enabled}")
        appendLine("Connected account token: ${policy.connected}")
        appendLine("Write operations: ${policy.writeEnabled}")
        appendLine("Destructive DELETE operations: ${policy.destructiveEnabled}")
        appendLine("Organization admin mutations: ${policy.organizationAdminEnabled}")
        appendLine("Granted scopes: ${policy.grantedScopes.ifBlank { "(not authorized)" }}")
        append("Policy can only be changed by the user in Settings → Integrations & Linked Accounts.")
    }.trimEnd()
}
