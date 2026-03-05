package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Interacts with the GitHub REST API for issue creation and pull request management.
 *
 * Requires the user to configure a `GITHUB_PAT` (Personal Access Token) in the
 * Integrations settings screen.
 */
object GitHubManagerTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "github_manager",
            description = "Interacts with the GitHub API. Supported actions: create_issue, create_pull_request. " +
                "Requires a GitHub Personal Access Token configured in Integrations settings.",
            parameters = listOf(
                ToolParameter("action", "string", "Action: create_issue or create_pull_request.", required = true),
                ToolParameter("repo", "string", "Repository in owner/name format (e.g., user/repo).", required = true),
                ToolParameter("title", "string", "Title for the issue or PR.", required = true),
                ToolParameter("body", "string", "Body/description text.", required = true),
                ToolParameter("head", "string", "Source branch (for PRs only).", required = false),
                ToolParameter("base", "string", "Target branch (for PRs only, default: main).", required = false)
            )
        )
    )

    suspend fun execute(
        pat: String?,
        action: String,
        repo: String,
        title: String,
        body: String,
        head: String? = null,
        base: String? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (pat.isNullOrBlank()) {
            return@withContext ToolExecutionResult(
                "GitHub PAT is not configured. Go to Settings → Integrations to set it up.",
                isError = true
            )
        }

        try {
            when (action) {
                "create_issue" -> createIssue(pat, repo, title, body)
                "create_pull_request" -> createPullRequest(pat, repo, title, body, head, base ?: "main")
                else -> ToolExecutionResult("Unknown action: $action. Use create_issue or create_pull_request.", isError = true)
            }
        } catch (e: IOException) {
            ToolExecutionResult("GitHub API error: ${e.message}", isError = true)
        }
    }

    private fun createIssue(pat: String, repo: String, title: String, body: String): ToolExecutionResult {
        val url = URL("https://api.github.com/repos/$repo/issues")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $pat")
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000

        val jsonBody = """{"title":${escapeJson(title)},"body":${escapeJson(body)}}"""
        conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val responseBody = if (code in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
        }

        return if (code in 200..299) {
            ToolExecutionResult("✅ Issue created successfully in $repo.\n$responseBody")
        } else {
            ToolExecutionResult("GitHub API error ($code): $responseBody", isError = true)
        }
    }

    private fun createPullRequest(
        pat: String, repo: String, title: String, body: String, head: String?, base: String
    ): ToolExecutionResult {
        if (head.isNullOrBlank()) {
            return ToolExecutionResult("head branch is required for create_pull_request.", isError = true)
        }

        val url = URL("https://api.github.com/repos/$repo/pulls")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $pat")
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000

        val jsonBody = """{"title":${escapeJson(title)},"body":${escapeJson(body)},"head":${escapeJson(head)},"base":${escapeJson(base)}}"""
        conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val responseBody = if (code in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
        }

        return if (code in 200..299) {
            ToolExecutionResult("✅ Pull request created successfully in $repo.\n$responseBody")
        } else {
            ToolExecutionResult("GitHub API error ($code): $responseBody", isError = true)
        }
    }

    private fun escapeJson(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
