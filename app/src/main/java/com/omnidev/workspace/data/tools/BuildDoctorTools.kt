package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.builddoctor.BuildDoctorPro

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * BuildDoctorTools — Context note Agent Context note Build Doctor Pro (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *   - build_diagnose: Context note output Context note + Context note Context note Context note
 *   - build_record_fix: Context note Context note Context note Context note (Context note Context note Context note Context note)
 *   - build_record_fail: Context note Context note Context note (Context note Context note Context note)
 *   - build_top_solutions: Context note Context note Context note Context note
 */
class BuildDoctorTools(private val doctor: BuildDoctorPro) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "build_diagnose",
            description = "Info Info build (stdout/stderr) + Info Info Info Info Info. " +
                "Info Info Info Info Info Info Info Info Info Info.",
            parameters = listOf(
                ToolParameter("build_output", "string", "output Info build (stdout + stderr)"),
                ToolParameter("build_command", "string", "Info Info Info (e.g. 'gradle build')", required = false)
            )
        ),
        ToolDefinition(
            name = "build_record_fix",
            description = "Info Info Info Info Info Info. Info Info diff " +
                "Info Info Info Info Info.",
            parameters = listOf(
                ToolParameter("error_fingerprint", "string", "Info Info Info build_diagnose"),
                ToolParameter("solution_diff", "string", "Info diff/Info Info Info Info", required = false),
                ToolParameter("explanation", "string", "Info Info (≤ 200 Info)", required = false)
            )
        ),
        ToolDefinition(
            name = "build_record_fail",
            description = "Info Info Info Info Info Info Info (Info Info Info Info).",
            parameters = listOf(
                ToolParameter("error_fingerprint", "string", "Info Info Info build_diagnose")
            )
        ),
        ToolDefinition(
            name = "build_top_solutions",
            description = "Info Info Info Info Info Info Info (Info Info Info).",
            parameters = listOf(
                ToolParameter("limit", "integer", "Info Info (1-50Info Info 20)", required = false)
            )
        )
    )

    /** Context note null Context note Context note Context note Context note Context note Context note wrapper (Context note fall-through). */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            when (name) {
                "build_diagnose" -> {
                    val out = args["build_output"]?.trim()
                        ?: return ToolExecutionResult("build_output Info", isError = true)
                    val cmd = args["build_command"]?.take(200) ?: ""
                    val diag = doctor.diagnose(out, cmd)
                    ToolExecutionResult(buildString {
                        appendLine(diag.summary)
                        if (diag.knownSolutions.isNotEmpty()) {
                            appendLine()
                            appendLine("✅ Info Info (${diag.knownSolutions.size}):")
                            for (k in diag.knownSolutions.take(5)) {
                                val confidence = computeConfidence(k.successfulFixCount, k.failedFixCount)
                                appendLine("  • [${k.category}] ${k.message.take(120)}")
                                appendLine("    Context note: ${(confidence * 100).toInt()}% (✓${k.successfulFixCount}/✗${k.failedFixCount}) | fp=${k.fingerprint}")
                                if (k.explanation.isNotBlank()) appendLine("    📝 ${k.explanation.take(180)}")
                                if (k.solutionDiff.isNotBlank()) {
                                    appendLine("    diff:")
                                    appendLine(k.solutionDiff.take(500).prependIndent("      "))
                                }
                            }
                        }
                        if (diag.newErrors.isNotEmpty()) {
                            appendLine()
                            appendLine("🆕 Info Info (${diag.newErrors.size}):")
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
                        ?: return ToolExecutionResult("error_fingerprint Info", isError = true)
                    val diff = args["solution_diff"] ?: ""
                    val expl = args["explanation"] ?: ""
                    doctor.recordSuccessfulFix(fp, diff, expl)
                    ToolExecutionResult("✅ Info Info Info fp=$fp")
                }
                "build_record_fail" -> {
                    val fp = args["error_fingerprint"]?.trim()
                        ?: return ToolExecutionResult("error_fingerprint Info", isError = true)
                    doctor.recordFailedFix(fp)
                    ToolExecutionResult("⚠️ Info Info Info Info fp=$fp")
                }
                "build_top_solutions" -> {
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                    val sols = doctor.topSolutions(limit)
                    if (sols.isEmpty()) ToolExecutionResult("Info Info Info Info Info.")
                    else ToolExecutionResult(buildString {
                        appendLine("📚 Info ${sols.size} Info:")
                        for (s in sols) {
                            val conf = computeConfidence(s.successfulFixCount, s.failedFixCount)
                            appendLine("  • [${s.category}] ${s.message.take(120)}")
                            appendLine("    Context note ${(conf * 100).toInt()}% | Context note ${s.occurrenceCount}× | fp=${s.fingerprint}")
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
