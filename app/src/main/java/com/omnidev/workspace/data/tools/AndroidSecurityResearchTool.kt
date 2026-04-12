package com.omnidev.workspace.data.tools.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.ToolParameter
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

// ═══════════════════════════════════════════════════════════════════════════════
// AndroidSecurityResearchTool — Agent interface for AndroidVulnResearchEngine
//
// Exposes the full 4-phase vulnerability research pipeline as a single agent tool.
// Designed after the DARPA AI Cyber Challenge scaffolding architecture:
//   → Structured workflow (not just "scan") 
//   → Phased output (discovery → verification → PoC → patch)
//   → Self-contained research session with JSON output for agent parsing
// ═══════════════════════════════════════════════════════════════════════════════
object AndroidSecurityResearchTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "android_security_research",
            description = """
                Full autonomous vulnerability research pipeline for installed Android apps.
                Inspired by DARPA's AI Cyber Challenge and Anthropic's Project Glasswing approach.
                
                Actions:
                  full_research    → Run all 4 phases: static discovery + dynamic probing + exploit verification + patch generation.
                                     Most comprehensive. Returns full JSON report with PoC commands and Kotlin patches.
                  static_only      → Phase 1 only: DEX analysis, manifest, native libs, crypto. Fast (no shell execution).
                  dynamic_probe    → Phase 2 only: Live intent fuzzing, ContentProvider SQL injection, deep link injection.
                                     Requires Shizuku. Runs controlled shell probes against the target app.
                  verify_exploits  → Phase 3 only: Takes existing findings and executes safe PoC commands to confirm them.
                                     Upgrades UNVERIFIED findings to CONFIRMED or FALSE_POSITIVE.
                  generate_patches → Phase 4 only: Generate consolidated Kotlin/Java patches + Manifest changes + ProGuard rules.
                  list_apps        → List all installed user apps with basic security posture (risk score).
                  export_report    → Export last research report as HTML file to external storage.
                  
                Output format: JSON with fields:
                  riskScore (0-100), findings (array), confirmedCount, topPriorities, patchBundle.
                  Each finding includes: id, category, cwe, severity, cvss, title, description,
                  location, evidence, verificationStatus, exploitPoCs (with shell commands), remediation.
            """.trimIndent(),
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "One of: full_research, static_only, dynamic_probe, verify_exploits, generate_patches, list_apps, export_report",
                    required = true
                ),
                ToolParameter(
                    name = "package_name",
                    type = "string",
                    description = "Target app package name (e.g., 'com.whatsapp'). Required for all actions except list_apps.",
                    required = false
                ),
                ToolParameter(
                    name = "severity_filter",
                    type = "string",
                    description = "Filter output by minimum severity: CRITICAL, HIGH, MEDIUM, LOW, INFO. Default: all.",
                    required = false
                ),
                ToolParameter(
                    name = "include_patches",
                    type = "string",
                    description = "Whether to include full patch code in output. 'true' (default) or 'false' (summary only).",
                    required = false
                ),
                ToolParameter(
                    name = "max_results",
                    type = "string",
                    description = "Limit number of findings in output (default: 50).",
                    required = false
                )
            )
        )
    )

    suspend fun execute(
        context: Context,
        action: String,
        args: Map<String, String>
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val pkg = args["package_name"]?.trim()
        val severityFilter = args["severity_filter"]?.uppercase()?.let {
            runCatching { Severity.valueOf(it) }.getOrNull()
        }
        val includePatches = args["include_patches"]?.lowercase() != "false"
        val maxResults = args["max_results"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50

        when (action.lowercase().trim()) {

            "list_apps" -> listInstalledApps(context)

            "static_only" -> {
                val packageName = pkg ?: return@withContext missingPkg()
                val report = AndroidVulnResearchEngine.runFullResearch(
                    context = context,
                    packageName = packageName,
                    phases = setOf(AndroidVulnResearchEngine.ResearchPhase.STATIC)
                )
                formatReport(report, severityFilter, includePatches, maxResults)
            }

            "dynamic_probe" -> {
                val packageName = pkg ?: return@withContext missingPkg()
                val report = AndroidVulnResearchEngine.runFullResearch(
                    context = context,
                    packageName = packageName,
                    phases = setOf(
                        AndroidVulnResearchEngine.ResearchPhase.STATIC,
                        AndroidVulnResearchEngine.ResearchPhase.DYNAMIC
                    )
                )
                formatReport(report, severityFilter, includePatches, maxResults)
            }

            "verify_exploits" -> {
                val packageName = pkg ?: return@withContext missingPkg()
                val report = AndroidVulnResearchEngine.runFullResearch(
                    context = context,
                    packageName = packageName,
                    phases = setOf(
                        AndroidVulnResearchEngine.ResearchPhase.STATIC,
                        AndroidVulnResearchEngine.ResearchPhase.DYNAMIC,
                        AndroidVulnResearchEngine.ResearchPhase.EXPLOIT_VERIFY
                    )
                )
                formatReport(report, severityFilter, includePatches, maxResults)
            }

            "generate_patches" -> {
                val packageName = pkg ?: return@withContext missingPkg()
                // Run static phase to get findings, then generate patches
                val report = AndroidVulnResearchEngine.runFullResearch(
                    context = context,
                    packageName = packageName,
                    phases = setOf(AndroidVulnResearchEngine.ResearchPhase.STATIC)
                )
                // Return patch bundle only
                val filtered = filterByMinSeverity(report.findings, severityFilter).take(maxResults)
                val patchOutput = buildString {
                    appendLine("// PATCH BUNDLE for ${report.packageName} (${report.label})")
                    appendLine("// Risk Score: ${report.riskScore}/100 | Findings: ${filtered.size}")
                    appendLine()
                    filtered.forEach { f ->
                        appendLine("// [${f.id}] ${f.severity.emoji} ${f.title}")
                        appendLine("// Severity: ${f.severity.name} | CVSS: ${f.cvssScore} | CWE: ${f.category.cwe}")
                        appendLine("// Status: ${f.verificationStatus.name}")
                        appendLine()
                        appendLine(f.remediation.codeSnippet)
                        f.remediation.manifestChange?.let {
                            appendLine()
                            appendLine("// Manifest change:")
                            appendLine(it)
                        }
                        f.remediation.proguardRule?.let {
                            appendLine()
                            appendLine("// ProGuard rule:")
                            appendLine(it)
                        }
                        appendLine()
                        appendLine("// References: ${f.remediation.references.joinToString(", ")}")
                        appendLine("// " + "═".repeat(70))
                        appendLine()
                    }
                }
                ToolExecutionResult(patchOutput.take(15_000))
            }

            "full_research" -> {
                val packageName = pkg ?: return@withContext missingPkg()
                val report = AndroidVulnResearchEngine.runFullResearch(
                    context = context,
                    packageName = packageName,
                    phases = AndroidVulnResearchEngine.ResearchPhase.entries.toSet()
                )
                formatReport(report, severityFilter, includePatches, maxResults)
            }

            "export_report" -> {
                val packageName = pkg ?: return@withContext missingPkg()
                val report = AndroidVulnResearchEngine.runFullResearch(
                    context = context,
                    packageName = packageName,
                    phases = AndroidVulnResearchEngine.ResearchPhase.entries.toSet()
                )
                val outputPath = exportReportToHtml(context, report)
                if (outputPath != null) {
                    ToolExecutionResult("✅ Report exported to: $outputPath\n\nSummary:\n${buildSummaryText(report)}")
                } else {
                    ToolExecutionResult("❌ Failed to export report. JSON summary:\n${buildSummaryText(report)}", isError = true)
                }
            }

            else -> ToolExecutionResult(
                "Unknown action '$action'. Valid actions: full_research, static_only, dynamic_probe, verify_exploits, generate_patches, list_apps, export_report",
                isError = true
            )
        }
    }

    // ─── Output Formatters ──────────────────────────────────────────────────

    private fun formatReport(
        report: ResearchReport,
        severityFilter: Severity?,
        includePatches: Boolean,
        maxResults: Int
    ): ToolExecutionResult {
        val filtered = filterByMinSeverity(report.findings, severityFilter).take(maxResults)

        val sb = StringBuilder()
        sb.appendLine("═══ SECURITY RESEARCH REPORT ═══")
        sb.appendLine("Package    : ${report.packageName}")
        sb.appendLine("Label      : ${report.label}")
        sb.appendLine("Risk Score : ${report.riskScore}/100 ${getRiskEmoji(report.riskScore)}")
        sb.appendLine("Duration   : ${report.duration}ms")
        sb.appendLine("Phases     : ${report.phasesCompleted.joinToString(" → ")}")
        sb.appendLine("Attack Surface: ${report.attackSurfaceSummary}")
        sb.appendLine()

        val criticals = filtered.count { it.severity == Severity.CRITICAL }
        val highs = filtered.count { it.severity == Severity.HIGH }
        val confirmed = filtered.count { it.verificationStatus == VerificationStatus.CONFIRMED }
        sb.appendLine("Findings Summary:")
        sb.appendLine("  Total: ${filtered.size} | 🔴 Critical: $criticals | 🟠 High: $highs | ✅ Confirmed: $confirmed")

        if (report.topPriorities.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Top Priorities:")
            report.topPriorities.forEach { sb.appendLine("  • $it") }
        }

        sb.appendLine()
        sb.appendLine("═══ FINDINGS ═══")

        filtered.forEach { f ->
            sb.appendLine()
            sb.appendLine("┌─ [${f.id}] ${f.severity.emoji} ${f.title}")
            sb.appendLine("│  Severity : ${f.severity.name} (CVSS: ${f.cvssScore})")
            sb.appendLine("│  Category : ${f.category.displayName} (${f.category.cwe})")
            sb.appendLine("│  Status   : ${f.verificationStatus.name}")
            sb.appendLine("│  Location : ${f.location}")
            sb.appendLine("│  Evidence : ${f.evidence.take(200)}")

            if (f.exploitPoCs.isNotEmpty()) {
                sb.appendLine("│")
                sb.appendLine("│  PoC Commands:")
                f.exploitPoCs.take(2).forEach { poc ->
                    sb.appendLine("│    ▶ ${poc.description}")
                    sb.appendLine("│      ${poc.shellCommand.lines().first().take(120)}")
                    poc.verificationResult?.let {
                        sb.appendLine("│      Result: ${it.take(100)}")
                    }
                }
            }

            sb.appendLine("│")
            sb.appendLine("│  Fix: ${f.remediation.summary.take(150)}")

            if (includePatches && f.remediation.codeSnippet.isNotBlank()) {
                val snippet = f.remediation.codeSnippet.lines().take(8).joinToString("\n")
                sb.appendLine("│  Patch:")
                snippet.lines().forEach { sb.appendLine("│    $it") }
            }

            sb.appendLine("└─────────────────────────────────────────────────────────────────")
        }

        if (filtered.isEmpty()) {
            sb.appendLine("✅ No findings match the selected filter.")
        }

        val result = sb.toString()
        return ToolExecutionResult(result.take(14_000), truncated = result.length > 14_000)
    }

    private fun buildSummaryText(report: ResearchReport): String = buildString {
        appendLine("Risk Score: ${report.riskScore}/100")
        appendLine("Phases: ${report.phasesCompleted.joinToString(", ")}")
        appendLine("Total Findings: ${report.findings.size}")
        appendLine("Critical: ${report.findings.count { it.severity == Severity.CRITICAL }}")
        appendLine("High: ${report.findings.count { it.severity == Severity.HIGH }}")
        appendLine("Confirmed: ${report.findings.count { it.verificationStatus == VerificationStatus.CONFIRMED }}")
        appendLine("Duration: ${report.duration}ms")
        appendLine("Top Priorities:")
        report.topPriorities.take(3).forEach { appendLine("  • $it") }
    }

    private fun listInstalledApps(context: Context): ToolExecutionResult {
        val pm = context.packageManager
        val apps = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
        } catch (e: Exception) {
            return ToolExecutionResult("Failed to list packages: ${e.message}", isError = true)
        }

        // Filter out system apps to show user apps only
        val userApps = apps.filter { pi ->
            val flags = pi.applicationInfo?.flags ?: 0
            (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
        }

        val sb = StringBuilder()
        sb.appendLine("Installed User Apps: ${userApps.size}")
        sb.appendLine()

        userApps.sortedBy { it.packageName }.take(100).forEach { pi ->
            val label = pi.applicationInfo?.let { pm.getApplicationLabel(it) } ?: pi.packageName
            val appFlags = pi.applicationInfo?.flags ?: 0
            val isDebug = (appFlags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val allowBackup = (appFlags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) != 0

            val warnings = buildString {
                if (isDebug) append("⚠️DBG ")
                if (allowBackup) append("⚠️BCK ")
            }.trim()

            sb.appendLine("• $label (${pi.packageName}) ${if (warnings.isNotBlank()) "[$warnings]" else "✅"}")
        }

        if (userApps.size > 100) sb.appendLine("... and ${userApps.size - 100} more.")
        sb.appendLine()
        sb.appendLine("Use 'full_research' with package_name to analyze any app in depth.")
        return ToolExecutionResult(sb.toString())
    }

    private fun exportReportToHtml(context: Context, report: ResearchReport): String? {
        return try {
            val json = report.toJson()
            val sb = StringBuilder()

            sb.append("""
                <!DOCTYPE html><html><head><meta charset="utf-8">
                <title>Security Report — ${report.packageName}</title>
                <style>
                body { font-family: 'Segoe UI', Arial, sans-serif; margin: 20px; color: #111; background: #f9f9f9; }
                h1 { color: #c0392b; } h2 { color: #2c3e50; border-bottom: 2px solid #e74c3c; padding-bottom:4px; }
                .finding { border-left: 4px solid #e74c3c; padding: 12px; margin: 12px 0; background: #fff; border-radius: 4px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); }
                .finding.HIGH { border-color: #e67e22; }
                .finding.MEDIUM { border-color: #f39c12; }
                .finding.LOW { border-color: #27ae60; }
                .finding.INFO { border-color: #95a5a6; }
                .confirmed { background: #e8f8e8; }
                pre { background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 6px; overflow-x: auto; font-size:0.85em; }
                .badge { display:inline-block; padding:2px 8px; border-radius:12px; font-size:0.8em; font-weight:bold; color:#fff; }
                .badge-CRITICAL { background:#c0392b; } .badge-HIGH { background:#e67e22; }
                .badge-MEDIUM { background:#f39c12; } .badge-LOW { background:#27ae60; }
                .risk-bar { height:20px; background: linear-gradient(to right, #27ae60, #f39c12, #e74c3c); width:100%; border-radius:10px; }
                .risk-indicator { height:20px; width: 3px; background:#000; border-radius:2px; position:relative; }
                summary { cursor:pointer; font-weight:bold; }
                </style></head><body>
            """.trimIndent())

            sb.append("<h1>🔬 Security Research Report</h1>")
            sb.append("<p><strong>Package:</strong> ${report.packageName} &nbsp; <strong>Label:</strong> ${report.label}</p>")
            sb.append("<p><strong>Duration:</strong> ${report.duration}ms &nbsp; <strong>Generated:</strong> ${java.util.Date(report.analysisTimestampMs)}</p>")

            // Risk score bar
            val riskColor = when {
                report.riskScore >= 70 -> "#c0392b"
                report.riskScore >= 40 -> "#e67e22"
                else -> "#27ae60"
            }
            sb.append("<h2>Risk Score: <span style='color:$riskColor'>${report.riskScore}/100</span></h2>")
            sb.append("<p>Attack Surface: ${report.attackSurfaceSummary}</p>")
            sb.append("<p>Phases: ${report.phasesCompleted.joinToString(" → ")}</p>")

            // Summary stats
            val critical = report.findings.count { it.severity == Severity.CRITICAL }
            val high = report.findings.count { it.severity == Severity.HIGH }
            val confirmed = report.findings.count { it.verificationStatus == VerificationStatus.CONFIRMED }
            sb.append("""<table style="border-collapse:collapse;margin:12px 0">
                <tr><td style="padding:6px 16px;background:#c0392b;color:#fff;border-radius:4px">Critical: $critical</td>
                <td style="padding:6px 16px;background:#e67e22;color:#fff;border-radius:4px">High: ${report.findings.count { it.severity == Severity.HIGH }}</td>
                <td style="padding:6px 16px;background:#f39c12;color:#fff;border-radius:4px">Medium: ${report.findings.count { it.severity == Severity.MEDIUM }}</td>
                <td style="padding:6px 16px;background:#27ae60;color:#fff;border-radius:4px">Confirmed: $confirmed</td></tr></table>""")

            // Top priorities
            if (report.topPriorities.isNotEmpty()) {
                sb.append("<h2>⚡ Top Priorities</h2><ul>")
                report.topPriorities.forEach { sb.append("<li>$it</li>") }
                sb.append("</ul>")
            }

            // Findings
            sb.append("<h2>🔍 Findings (${report.findings.size})</h2>")
            report.findings.forEach { f ->
                val severityClass = f.severity.name
                val confirmedClass = if (f.verificationStatus == VerificationStatus.CONFIRMED) " confirmed" else ""
                sb.append("""<div class="finding $severityClass$confirmedClass">""")
                sb.append("""<span class="badge badge-$severityClass">${f.severity.emoji} ${f.severity.name}</span>""")
                sb.append(""" <strong>[${f.id}]</strong> ${f.title}<br>""")
                sb.append("""<small>Category: ${f.category.displayName} | CWE: ${f.category.cwe} | CVSS: ${f.cvssScore} | Status: ${f.verificationStatus.name}</small><br>""")
                sb.append("""<p>${f.description}</p>""")
                sb.append("""<p><strong>Location:</strong> <code>${f.location}</code></p>""")
                sb.append("""<p><strong>Evidence:</strong> <code>${f.evidence.take(300).replace("<","&lt;").replace(">","&gt;")}</code></p>""")

                if (f.exploitPoCs.isNotEmpty()) {
                    sb.append("<details><summary>PoC Commands (${f.exploitPoCs.size})</summary>")
                    f.exploitPoCs.forEach { poc ->
                        sb.append("<p><strong>${poc.description}</strong></p>")
                        sb.append("<pre>${poc.shellCommand.replace("<","&lt;").replace(">","&gt;")}</pre>")
                        poc.verificationResult?.let {
                            sb.append("<p><strong>Verification Result:</strong> <code>${it.take(200).replace("<","&lt;")}</code></p>")
                        }
                    }
                    sb.append("</details>")
                }

                sb.append("<details><summary>🔧 Remediation</summary>")
                sb.append("<p>${f.remediation.summary}</p>")
                if (f.remediation.codeSnippet.isNotBlank()) {
                    sb.append("<pre>${f.remediation.codeSnippet.replace("<","&lt;").replace(">","&gt;")}</pre>")
                }
                f.remediation.manifestChange?.let {
                    sb.append("<p><strong>Manifest change:</strong></p><pre>${it.replace("<","&lt;").replace(">","&gt;")}</pre>")
                }
                f.remediation.proguardRule?.let {
                    sb.append("<p><strong>ProGuard rule:</strong></p><pre>$it</pre>")
                }
                sb.append("<p><strong>References:</strong> ${f.remediation.references.joinToString(" | ") { ref -> "<a href='$ref'>$ref</a>" }}</p>")
                sb.append("</details>")
                sb.append("</div>")
            }

            // Patch bundle
            sb.append("<h2>📦 Patch Bundle</h2>")
            sb.append("<pre>${report.patchBundle.take(8000).replace("<","&lt;").replace(">","&gt;")}</pre>")
            sb.append("</body></html>")

            val docsDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
            if (docsDir != null && !docsDir.exists()) docsDir.mkdirs()
            val outFile = java.io.File(docsDir ?: context.cacheDir, "${report.packageName}_security_research.html")
            outFile.writeText(sb.toString())
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun filterByMinSeverity(
        findings: List<VulnerabilityFinding>,
        minSeverity: Severity?
    ): List<VulnerabilityFinding> {
        if (minSeverity == null) return findings
        return findings.filter { it.severity.score >= minSeverity.score }
    }

    private fun getRiskEmoji(score: Int) = when {
        score >= 80 -> "🔴 CRITICAL RISK"
        score >= 60 -> "🟠 HIGH RISK"
        score >= 40 -> "🟡 MEDIUM RISK"
        score >= 20 -> "🟢 LOW RISK"
        else -> "⚪ MINIMAL RISK"
    }

    private fun missingPkg() = ToolExecutionResult(
        "Missing required argument: package_name. Example: package_name=com.example.app",
        isError = true
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
// COMPOSITE TOOL MANAGER INTEGRATION PATCH
// ═══════════════════════════════════════════════════════════════════════════════
//
// Add to CompositeToolManager.getToolDefinitions():
// ──────────────────────────────────────────────────
//     if (context != null) {
//         addAll(AndroidSecurityResearchTool.getToolDefinitions())
//     }
//
// Add to CompositeToolManager.executeTool():
// ──────────────────────────────────────────
//     "android_security_research" -> {
//         val ctx = context ?: return missingContext()
//         val action = arguments["action"] ?: return missingArg("action")
//         AndroidSecurityResearchTool.execute(
//             context = ctx,
//             action = action,
//             args = arguments
//         )
//     }
//
// ═══════════════════════════════════════════════════════════════════════════════
