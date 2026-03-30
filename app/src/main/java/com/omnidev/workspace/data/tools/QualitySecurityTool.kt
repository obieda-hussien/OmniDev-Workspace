package com.omnidev.workspace.data.tools

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Unified quality/security utility tool for code review, basic vulnerability checks, and quick tests.
 *
 * * HACKER UPGRADES:
 * 1. Native App Scanning: Reads directly from PackageManager instead of regex-scraping text outputs.
 * 2. Advanced Secret Detection: Added modern heuristics for AWS Keys and JWT Tokens.
 * 3. Resilient HTTP Scanner: Gracefully handles 4xx/5xx HTTP error streams without crashing.
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
            description = "Run automated code review, web header vulnerability scans, app export risk analysis, and HTTP testing.",
            parameters = listOf(
                ToolParameter("action", "string", "One of: code_review, scan_web_vulnerabilities, scan_app_vulnerabilities, run_http_test", required = true),
                ToolParameter("code", "string", "Source code text for action=code_review.", required = false),
                ToolParameter("filename", "string", "Optional file name for action=code_review.", required = false),
                ToolParameter("url", "string", "Target URL for web scan/test actions.", required = false),
                ToolParameter("package_name", "string", "Installed Android package for action=scan_app_vulnerabilities.", required = false),
                ToolParameter("method", "string", "HTTP method for action=run_http_test (default GET).", required = false),
                ToolParameter("expected_status", "string", "Expected HTTP status for action=run_http_test (default 200).", required = false)
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
                else -> ToolExecutionResult("Invalid action. Use one of: code_review, scan_web_vulnerabilities, scan_app_vulnerabilities, run_http_test", isError = true)
            }
        }

    // ─────────────────────────────────────────────────────────────────
    // Code Review Engine
    // ─────────────────────────────────────────────────────────────────

    private fun runCodeReview(args: Map<String, String>): ToolExecutionResult {
        val code = args["code"]?.trim() ?: return ToolExecutionResult("Missing required argument: code", isError = true)
        val filename = args["filename"]?.trim().orEmpty()
        val findings = mutableListOf<String>()

        if (Regex("(?i)TODO|FIXME").containsMatchIn(code)) {
            findings += "Found TODO/FIXME markers; verify unfinished logic is intentional."
        }

        // Modern Secret Detection (AWS Keys, JWT Tokens, Hardcoded passwords)
        val quotedSecretPattern = Regex("(?i)(api[_-]?key|secret|token|password)\\s*[:=]\\s*(\"\"\"[\\s\\S]+?\"\"\"|\"[^\"]+\"|'[^']+')")
        val unquotedSecretPattern = Regex("(?i)(api[_-]?key|secret|token|password)\\s*[:=]\\s*([A-Za-z0-9_\\-]{12,})")
        val awsKeyPattern = Regex("(?<![A-Z0-9])[A-Z0-9]{20}(?![A-Z0-9])") // Rough AWS AKIA matcher
        val jwtPattern = Regex("eyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+") // JWT signature
        
        if (quotedSecretPattern.containsMatchIn(code) || unquotedSecretPattern.containsMatchIn(code)) {
            findings += "Potential hardcoded generic secret detected; move sensitive values to secure config."
        }
        if (awsKeyPattern.containsMatchIn(code)) {
            findings += "CRITICAL: Potential AWS Access Key detected in code."
        }
        if (jwtPattern.containsMatchIn(code)) {
            findings += "CRITICAL: Potential hardcoded JWT Token detected."
        }

        // Insecure TLS/SSL detection
        if (
            Regex("(?i)trustAll|trustAllCerts").containsMatchIn(code) ||
            Regex("(?is)setHostnameVerifier\\s*\\{[^}]*->\\s*true\\s*\\}").containsMatchIn(code) ||
            Regex("(?is)setHostnameVerifier\\s*\\([^)]*->\\s*true\\s*\\)").containsMatchIn(code) ||
            Regex("(?i)checkServerTrusted\\s*\\([^)]*\\)\\s*\\{\\s*\\}").containsMatchIn(code)
        ) {
            findings += "CRITICAL: Potential TLS trust bypass detected; avoid insecure trust-all behavior."
        }

        if (Regex("(?m)^.{${CODE_REVIEW_LINE_LENGTH_THRESHOLD + 1},}$").containsMatchIn(code)) {
            findings += "Very long lines found (>${CODE_REVIEW_LINE_LENGTH_THRESHOLD} chars); may reduce readability."
        }

        val title = if (filename.isBlank()) "Code review summary" else "Code review summary for $filename"
        val output = if (findings.isEmpty()) {
            "$title:\n✅ No obvious high-signal issues found by lightweight static checks."
        } else {
            buildString {
                appendLine("$title:")
                findings.forEachIndexed { index, item -> appendLine("${index + 1}. $item") }
            }.trim()
        }
        return ToolExecutionResult(output = output)
    }

    // ─────────────────────────────────────────────────────────────────
    // Web Vulnerability Scanner
    // ─────────────────────────────────────────────────────────────────

    private fun scanWebVulnerabilities(args: Map<String, String>): ToolExecutionResult {
        val url = args["url"]?.trim() ?: return ToolExecutionResult("Missing required argument: url", isError = true)
        
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolExecutionResult("Invalid URL: must start with http:// or https://", isError = true)
        }

        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD" // HEAD is faster for header analysis than GET
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OmniDev-Workspace-SecurityTool/1.0")
            }
            
            try {
                // Safely get response code even if it's a 4xx/5xx error
                val status = try { conn.responseCode } catch (e: Exception) { conn.responseCode }
                
                val headers = conn.headerFields.filterKeys { it != null }
                    .mapValues { it.value.joinToString(", ") }
                    
                val missingHeaders = mutableListOf<String>()
                val recommended = listOf(
                    "Content-Security-Policy",
                    "Strict-Transport-Security",
                    "X-Content-Type-Options",
                    "X-Frame-Options",
                    "Referrer-Policy",
                    "Permissions-Policy"
                )
                
                recommended.forEach { h ->
                    if (!headers.keys.any { it.equals(h, ignoreCase = true) }) missingHeaders += h
                }

                val securityNotes = mutableListOf<String>()
                if (url.startsWith("http://")) {
                    securityNotes += "CRITICAL: Target uses unencrypted HTTP. Transport can be intercepted (MitM)."
                }
                if (missingHeaders.isNotEmpty()) {
                    securityNotes += "Missing security headers: ${missingHeaders.joinToString(", ")}"
                }
                
                val cookieHeaders = conn.headerFields
                    .filterKeys { it != null && it.equals("Set-Cookie", ignoreCase = true) }
                    .values.flatten()
                    
                if (cookieHeaders.isNotEmpty()) {
                    val hasSecureFlag = cookieHeaders.all { cookie -> cookie.split(';').map { it.trim().lowercase() }.any { it == "secure" } }
                    val hasHttpOnlyFlag = cookieHeaders.all { cookie -> cookie.split(';').map { it.trim().lowercase() }.any { it == "httponly" } }
                    
                    if (!hasSecureFlag && url.startsWith("https://")) securityNotes += "Set-Cookie appears without 'Secure' flag."
                    if (!hasHttpOnlyFlag) securityNotes += "Set-Cookie appears without 'HttpOnly' flag (Vulnerable to XSS)."
                }

                val body = buildString {
                    appendLine("Web vulnerability scan for: $url")
                    appendLine("HTTP status: $status")
                    appendLine()
                    if (securityNotes.isEmpty()) {
                        appendLine("✅ No obvious high-signal web security misconfigurations detected in headers.")
                    } else {
                        securityNotes.forEachIndexed { index, note -> appendLine("${index + 1}. $note") }
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

    // ─────────────────────────────────────────────────────────────────
    // App Vulnerability Scanner (Native Implementation)
    // ─────────────────────────────────────────────────────────────────

    private fun scanAppVulnerabilities(context: Context?, args: Map<String, String>): ToolExecutionResult {
        val ctx = context ?: return ToolExecutionResult("scan_app_vulnerabilities requires Android context.", isError = true)
        val packageName = args["package_name"]?.trim() ?: return ToolExecutionResult("Missing required argument: package_name", isError = true)

        val pm = ctx.packageManager
        
        return try {
            val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or 
                        PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
            
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, flags)
            }

            val appInfo = packageInfo.applicationInfo
            
            val exportedActivities = packageInfo.activities?.count { it.exported } ?: 0
            val exportedServices = packageInfo.services?.count { it.exported } ?: 0
            val exportedReceivers = packageInfo.receivers?.count { it.exported } ?: 0
            val exportedProviders = packageInfo.providers?.count { it.exported } ?: 0

            val findings = mutableListOf<String>()
            
            // Check App Flags
            if (appInfo != null) {
                if ((appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                    findings += "CRITICAL: App is DEBUGGABLE. It can be easily reverse-engineered and attached to by debuggers."
                }
                if ((appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0) {
                    findings += "WARNING: ALLOW_BACKUP is true. App data can be extracted via ADB."
                }
            }

            // Check Component Surface Area
            if (exportedActivities > MAX_SAFE_EXPORTED_ACTIVITIES) findings += "High number of exported activities: $exportedActivities (Attack Surface Expansion)"
            if (exportedServices > MAX_SAFE_EXPORTED_SERVICES) findings += "High number of exported services: $exportedServices"
            if (exportedReceivers > MAX_SAFE_EXPORTED_RECEIVERS) findings += "High number of exported receivers: $exportedReceivers"
            if (exportedProviders > 0) findings += "Exported content providers detected: $exportedProviders (Verify URI permissions)"

            val summary = buildString {
                appendLine("App vulnerability scan summary for $packageName:")
                appendLine("- Exported activities : $exportedActivities")
                appendLine("- Exported services   : $exportedServices")
                appendLine("- Exported receivers  : $exportedReceivers")
                appendLine("- Exported providers  : $exportedProviders")
                appendLine()
                if (findings.isEmpty()) {
                    appendLine("✅ No obvious high-signal manifest exposure risks detected.")
                } else {
                    findings.forEachIndexed { index, item -> appendLine("${index + 1}. $item") }
                }
            }.trim()

            ToolExecutionResult(summary)

        } catch (e: PackageManager.NameNotFoundException) {
            ToolExecutionResult("App not found: $packageName", isError = true)
        } catch (e: Exception) {
            ToolExecutionResult("Scan failed: ${e.message}", isError = true)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // HTTP Tester
    // ─────────────────────────────────────────────────────────────────

    private fun runHttpTest(args: Map<String, String>): ToolExecutionResult {
        val url = args["url"]?.trim() ?: return ToolExecutionResult("Missing required argument: url", isError = true)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolExecutionResult("Invalid URL: must start with http:// or https://", isError = true)
        }
        
        var method = (args["method"] ?: "GET").uppercase()
        if (method !in ALLOWED_HTTP_METHODS) {
            return ToolExecutionResult("Invalid method '$method'. Allowed: ${ALLOWED_HTTP_METHODS.joinToString()}", isError = true)
        }
        
        val expectedStatus = args["expected_status"]?.toIntOrNull() ?: 200

        return runCatching {
            // Android HttpURLConnection workaround for PATCH
            val overrideMethod = if (method == "PATCH") { method = "POST"; "PATCH" } else null

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                if (overrideMethod != null) setRequestProperty("X-HTTP-Method-Override", overrideMethod)
            }
            
            try {
                // Safely capture status code
                val actual = try { conn.responseCode } catch (e: Exception) { conn.responseCode }
                val passed = actual == expectedStatus
                
                ToolExecutionResult(
                    output = buildString {
                        appendLine("HTTP test result")
                        appendLine("URL: $url")
                        appendLine("Method: ${overrideMethod ?: method}")
                        appendLine("Expected status: $expectedStatus")
                        appendLine("Actual status: $actual")
                        append("Result: ${if (passed) "✅ PASS" else "❌ FAIL"}")
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
