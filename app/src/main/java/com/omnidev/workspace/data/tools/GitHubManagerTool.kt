package com.omnidev.workspace.data.tools

import com.omnidev.workspace.OmniDevApp
import com.omnidev.workspace.data.auth.GitHubAgentAccessStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * User-authorized GitHub account control surface.
 *
 * Copilot/Models credentials are NOT used here. The user must explicitly enable
 * "GitHub Agent Access" in Integrations & Linked Accounts and authorize a separate
 * scoped OAuth token. Local policy then applies a second gate on top of GitHub's
 * OAuth scopes: read, write, destructive, and organization-admin capabilities.
 *
 * `api_request` intentionally provides a future-proof relative GitHub API bridge
 * while pinning the host to api.github.com so the OAuth token can never be sent to
 * an arbitrary host. OkHttp is used rather than HttpURLConnection so PATCH and the
 * full GitHub REST method set behave consistently on older Android releases.
 */
object GitHubManagerTool {
    private const val API_BASE = "https://api.github.com"
    private const val API_VERSION = "2022-11-28"
    private const val MAX_RESPONSE_CHARS = 40_000
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "github_manager",
            description = "User-authorized GitHub account control. The user must enable GitHub Agent Access in Settings → Integrations & Linked Accounts. " +
                "Actions: policy_status, whoami, create_issue, create_pull_request, api_request. " +
                "api_request can call any relative GitHub REST API endpoint on api.github.com (including /graphql) using the separately authorized account token; local read/write/destructive/org-admin gates still apply. " +
                "IMPORTANT legacy router compatibility: repo, title, and body must always be supplied; pass an empty string when an action does not use one.",
            parameters = listOf(
                ToolParameter("action", "string", "policy_status | whoami | create_issue | create_pull_request | api_request", required = true),
                ToolParameter(
                    "repo", "string",
                    "For issue/PR: owner/name. For api_request: relative API path such as /user/repos, /repos/owner/repo/actions/runs, or /graphql. For policy_status/whoami pass an empty string.",
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
        val policy = runCatching { store.policy() }.getOrElse { error ->
            return@withContext ToolExecutionResult(
                "GITHUB_SECURE_STORAGE_ERROR: ${error.message ?: error.javaClass.simpleName}",
                isError = true
            )
        }

        if (action.equals("policy_status", ignoreCase = true)) {
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
        validateJsonBody(jsonBody)

        val requestBody = when {
            normalizedMethod in setOf("POST", "PUT", "PATCH") ->
                (jsonBody ?: "{}").toRequestBody(JSON_MEDIA_TYPE)
            normalizedMethod == "DELETE" && !jsonBody.isNullOrBlank() ->
                jsonBody.toRequestBody(JSON_MEDIA_TYPE)
            else -> null
        }

        val request = Request.Builder()
            .url(API_BASE + safeEndpoint)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("User-Agent", "OmniDev-Workspace")
            .method(normalizedMethod, requestBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val code = response.code
            val responseText = response.body?.string().orEmpty()
            val scopes = response.header("X-OAuth-Scopes").orEmpty()
            val accepted = response.header("X-Accepted-OAuth-Scopes").orEmpty()
            val permissions = response.header("X-Accepted-GitHub-Permissions").orEmpty()
            val clipped = responseText.take(MAX_RESPONSE_CHARS)

            return if (response.isSuccessful) {
                ToolExecutionResult(
                    buildString {
                        append("✅ GitHub $normalizedMethod $safeEndpoint → HTTP $code")
                        if (clipped.isNotBlank()) append("\n").append(clipped)
                        if (responseText.length > clipped.length) append("\n[response truncated]")
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
        }
    }

    private fun validateJsonBody(jsonBody: String?) {
        if (jsonBody.isNullOrBlank()) return
        val trimmed = jsonBody.trim()
        val valid = when {
            trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }.isSuccess
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.isSuccess
            else -> false
        }
        require(valid) { "api_request body must be a valid JSON object or array." }
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

        if (endpoint.startsWith("/orgs/") && method != "GET" && !policy.organizationAdminEnabled) {
            throw IllegalArgumentException("GitHub organization-admin access is disabled by the user.")
        }
    }

    private fun requireWrite(policy: GitHubAgentAccessStore.Policy): ToolExecutionResult? =
        if (policy.writeEnabled) null else ToolExecutionResult(
            "GITHUB_WRITE_DISABLED: The user allowed GitHub read access but disabled write operations.",
            isError = true
        )

    private fun normalizeEndpoint(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "GitHub API endpoint is empty." }
        require(!trimmed.contains("\r") && !trimmed.contains("\n")) { "Invalid endpoint." }
        require(!trimmed.contains("..")) { "Path traversal is not allowed." }
        require(!trimmed.contains("://")) { "Only relative api.github.com endpoints are allowed." }
        require(!trimmed.startsWith("//")) { "Protocol-relative endpoints are not allowed." }
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
        appendLine("Advanced account/org administration: ${policy.organizationAdminEnabled}")
        appendLine("Granted scopes: ${policy.grantedScopes.ifBlank { "(not authorized)" }}")
        append("Policy can only be changed by the user in Settings → Integrations & Linked Accounts.")
    }.trimEnd()
}
