package com.omnidev.workspace.data.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Unified quality/security utility tool for code review, basic vulnerability checks, and quick tests.
 */
object QualitySecurityTool {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val CODE_REVIEW_LINE_LENGTH_THRESHOLD = 180
    private const val MAX_SAFE_EXPORTED_ACTIVITIES = 10
    private const val MAX_SAFE_EXPORTED_SERVICES = 5
    private const val MAX_SAFE_EXPORTED_RECEIVERS = 5
    private val ALLOWED_HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "quality_security_tool",
            description = "Run code review checks, app/web vulnerability checks, and lightweight bug/error tests.",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "One of: code_review, scan_web_vulnerabilities, scan_app_vulnerabilities, run_http_test",
                    required = true
                ),
                ToolParameter(
                    name = "code",
                    type = "string",
                    description = "Source code text for action=code_review.",
                    required = false
                ),
                ToolParameter(
                    name = "filename",
                    type = "string",
                    description = "Optional file name for action=code_review.",
                    required = false
                ),
                ToolParameter(
                    name = "url",
                    type = "string",
                    description = "Target URL for web scan/test actions.",
                    required = false
                ),
                ToolParameter(
                    name = "package_name",
                    type = "string",
                    description = "Installed Android package for action=scan_app_vulnerabilities.",
                    required = false
                ),
                ToolParameter(
                    name = "method",
                    type = "string",
                    description = "HTTP method for action=run_http_test (default GET).",
                    required = false
                ),
                ToolParameter(
                    name = "expected_status",
                    type = "string",
                    description = "Expected HTTP status for action=run_http_test (default 200).",
                    required = false
                )
            )
        )
    )

    suspend fun execute(context: Context?, args: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            when ((args["action"] ?: "").trim().lowercase()) {
                "code_review" -> runCodeReview(args)
                "scan_web_vulnerabilities" -> scanWebVulnerabilities(args)
                "scan_app_vulnerabilities" -> scanAppVulnerabilities(context, args)
                "run_http_test" -> runHttpTest(args)
                else -> ToolExecutionResult(
                    "Invalid action. Use one of: code_review, scan_web_vulnerabilities, scan_app_vulnerabilities, run_http_test",
                    isError = true
                )
            }
        }

    private fun runCodeReview(args: Map<String, String>): ToolExecutionResult {
        val code = args["code"]?.trim()
            ?: return ToolExecutionResult("Missing required argument: code", isError = true)
        val filename = args["filename"]?.trim().orEmpty()
        val findings = mutableListOf<String>()

        if (Regex("(?i)TODO|FIXME").containsMatchIn(code)) {
            findings += "Found TODO/FIXME markers; verify unfinished logic is intentional."
        }
        val quotedSecretPattern =
            Regex("(?i)(api[_-]?key|secret|token|password)\\s*[:=]\\s*(\"\"\"[\\s\\S]+?\"\"\"|\"[^\"]+\"|'[^']+')")
        val unquotedSecretPattern =
            Regex("(?i)(api[_-]?key|secret|token|password)\\s*[:=]\\s*([A-Za-z0-9_\\-]{12,})")
        if (quotedSecretPattern.containsMatchIn(code) || unquotedSecretPattern.containsMatchIn(code)) {
            findings += "Potential hardcoded secret detected; move sensitive values to secure config."
        }
        if (
            Regex("(?i)trustAll|trustAllCerts").containsMatchIn(code) ||
            Regex("(?is)setHostnameVerifier\\s*\\{[^}]*->\\s*true\\s*\\}").containsMatchIn(code) ||
            Regex("(?is)setHostnameVerifier\\s*\\([^)]*->\\s*true\\s*\\)").containsMatchIn(code) ||
            Regex("(?i)checkServerTrusted\\s*\\([^)]*\\)\\s*\\{\\s*\\}").containsMatchIn(code)
        ) {
            findings += "Potential TLS trust bypass detected; avoid insecure trust-all behavior."
        }
        if (Regex("(?m)^.{${CODE_REVIEW_LINE_LENGTH_THRESHOLD + 1},}$").containsMatchIn(code)) {
            findings += "Very long lines found (>${CODE_REVIEW_LINE_LENGTH_THRESHOLD} chars); may reduce readability and reviewability."
        }

        val title = if (filename.isBlank()) "Code review summary" else "Code review summary for $filename"
        val output = if (findings.isEmpty()) {
            "$title:\nNo obvious high-signal issues found by lightweight checks."
        } else {
            buildString {
                appendLine("$title:")
                findings.forEachIndexed { index, item ->
                    appendLine("${index + 1}. $item")
                }
            }.trim()
        }
        return ToolExecutionResult(output = output)
    }

    private fun scanWebVulnerabilities(args: Map<String, String>): ToolExecutionResult {
        val url = args["url"]?.trim()
            ?: return ToolExecutionResult("Missing required argument: url", isError = true)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolExecutionResult("Invalid URL: must start with http:// or https://", isError = true)
        }

        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OmniDev-Workspace-SecurityTool/1.0")
            }
            try {
                val status = conn.responseCode
                val headers = conn.headerFields.filterKeys { it != null }
                    .mapValues { it.value.joinToString(", ") }
                val missingHeaders = mutableListOf<String>()
                val recommended = listOf(
                    "Content-Security-Policy",
                    "Strict-Transport-Security",
                    "X-Content-Type-Options",
                    "X-Frame-Options",
                    "Referrer-Policy"
                )
                recommended.forEach { h ->
                    if (!headers.keys.any { it.equals(h, ignoreCase = true) }) {
                        missingHeaders += h
                    }
                }

                val securityNotes = mutableListOf<String>()
                if (url.startsWith("http://")) {
                    securityNotes += "Target uses HTTP (not HTTPS). Transport can be intercepted."
                }
                if (missingHeaders.isNotEmpty()) {
                    securityNotes += "Missing security headers: ${missingHeaders.joinToString(", ")}"
                }
                val cookieHeaders = conn.headerFields
                    .filterKeys { it != null && it.equals("Set-Cookie", ignoreCase = true) }
                    .values
                    .flatten()
                if (cookieHeaders.isNotEmpty()) {
                    val hasSecureFlag = cookieHeaders.all { cookie ->
                        cookie.split(';').map { it.trim().lowercase() }.any { it == "secure" }
                    }
                    val hasHttpOnlyFlag = cookieHeaders.all { cookie ->
                        cookie.split(';').map { it.trim().lowercase() }.any { it == "httponly" }
                    }
                    if (!hasSecureFlag) {
                        securityNotes += "Set-Cookie appears without Secure flag."
                    }
                    if (!hasHttpOnlyFlag) {
                        securityNotes += "Set-Cookie appears without HttpOnly flag."
                    }
                }

                val body = buildString {
                    appendLine("Web vulnerability scan for: $url")
                    appendLine("HTTP status: $status")
                    appendLine()
                    if (securityNotes.isEmpty()) {
                        appendLine("No obvious high-signal web security misconfigurations detected.")
                    } else {
                        securityNotes.forEachIndexed { index, note ->
                            appendLine("${index + 1}. $note")
                        }
                    }
                }.trim()
                ToolExecutionResult(body, isError = status >= 500)
            } finally {
                conn.disconnect()
            }
        }.getOrElse { e ->
            ToolExecutionResult("Web vulnerability scan failed: ${e.message}", isError = true)
        }
    }

    private fun scanAppVulnerabilities(
        context: Context?,
        args: Map<String, String>
    ): ToolExecutionResult {
        val ctx = context
            ?: return ToolExecutionResult("scan_app_vulnerabilities requires Android context.", isError = true)
        val packageName = args["package_name"]?.trim()
            ?: return ToolExecutionResult("Missing required argument: package_name", isError = true)

        val analysis = AppManifestAnalyzerTool.execute(ctx, packageName, "all")
        if (analysis.isError) return analysis

        val output = analysis.output
        val exportedActivities = Regex("""Activities.*?📤 Exported \((\d+)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val exportedServices = Regex("""Services.*?📤 Exported \((\d+)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val exportedReceivers = Regex("""Broadcast Receivers.*?📤 Exported \((\d+)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val exportedProviders = Regex("""Content Providers.*?📤 Exported \((\d+)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

        val findings = mutableListOf<String>()
        if (exportedActivities > MAX_SAFE_EXPORTED_ACTIVITIES) {
            findings += "High number of exported activities: $exportedActivities"
        }
        if (exportedServices > MAX_SAFE_EXPORTED_SERVICES) {
            findings += "High number of exported services: $exportedServices"
        }
        if (exportedReceivers > MAX_SAFE_EXPORTED_RECEIVERS) {
            findings += "High number of exported receivers: $exportedReceivers"
        }
        if (exportedProviders > 0) findings += "Exported content providers detected: $exportedProviders"
        if (output.contains("── Deep Links ──")) findings += "Deep links exposed. Validate host/path allow-listing."

        val summary = buildString {
            appendLine("App vulnerability scan summary for $packageName:")
            appendLine("- Exported activities: $exportedActivities")
            appendLine("- Exported services: $exportedServices")
            appendLine("- Exported receivers: $exportedReceivers")
            appendLine("- Exported providers: $exportedProviders")
            appendLine()
            if (findings.isEmpty()) {
                appendLine("No obvious high-signal manifest exposure risks detected.")
            } else {
                findings.forEachIndexed { index, item ->
                    appendLine("${index + 1}. $item")
                }
            }
        }.trim()

        return ToolExecutionResult(summary)
    }

    private fun runHttpTest(args: Map<String, String>): ToolExecutionResult {
        val url = args["url"]?.trim()
            ?: return ToolExecutionResult("Missing required argument: url", isError = true)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolExecutionResult("Invalid URL: must start with http:// or https://", isError = true)
        }
        val method = (args["method"] ?: "GET").uppercase()
        if (method !in ALLOWED_HTTP_METHODS) {
            return ToolExecutionResult(
                "Invalid method '$method'. Allowed: ${ALLOWED_HTTP_METHODS.joinToString()}",
                isError = true
            )
        }
        val expectedStatus = args["expected_status"]?.toIntOrNull() ?: 200

        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            try {
                val actual = conn.responseCode
                val passed = actual == expectedStatus
                ToolExecutionResult(
                    output = buildString {
                        appendLine("HTTP test result")
                        appendLine("URL: $url")
                        appendLine("Method: $method")
                        appendLine("Expected status: $expectedStatus")
                        appendLine("Actual status: $actual")
                        append("Result: ${if (passed) "PASS" else "FAIL"}")
                    },
                    isError = !passed
                )
            } finally {
                conn.disconnect()
            }
        }.getOrElse { e ->
            ToolExecutionResult("HTTP test failed: ${e.message}", isError = true)
        }
    }
}
