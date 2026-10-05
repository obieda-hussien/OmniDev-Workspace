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
 * account-control token through OAuth Device Flow or a Personal Access Token.
 * Local policy then applies a second gate on top of GitHub's authorization:
 * read, write, destructive, and organization-admin capabilities.
 *
 * `api_request` intentionally provides a future-proof relative GitHub API bridge
 * while pinning the host to api.github.com so the token can never be sent to an
 * arbitrary host. OkHttp is used rather than HttpURLConnection so PATCH and the
 * full GitHub REST method set behave consistently on older Android releases.
 */
object GitHubManagerTool {
    private const val API_BASE = "https://api.github.com"
    private const val API_VERSION = "2022-11-28"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun getToolDefinitions(): List<ToolDefinition> = listOf(ToolDefinition(
        "github_manager",
        "Read and manage GitHub repositories using GitHub Agent Access. Prefer get_repo, list_contents and read_file for source review; list_repos only discovers names and cannot verify file contents. Preserve exact repository names including trailing hyphens. api_request uses endpoint and method, never owner/repo as an API root. Check policy_status for the connected account; a 404 is not proof of missing authorization.",
        listOf(
            ToolParameter("action", "string", "Operation", allowedValues = GitHubRequestContract.actions),
            ToolParameter("repo", "string", "Exact owner/repo for typed repository actions. Legacy api_request endpoint fallback.", required = false, requiredForActions = listOf("get_repo", "list_contents", "read_file", "create_issue", "create_pull_request")),
            ToolParameter("endpoint", "string", "api_request path, e.g. /repos/owner/repo/contents/README.md or /user/repos?page=2&per_page=20", required = false),
            ToolParameter("method", "string", "api_request HTTP method; defaults to GET", required = false, allowedValues = listOf("GET", "POST", "PUT", "PATCH", "DELETE")),
            ToolParameter("path", "string", "Exact file/directory path within repo; omit for root directory", required = false, requiredForActions = listOf("read_file")),
            ToolParameter("ref", "string", "Branch/tag/SHA; omit for actual repository default branch", required = false),
            ToolParameter("owner", "string", "list_repos: omit for authenticated account; supplied owner lists that user's public repos", required = false),
            ToolParameter("page", "integer", "list_repos page, default 1", required = false),
            ToolParameter("per_page", "integer", "list_repos page size 1..100, default 20", required = false),
            ToolParameter("start_line", "integer", "read_file first line, default 1", required = false),
            ToolParameter("max_lines", "integer", "read_file lines 1..200, default 120", required = false),
            ToolParameter("response_format", "string", "api_request: compact by default; raw for exact fields, still bounded", required = false, allowedValues = listOf("compact", "raw")),
            ToolParameter("title", "string", "Issue/PR title; legacy api_request method fallback", required = false, requiredForActions = listOf("create_issue", "create_pull_request")),
            ToolParameter("body", "string", "Issue/PR text or optional api_request JSON payload. Omit for GET.", required = false),
            ToolParameter("head", "string", "Source branch for PR", required = false, requiredForActions = listOf("create_pull_request")),
            ToolParameter("base", "string", "PR target branch, default main", required = false)
        )
    ))

    suspend fun execute(
        @Suppress("UNUSED_PARAMETER") pat: String?,
        action: String,
        repo: String = "",
        title: String = "",
        body: String = "",
        head: String? = null,
        base: String? = null,
        options: Map<String, String> = emptyMap()
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
                isError = true, classification = "GITHUB_AUTH_REQUIRED"
            )
        }
        val token = policy.token?.takeIf { it.isNotBlank() }
            ?: return@withContext ToolExecutionResult(
                "GITHUB_AGENT_NOT_AUTHORIZED: GitHub Agent Access is enabled but no separate account-control token is connected. " +
                    "Connect with GitHub or verify a Personal Access Token from Settings → Integrations & Linked Accounts.",
                isError = true, classification = "GITHUB_AUTH_REQUIRED"
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
                "get_repo", "list_repos", "list_contents", "read_file" -> {
                    val read = GitHubRequestContract.read(action.lowercase().trim(), repo, options["path"], options["ref"],
                        options["page"]?.toInt() ?: 1, options["per_page"]?.toInt() ?: 20, options["owner"])
                    apiRequest(policy, token, "GET", read.endpoint, null, read.rawFile,
                        startLine = options["start_line"]?.toInt() ?: 1, maxLines = options["max_lines"]?.toInt() ?: 120)
                }
                "api_request" -> {
                    val method = (options["method"] ?: title.takeIf { it.isNotBlank() } ?: "GET").trim().uppercase()
                    require(options["method"] == null || title.isBlank() || title.trim().equals(method, true)) { "Conflicting method and legacy title." }
                    require(options["endpoint"] == null || repo.isBlank() || GitHubRequestContract.endpoint(repo, method) == GitHubRequestContract.endpoint(options.getValue("endpoint"), method)) { "Conflicting endpoint and legacy repo." }
                    val endpoint = GitHubRequestContract.endpoint(options["endpoint"] ?: repo, method)
                    apiRequest(policy, token, method, endpoint, body.takeIf { it.isNotBlank() },
                        rawResponse = options["response_format"] == "raw")
                }
                else -> ToolExecutionResult(
                    "Unknown GitHub action '$action'. Use policy_status, whoami, create_issue, create_pull_request, or api_request.",
                    isError = true
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: IllegalArgumentException) {
            ToolExecutionResult("GitHub request rejected: ${e.message}", isError = true, classification = "INVALID_TOOL_ARGUMENTS")
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
        val target = GitHubRequestContract.repo(repo)
        require(title.isNotBlank()) { "Issue/PR title must not be blank." }
        val payload = JSONObject().put("title", title).put("body", body).toString()
        return apiRequest(policy, token, "POST", "/repos/$target/issues", payload)
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
        val target = GitHubRequestContract.repo(repo)
        require(title.isNotBlank()) { "Issue/PR title must not be blank." }
        require(!head.isNullOrBlank()) { "head branch is required for create_pull_request." }
        val payload = JSONObject()
            .put("title", title)
            .put("body", body)
            .put("head", head)
            .put("base", base)
            .toString()
        return apiRequest(policy, token, "POST", "/repos/$target/pulls", payload)
    }

    internal fun apiRequest(
        policy: GitHubAgentAccessStore.Policy,
        token: String,
        method: String,
        endpoint: String,
        jsonBody: String?,
        rawFile: Boolean = false,
        rawResponse: Boolean = false,
        startLine: Int = 1,
        maxLines: Int = 120,
        client: OkHttpClient = httpClient
    ): ToolExecutionResult {
        val normalizedMethod = method.uppercase()
        require(normalizedMethod in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) {
            "Unsupported HTTP method '$method'."
        }
        val safeEndpoint = GitHubRequestContract.endpoint(endpoint, normalizedMethod)
        enforceLocalPolicy(policy, normalizedMethod, safeEndpoint)
        validateJsonBody(jsonBody)
        require(!rawFile || startLine >= 1 && maxLines in 1..200) { "Invalid file line range." }

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
            .header("Accept", if (rawFile) "application/vnd.github.raw+json" else "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("User-Agent", "OmniDev-Workspace")
            .method(normalizedMethod, requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val code = response.code
            val responseText = response.body?.charStream()?.use { reader ->
                val buffer = CharArray(4096)
                val text = StringBuilder()
                while (text.length <= 1_000_000) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, 1_000_001 - text.length))
                    if (count < 0) break
                    text.append(buffer, 0, count)
                }
                text.toString()
            }.orEmpty()
            if (responseText.length > 1_000_000) return ToolExecutionResult(
                "GitHub response exceeds the bounded reader. Narrow the endpoint or page; no complete resource was verified.",
                isError = true, classification = "GITHUB_RESPONSE_TOO_LARGE")
            val next = GitHubResponseFormatter.nextEndpoint(response.header("Link"))
            return if (response.isSuccessful) {
                val evidence = if (rawFile) GitHubResponseFormatter.filePage(responseText, startLine, maxLines)
                    else GitHubResponseFormatter.format(responseText, safeEndpoint, rawResponse)
                ToolExecutionResult(buildString {
                    appendLine("GitHub $normalizedMethod $safeEndpoint → HTTP $code")
                    appendLine("Account: @${policy.accountLogin ?: "unknown"}")
                    append(evidence)
                    next?.let { append("\nNext page endpoint: $it (this response is only one page)") }
                })
            } else {
                val classification = when (code) {
                    401 -> "GITHUB_AUTH_REQUIRED"
                    403, 429 -> if (response.header("X-RateLimit-Remaining") == "0" || response.header("Retry-After") != null) "GITHUB_RATE_LIMITED" else "GITHUB_PERMISSION_DENIED"
                    404 -> "GITHUB_RESOURCE_NOT_FOUND"
                    422, 400 -> "INVALID_TOOL_ARGUMENTS"
                    else -> "GITHUB_HTTP_ERROR"
                }
                val hint = when (classification) {
                    "GITHUB_AUTH_REQUIRED" -> "Token invalid/expired/revoked. Reconnect GitHub Agent Access in Settings."
                    "GITHUB_PERMISSION_DENIED" -> "Check token repository selection/permissions, organization approval or SSO. Local switches cannot grant token permissions."
                    "GITHUB_RATE_LIMITED" -> "GitHub rate limit: do not repeatedly retry now. Retry-After=${response.header("Retry-After") ?: "not supplied"}; reset=${response.header("X-RateLimit-Reset") ?: "not supplied"}."
                    "GITHUB_RESOURCE_NOT_FOUND" -> "Check exact owner/repo, path and ref. A private resource may also be hidden by token repository selection or permissions; 404 alone cannot distinguish this. Do not remove punctuation, enumerate unrelated repos or switch to local git as a substitute for this read."
                    else -> "Check the endpoint and request data; do not claim the operation succeeded."
                }
                ToolExecutionResult("GitHub HTTP $code for $normalizedMethod $safeEndpoint\nAccount: @${policy.accountLogin ?: "unknown"}\n" +
                    responseText.take(1200) + "\n" + hint +
                    response.header("X-Accepted-GitHub-Permissions")?.let { "\nRequired GitHub permissions: $it" }.orEmpty(),
                    isError = true, classification = classification,
                    persistentFailure = classification in setOf("GITHUB_AUTH_REQUIRED", "GITHUB_PERMISSION_DENIED", "GITHUB_RATE_LIMITED"))
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
        if (method == "DELETE" && (!policy.writeEnabled || !policy.destructiveEnabled)) {
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

    private fun policySummary(policy: GitHubAgentAccessStore.Policy): String = buildString {
        appendLine("GitHub Agent Access policy")
        appendLine("Enabled: ${policy.enabled}")
        appendLine("Connected: ${policy.connected}")
        appendLine("Connection method: ${policy.authMethod.displayName}")
        appendLine("Account: ${policy.accountLogin?.let { "@$it" } ?: "(unknown / not connected)"}")
        appendLine("Write operations: ${policy.writeEnabled}")
        appendLine("Destructive DELETE operations: ${policy.destructiveEnabled}")
        appendLine("Advanced account/org administration: ${policy.organizationAdminEnabled}")
        appendLine("Reported/granted scopes: ${policy.grantedScopes.ifBlank { "(GitHub-managed permissions or not authorized)" }}")
        append("Policy can only be changed by the user in Settings → Integrations & Linked Accounts.")
    }.trimEnd()
}
