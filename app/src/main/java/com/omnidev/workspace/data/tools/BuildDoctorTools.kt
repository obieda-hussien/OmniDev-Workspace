package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.builddoctor.BuildDoctorPro

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * BuildDoctorTools — [Localized] Agent [Localized] Build Doctor Pro (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *   - build_diagnose: [Localized] output [Localized] + [Localized] [Localized] [Localized]
 *   - build_record_fix: [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized] [Localized])
 *   - build_record_fail: [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized])
 *   - build_top_solutions: [Localized] [Localized] [Localized] [Localized]
 */
class BuildDoctorTools(private val doctor: BuildDoctorPro) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "build_diagnose",
            description = "[Localized] [Localized] build (stdout/stderr) + [Localized] [Localized] [Localized] [Localized] [Localized]. " +
                "[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].",
            parameters = listOf(
                ToolParameter("build_output", "string", "output [Localized] build (stdout + stderr)"),
                ToolParameter("build_command", "string", "[Localized] [Localized] [Localized] (e.g. 'gradle build')", required = false)
            )
        ),
        ToolDefinition(
            name = "build_record_fix",
            description = "[Localized] [Localized] [Localized] [Localized] [Localized] [Localized]. [Localized] [Localized] diff " +
                "[Localized] [Localized] [Localized] [Localized] [Localized].",
            parameters = listOf(
                ToolParameter("error_fingerprint", "string", "[Localized] [Localized] [Localized] build_diagnose"),
                ToolParameter("solution_diff", "string", "[Localized] diff/[Localized] [Localized] [Localized] [Localized]", required = false),
                ToolParameter("explanation", "string", "[Localized] [Localized] (≤ 200 [Localized])", required = false)
            )
        ),
        ToolDefinition(
            name = "build_record_fail",
            description = "[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized] [Localized]).",
            parameters = listOf(
                ToolParameter("error_fingerprint", "string", "[Localized] [Localized] [Localized] build_diagnose")
            )
        ),
        ToolDefinition(
            name = "build_top_solutions",
            description = "[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized]).",
            parameters = listOf(
                ToolParameter("limit", "integer", "[Localized] [Localized] (1-50[Localized] [Localized] 20)", required = false)
            )
        )
    )

    /** [Localized] null [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] wrapper ([Localized] fall-through). */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            when (name) {
                "build_diagnose" -> {
                    val out = args["build_output"]?.trim()
                        ?: return ToolExecutionResult("build_output [Localized]", isError = true)
                    val cmd = args["build_command"]?.take(200) ?: ""
                    val diag = doctor.diagnose(out, cmd)
                    ToolExecutionResult(buildString {
                        appendLine(diag.summary)
                        if (diag.knownSolutions.isNotEmpty()) {
                            appendLine()
                            appendLine("✅ [Localized] [Localized] (${diag.knownSolutions.size}):")
                            for (k in diag.knownSolutions.take(5)) {
                                val confidence = computeConfidence(k.successfulFixCount, k.failedFixCount)
                                appendLine("  • [${k.category}] ${k.message.take(120)}")
                                appendLine("    [Localized]: ${(confidence * 100).toInt()}% (✓${k.successfulFixCount}/✗${k.failedFixCount}) | fp=${k.fingerprint}")
                                if (k.explanation.isNotBlank()) appendLine("    📝 ${k.explanation.take(180)}")
                                if (k.solutionDiff.isNotBlank()) {
                                    appendLine("    diff:")
                                    appendLine(k.solutionDiff.take(500).prependIndent("      "))
                                }
                            }
                        }
                        if (diag.newErrors.isNotEmpty()) {
                            appendLine()
                            appendLine("🆕 [Localized] [Localized] (${diag.newErrors.size}):")
                            for (e in diag.newErrors.take(10)) {
                                appendLine("  • [${e.category}] ${e.message.take(150)}")
                                if (e.filePath.isNotBlank())
                                    appendLine("    📍 ${e.filePath}:${e.lineNumber} | fp=${e.fingerprint}")
                            }
                        }
                    })
                }
                "build_record_fix" -> {
                    val fp = args["error_fingerprint"]?.trim()
                        ?: return ToolExecutionResult("error_fingerprint [Localized]", isError = true)
                    val diff = args["solution_diff"] ?: ""
                    val expl = args["explanation"] ?: ""
                    doctor.recordSuccessfulFix(fp, diff, expl)
                    ToolExecutionResult("✅ [Localized] [Localized] [Localized] fp=$fp")
                }
                "build_record_fail" -> {
                    val fp = args["error_fingerprint"]?.trim()
                        ?: return ToolExecutionResult("error_fingerprint [Localized]", isError = true)
                    doctor.recordFailedFix(fp)
                    ToolExecutionResult("⚠️ [Localized] [Localized] [Localized] [Localized] fp=$fp")
                }
                "build_top_solutions" -> {
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                    val sols = doctor.topSolutions(limit)
                    if (sols.isEmpty()) ToolExecutionResult("[Localized] [Localized] [Localized] [Localized] [Localized].")
                    else ToolExecutionResult(buildString {
                        appendLine("📚 [Localized] ${sols.size} [Localized]:")
                        for (s in sols) {
                            val conf = computeConfidence(s.successfulFixCount, s.failedFixCount)
                            appendLine("  • [${s.category}] ${s.message.take(120)}")
                            appendLine("    [Localized] ${(conf * 100).toInt()}% | [Localized] ${s.occurrenceCount}× | fp=${s.fingerprint}")
                            if (s.explanation.isNotBlank()) appendLine("    📝 ${s.explanation.take(160)}")
                        }
                    })
                }
                else -> ToolExecutionResult("Unknown tool: $name", isError = true)
            }
        } catch (t: Throwable) {
            ToolExecutionResult("BuildDoctor tool error: ${t.message}", isError = true)
        }
    }

    private fun computeConfidence(success: Int, fail: Int): Float {
        val total = success + fail
        if (total == 0) return 0.5f
        return success.toFloat() / total.toFloat()
    }

    fun handles(name: String): Boolean = name in HANDLED

    companion object {
        val HANDLED = setOf(
            "build_diagnose",
            "build_record_fix",
            "build_record_fail",
            "build_top_solutions"
        )
    }
}
