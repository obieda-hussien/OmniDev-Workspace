package com.omnidev.workspace.data.tools.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.ToolParameter
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject

// ════════════════════════════════════════════════════════════════════════════════
// AndroidSecurityResearchTool  v2.0
//
// Full autonomous vulnerability research pipeline — now with:
//   • Self-contained tool download (aapt2, jadx, apktool, Python) via Shizuku
//   • Binary AXML manifest decoding via aapt2 (no more garbage output)
//   • Full APK decompilation to Java source via jadx
//   • Python-based DEX scanner + secret hunter across source tree
//   • Complete 5-phase pipeline with parallel execution
//
// Zero Termux dependency — everything runs through Shizuku shell.
// ════════════════════════════════════════════════════════════════════════════════
object AndroidSecurityResearchTool {
    // Heuristic weighting tuned for triage: cap each signal family so one noisy source
    // cannot dominate the final score, then blend with overall report risk score.
    private const val HIGH_IMPACT_SIGNAL_POINTS = 8
    private const val HIGH_IMPACT_SIGNAL_MAX = 32
    private const val UNVERIFIED_SEVERE_SIGNAL_POINTS = 6
    private const val UNVERIFIED_SEVERE_SIGNAL_MAX = 24
    private const val EXPLOITABILITY_SIGNAL_POINTS = 4
    private const val EXPLOITABILITY_SIGNAL_MAX = 20
    private const val RISK_SCORE_VERY_HIGH_BONUS = 20
    private const val RISK_SCORE_ELEVATED_BONUS = 10

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "android_security_research",
            description = """
Full autonomous vulnerability research pipeline for installed Android apps.
Self-contained — downloads aapt2, jadx, apktool, and Python 3.12 on demand via Shizuku.

Actions:
  setup_tools         → Download & install all research tools. Run this first.
  tools_status        → Show which tools are installed and their sizes.

  aapt2_analyze       → Deep APK analysis via aapt2: binary manifest, permissions, strings.
                        Decodes binary AXML format — much more accurate than text grep.
  dex_scan            → Python-powered DEX scanner: AWS keys, JWTs, weak crypto, cleartext URLs.
  decompile           → Full APK decompilation to Java source via jadx.
  scan_secrets        → Secret hunter across decompiled source (run after decompile).
  quick_python        → Run a custom Python snippet against the APK. APK_PATH is pre-set.
  full_pipeline       → All phases in parallel: aapt2 + DEX scan + decompile + apktool decode + secrets.
  advanced_hunt       → Extended threat hunt: full_pipeline + static/dynamic/exploit verification + zero-day heuristics.

  full_research       → Static + manifest + native + crypto analysis (original engine).
  static_only         → Phase 1 only: DEX analysis, manifest, native libs, crypto.
  dynamic_probe       → Phase 2: Live intent fuzzing, ContentProvider probing (needs Shizuku).
  verify_exploits     → Phase 3: Execute safe PoC commands to confirm findings.
  generate_patches    → Phase 4: Kotlin/Java patches + Manifest changes + ProGuard rules.
  list_apps           → List all user apps with basic security posture.
  export_report       → Export full research report as HTML to external storage.

Output is formatted for readability. Use output_format=json for machine parsing.
            """.trimIndent(),
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "One of: setup_tools, tools_status, aapt2_analyze, dex_scan, decompile, " +
                            "scan_secrets, quick_python, full_pipeline, advanced_hunt, full_research, static_only, " +
                            "dynamic_probe, verify_exploits, generate_patches, list_apps, export_report",
                    required = true
                ),
                ToolParameter(
                    name = "package_name",
                    type = "string",
                    description = "Target app package name (e.g., 'com.whatsapp'). Required for most actions.",
                    required = false
                ),
                ToolParameter(
                    name = "python_script",
                    type = "string",
                    description = "Python script content for action=quick_python. APK_PATH variable is pre-set.",
                    required = false
                ),
                ToolParameter(
                    name = "source_dir",
                    type = "string",
                    description = "Path to decompiled source directory for action=scan_secrets. " +
                            "If omitted, uses jadx output from a previous decompile action.",
                    required = false
                ),
                ToolParameter(
                    name = "pipeline_phases",
                    type = "string",
                    description = "Comma-separated phases for full_pipeline: aapt2,dex_scan,decompile,decode_smali,secret_hunt. " +
                            "Default: all phases.",
                    required = false
                ),
                ToolParameter(
                    name = "severity_filter",
                    type = "string",
                    description = "Filter findings by minimum severity: CRITICAL, HIGH, MEDIUM, LOW, INFO.",
                    required = false
                ),
                ToolParameter(
                    name = "include_patches",
                    type = "string",
                    description = "Include full patch code in generate_patches output: 'true' (default) or 'false'.",
                    required = false
                ),
                ToolParameter(
                    name = "max_results",
                    type = "string",
                    description = "Limit number of findings (default: 50).",
                    required = false
                ),
                ToolParameter(
                    name = "output_format",
                    type = "string",
                    description = "'text' (default) or 'json' for machine-readable output.",
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
        val pkg            = args["package_name"]?.trim()
        val severityFilter = args["severity_filter"]?.uppercase()?.let {
            runCatching { Severity.valueOf(it) }.getOrNull()
        }
        val includePatches = args["include_patches"]?.lowercase() != "false"
        val maxResults     = args["max_results"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val jsonOutput     = args["output_format"]?.lowercase() == "json"

        // ── Initialise tool manager ───────────────────────────────────────────
        OmniNativeToolsManager.init(context)

        when (action.lowercase().trim()) {

            // ── Tool management ───────────────────────────────────────────────

            "setup_tools" -> {
                val progressLines = mutableListOf<String>()
                val result = VulnResearchToolchain.setupTools(context) { msg ->
                    progressLines.add(msg)
                }
                val output = buildString {
                    appendLine("═══ Tool Setup Complete ═══")
                    appendLine()
                    appendLine("Installation Results:")
                    OmniNativeToolsManager.Tool.values().forEach { tool ->
                        val status = result.optString(tool.name, "unknown")
                        appendLine("  ${tool.displayName.padEnd(15)} $status")
                    }
                    appendLine()
                    appendLine("Total installed: ${result.optString("storage_mb")} MB")
                    appendLine()
                    appendLine("All tools ready. You can now run:")
                    appendLine("  • aapt2_analyze    — Binary manifest + permissions")
                    appendLine("  • dex_scan         — Secret + crypto pattern scan")
                    appendLine("  • decompile        — Full APK → Java source")
                    appendLine("  • decode_smali     — apktool decode (smali/resources; use decode_smali in pipeline_phases)")
                    appendLine("  • full_pipeline    — Everything at once")
                    appendLine("  • advanced_hunt    — full_pipeline + dynamic verification + heuristics")
                }
                if (jsonOutput) ToolExecutionResult(result.toString(2))
                else ToolExecutionResult(output)
            }

            "tools_status" -> {
                val status = OmniNativeToolsManager.statusJson(context)
                if (jsonOutput) {
                    ToolExecutionResult(status.toString(2))
                } else {
                    val output = buildString {
                        appendLine("═══ Research Tools Status ═══")
                        appendLine()
                        OmniNativeToolsManager.Tool.values().forEach { tool ->
                            val obj       = status.optJSONObject(tool.name)
                            val installed = obj?.optBoolean("installed", false) ?: false
                            val size      = if (installed) " (${obj?.optLong("size_kb")} KB)" else ""
                            val icon      = if (installed) "✅" else "❌"
                            appendLine("  $icon ${tool.displayName.padEnd(15)} $size")
                        }
                        appendLine()
                        appendLine("Total: ${status.optString("total_size_mb")} MB")
                        appendLine()
                        val allInstalled = OmniNativeToolsManager.Tool.values().all {
                            OmniNativeToolsManager.isInstalled(context, it)
                        }
                        if (!allInstalled) {
                            appendLine("Run action='setup_tools' to install missing tools.")
                        }
                    }
                    ToolExecutionResult(output)
                }
            }

            // ── aapt2 Analysis ────────────────────────────────────────────────

            "aapt2_analyze" -> {
                val packageName = pkg ?: return@withContext missingPkg()
                val result = VulnResearchToolchain.aapt2Analysis(context, packageName)
                if (jsonOutput) ToolExecutionResult(result.toJson().toString(2))
                else ToolExecutionResult(result.toToolOutput())
            }

            // ── Python DEX Scanner ────────────────────────────────────────────

            "dex_scan" -> {
                val packageName = pkg ?: return@withContext missingPkg()
                val result = VulnResearchToolchain.pythonDexScan(context, packageName)
                if (jsonOutput) ToolExecutionResult(result.toJson().toString(2))
                else ToolExecutionResult(result.toToolOutput())
            }

            // ── jadx Decompilation ────────────────────────────────────────────

            "decompile" -> {
                val packageName = pkg ?: return@withContext missingPkg()
                val result = VulnResearchToolchain.decompileToSource(context, packageName)
                if (jsonOutput) ToolExecutionResult(result.toJson().toString(2))
                else ToolExecutionResult(result.toToolOutput())
            }

            // ── apktool Decode ────────────────────────────────────────────────

            "decode_smali" -> {
                val packageName = pkg ?: return@withContext missingPkg()
                val result = VulnResearchToolchain.decodeWithApktool(context, packageName)
                if (jsonOutput) ToolExecutionResult(result.toJson().toString(2))
                else ToolExecutionResult(result.toToolOutput())
            }

            // ── Secret Hunter ─────────────────────────────────────────────────

            "scan_secrets" -> {
                val packageName = pkg ?: return@withContext missingPkg()
                val sourceDir   = args["source_dir"]
                val result = VulnResearchToolchain.scanSecretsInSource(context, packageName, sourceDir)
                if (jsonOutput) ToolExecutionResult(result.toJson().toString(2))
                else ToolExecutionResult(result.toToolOutput())
            }

            // ── Custom Python ─────────────────────────────────────────────────

            "quick_python" -> {
                val packageName = pkg ?: return@withContext missingPkg()
                val script = args["python_script"]
                    ?: return@withContext ToolExecutionResult(
                        "Missing 'python_script' argument. Provide a Python snippet; APK_PATH is pre-set.",
                        isError = true
                    )
                val result = VulnResearchToolchain.quickPython(context, packageName, script)
                ToolExecutionResult(result.toToolOutput())
            }

            // ── Full Pipeline ─────────────────────────────────────────────────

            "full_pipeline" -> {
                val packageName = pkg ?: return@withContext missingPkg()

                val requestedPhases = args["pipeline_phases"]
                    ?.split(",")
                    ?.mapNotNull { token ->
                        runCatching {
                            VulnResearchToolchain.PipelinePhase.valueOf(token.trim().uppercase())
                        }.getOrNull()
                    }
                    ?.toSet()
                    ?: VulnResearchToolchain.PipelinePhase.values().toSet()

                val phaseLog = mutableListOf<String>()
                val report = VulnResearchToolchain.fullPipeline(
                    context  = context,
                    packageName = packageName,
                    phases   = requestedPhases,
                    onPhaseComplete = { phase, summary ->
                        phaseLog.add("[$phase] $summary")
                    }
                )

                if (jsonOutput) {
                    ToolExecutionResult(report.toString(2))
                } else {
                    val output = buildString {
                        appendLine("═══ Full Pipeline Report: $packageName ═══")
                        appendLine("Duration: ${report.optLong("duration_ms")}ms")
                        appendLine()
                        appendLine("── Phase Summary ──")
                        phaseLog.forEach { appendLine("  $it") }
                        appendLine()

                        report.keys().forEach { phase ->
                            val phaseObj = report.optJSONObject(phase) ?: return@forEach
                            if (!phaseObj.optBoolean("ok", true)) return@forEach
                            appendLine("── ${phase.uppercase()} ──")
                            appendLine(phaseObj.optString("output", "").take(3000))
                            appendLine()
                        }
                    }
                    ToolExecutionResult(output.take(14_000), truncated = output.length > 14_000)
                }
            }

            "advanced_hunt" -> {
                val packageName = pkg ?: return@withContext missingPkg()

                val phaseLog = mutableListOf<String>()
                val enhancedPipeline = VulnResearchToolchain.fullPipeline(
                    context = context,
                    packageName = packageName,
                    phases = VulnResearchToolchain.PipelinePhase.values().toSet(),
                    onPhaseComplete = { phase, summary ->
                        phaseLog.add("[$phase] $summary")
                    }
                )

                val deepReport = AndroidVulnResearchEngine.runFullResearch(
                    context = context,
                    packageName = packageName,
                    phases = AndroidVulnResearchEngine.ResearchPhase.entries.toSet()
                )

                val heuristics = buildZeroDayHeuristics(deepReport)
                if (jsonOutput) {
                    val out = JSONObject()
                    out.put("package", packageName)
                    out.put("extended_pipeline", enhancedPipeline)
                    out.put("deep_research", deepReport.toJson())
                    out.put("zero_day_heuristics", heuristics)
                    ToolExecutionResult(out.toString(2))
                } else {
                    val output = buildString {
                        appendLine("═══ Advanced Threat Hunt: $packageName ═══")
                        appendLine()
                        appendLine("── Extended Pipeline (static artifact coverage) ──")
                        phaseLog.forEach { appendLine("  $it") }
                        appendLine()
                        appendLine("── Zero-Day Heuristics (probabilistic, not guaranteed) ──")
                        appendLine("Score: ${heuristics.optInt("score")}/100")
                        appendLine("Level: ${heuristics.optString("level")}")
                        val signals = heuristics.optJSONArray("signals")
                        if (signals != null && signals.length() > 0) {
                            appendLine("Signals:")
                            for (i in 0 until signals.length()) {
                                appendLine("  • ${signals.optString(i)}")
                            }
                        }
                        appendLine()
                        appendLine("── Deep Research Summary ──")
                        appendLine(buildSummaryText(deepReport))
                        appendLine()
                        appendLine("Use action='full_research' for full finding details and patch guidance.")
                    }
                    ToolExecutionResult(output.take(14_000), truncated = output.length > 14_000)
                }
            }

            // ── Legacy Research Engine (original 4-phase pipeline) ────────────

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
                val report = AndroidVulnResearchEngine.runFullResearch(
                    context = context,
                    packageName = packageName,
                    phases = setOf(AndroidVulnResearchEngine.ResearchPhase.STATIC)
                )
                val filtered = filterByMinSeverity(report.findings, severityFilter).take(maxResults)
                val patchOutput = buildString {
                    appendLine("// PATCH BUNDLE for ${report.packageName} (${report.label})")
                    appendLine("// Risk Score: ${report.riskScore}/100 | Findings: ${filtered.size}")
                    appendLine()
                    filtered.forEach { f ->
                        appendLine("// [${f.id}] ${f.severity.emoji} ${f.title}")
                        appendLine("// Severity: ${f.severity.name} | CVSS: ${f.cvssScore} | CWE: ${f.category.cwe}")
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
                "Unknown action '$action'. Valid actions:\n" +
                "  Tool management: setup_tools, tools_status\n" +
                "  Enhanced analysis: aapt2_analyze, dex_scan, decompile, decode_smali, scan_secrets, quick_python, full_pipeline, advanced_hunt\n" +
                "  Classic pipeline: full_research, static_only, dynamic_probe, verify_exploits, generate_patches, list_apps, export_report",
                isError = true
            )
        }
    }

    private fun buildZeroDayHeuristics(report: ResearchReport): JSONObject {
        val signals = mutableListOf<String>()
        var score = 0

        val criticalOrHigh = report.findings.count { it.severity == Severity.CRITICAL || it.severity == Severity.HIGH }
        if (criticalOrHigh > 0) {
            val weighted = criticalOrHigh
                .coerceAtMost(HIGH_IMPACT_SIGNAL_MAX / HIGH_IMPACT_SIGNAL_POINTS) * HIGH_IMPACT_SIGNAL_POINTS
            score += weighted
            signals += "Multiple high-impact findings detected ($criticalOrHigh)."
        }

        val unverifiedSevere = report.findings.count {
            (it.severity == Severity.CRITICAL || it.severity == Severity.HIGH) &&
                it.verificationStatus != VerificationStatus.CONFIRMED
        }
        if (unverifiedSevere > 0) {
            val weighted = unverifiedSevere
                .coerceAtMost(UNVERIFIED_SEVERE_SIGNAL_MAX / UNVERIFIED_SEVERE_SIGNAL_POINTS) * UNVERIFIED_SEVERE_SIGNAL_POINTS
            score += weighted
            signals += "High-severity findings not fully verified yet ($unverifiedSevere)."
        }

        val exploitReady = report.findings.count { it.exploitPoCs.isNotEmpty() }
        if (exploitReady > 0) {
            val weighted = exploitReady
                .coerceAtMost(EXPLOITABILITY_SIGNAL_MAX / EXPLOITABILITY_SIGNAL_POINTS) * EXPLOITABILITY_SIGNAL_POINTS
            score += weighted
            signals += "Exploitability indicators found in ${exploitReady} finding(s)."
        }

        if (report.riskScore >= 80) {
            score += RISK_SCORE_VERY_HIGH_BONUS
            signals += "Overall risk score is very high (${report.riskScore}/100)."
        } else if (report.riskScore >= 60) {
            score += RISK_SCORE_ELEVATED_BONUS
            signals += "Overall risk score is elevated (${report.riskScore}/100)."
        }

        val capped = score.coerceAtMost(100)
        val level = when {
            capped >= 80 -> "HIGH"
            capped >= 50 -> "MEDIUM"
            else -> "LOW"
        }

        return JSONObject().apply {
            put("score", capped)
            put("level", level)
            put(
                "note",
                "Heuristic signal only. This does not prove a zero-day; manual validation and threat intelligence are still required."
            )
            put("signals", org.json.JSONArray(signals))
        }
    }

    // ─── Formatters (used by legacy pipeline) ────────────────────────────────

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
        val highs     = filtered.count { it.severity == Severity.HIGH }
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
                    poc.verificationResult?.let { sb.appendLine("│      Result: ${it.take(100)}") }
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

        if (filtered.isEmpty()) sb.appendLine("✅ No findings match the selected filter.")

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
                @Suppress("DEPRECATION") pm.getInstalledPackages(0)
            }
        } catch (e: Exception) {
            return ToolExecutionResult("Failed to list packages: ${e.message}", isError = true)
        }

        val userApps = apps.filter { pi ->
            val flags = pi.applicationInfo?.flags ?: 0
            (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
        }

        val sb = StringBuilder()
        sb.appendLine("Installed User Apps: ${userApps.size}")
        sb.appendLine()

        userApps.sortedBy { it.packageName }.take(100).forEach { pi ->
            val label     = pi.applicationInfo?.let { pm.getApplicationLabel(it) } ?: pi.packageName
            val appFlags  = pi.applicationInfo?.flags ?: 0
            val isDebug   = (appFlags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val allowBck  = (appFlags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
            val warnings  = buildString {
                if (isDebug)  append("⚠️DBG ")
                if (allowBck) append("⚠️BCK ")
            }.trim()
            sb.appendLine("• $label (${pi.packageName}) ${if (warnings.isNotBlank()) "[$warnings]" else "✅"}")
        }

        if (userApps.size > 100) sb.appendLine("... and ${userApps.size - 100} more.")
        sb.appendLine()
        sb.appendLine("Use 'full_pipeline' with package_name to run a complete analysis.")
        return ToolExecutionResult(sb.toString())
    }

    private fun exportReportToHtml(context: Context, report: ResearchReport): String? {
        return try {
            val sb = StringBuilder()
            sb.append("""<!DOCTYPE html><html><head><meta charset="utf-8">
<title>Security Report — ${report.packageName}</title>
<style>
body{font-family:'Segoe UI',Arial,sans-serif;margin:20px;color:#111;background:#f9f9f9}
h1{color:#c0392b}h2{color:#2c3e50;border-bottom:2px solid #e74c3c;padding-bottom:4px}
.finding{border-left:4px solid #e74c3c;padding:12px;margin:12px 0;background:#fff;border-radius:4px}
.finding.HIGH{border-color:#e67e22}.finding.MEDIUM{border-color:#f39c12}.finding.LOW{border-color:#27ae60}
pre{background:#1e1e1e;color:#d4d4d4;padding:12px;border-radius:6px;overflow-x:auto;font-size:.85em}
summary{cursor:pointer;font-weight:bold}
</style></head><body>""")

            sb.append("<h1>Security Research Report</h1>")
            sb.append("<p><b>Package:</b> ${report.packageName} &nbsp; <b>Label:</b> ${report.label}</p>")
            sb.append("<p><b>Risk Score:</b> ${report.riskScore}/100 &nbsp; <b>Duration:</b> ${report.duration}ms</p>")
            sb.append("<p><b>Phases:</b> ${report.phasesCompleted.joinToString(" → ")}</p>")
            sb.append("<p><b>Attack Surface:</b> ${report.attackSurfaceSummary}</p>")

            sb.append("<h2>Findings (${report.findings.size})</h2>")
            report.findings.forEach { f ->
                sb.append("""<div class="finding ${f.severity.name}">""")
                sb.append("<b>[${f.id}]</b> ${f.severity.emoji} <b>${f.title}</b><br>")
                sb.append("<small>${f.category.displayName} | ${f.category.cwe} | CVSS: ${f.cvssScore} | ${f.verificationStatus.name}</small>")
                sb.append("<p>${f.description}</p>")
                sb.append("<p><b>Location:</b> <code>${f.location}</code></p>")
                sb.append("<details><summary>Remediation</summary><pre>${f.remediation.codeSnippet.replace("<","&lt;")}</pre></details>")
                sb.append("</div>")
            }

            sb.append("</body></html>")

            val docsDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
            docsDir?.mkdirs()
            val outFile = java.io.File(docsDir ?: context.cacheDir, "${report.packageName}_security.html")
            outFile.writeText(sb.toString())
            outFile.absolutePath
        } catch (e: Exception) { null }
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
        else        -> "⚪ MINIMAL RISK"
    }

    private fun missingPkg() = ToolExecutionResult(
        "Missing required argument: package_name. Example: package_name=com.example.app",
        isError = true
    )
}
