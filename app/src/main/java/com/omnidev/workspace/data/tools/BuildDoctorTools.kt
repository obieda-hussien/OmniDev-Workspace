package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.builddoctor.BuildDoctorPro

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * BuildDoctorTools — System awareness note Agent System awareness note Build Doctor Pro (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *   - build_diagnose: System awareness note output System awareness note + System awareness note System awareness note System awareness note
 *   - build_record_fix: System awareness note System awareness note System awareness note System awareness note (System awareness note System awareness note System awareness note System awareness note)
 *   - build_record_fail: System awareness note System awareness note System awareness note (System awareness note System awareness note System awareness note)
 *   - build_top_solutions: System awareness note System awareness note System awareness note System awareness note
 */
class BuildDoctorTools(private val doctor: BuildDoctorPro) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "build_diagnose",
            description = "System awareness note System awareness note build (stdout/stderr) + System awareness note System awareness note System awareness note System awareness note System awareness note. " +
                "System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.",
            parameters = listOf(
                ToolParameter("build_output", "string", "output System awareness note build (stdout + stderr)"),
                ToolParameter("build_command", "string", "System awareness note System awareness note System awareness note (e.g. 'gradle build')", required = false)
            )
        ),
        ToolDefinition(
            name = "build_record_fix",
            description = "System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note. System awareness note System awareness note diff " +
                "System awareness note System awareness note System awareness note System awareness note System awareness note.",
            parameters = listOf(
                ToolParameter("error_fingerprint", "string", "System awareness note System awareness note System awareness note build_diagnose"),
                ToolParameter("solution_diff", "string", "System awareness note diff/System awareness note System awareness note System awareness note System awareness note", required = false),
                ToolParameter("explanation", "string", "System awareness note System awareness note (≤ 200 System awareness note)", required = false)
            )
        ),
        ToolDefinition(
            name = "build_record_fail",
            description = "System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note (System awareness note System awareness note System awareness note System awareness note).",
            parameters = listOf(
                ToolParameter("error_fingerprint", "string", "System awareness note System awareness note System awareness note build_diagnose")
            )
        ),
        ToolDefinition(
            name = "build_top_solutions",
            description = "System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note (System awareness note System awareness note System awareness note).",
            parameters = listOf(
                ToolParameter("limit", "integer", "System awareness note System awareness note (1-50System awareness note System awareness note 20)", required = false)
            )
        )
    )

    /** System awareness note null System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note wrapper (System awareness note fall-through). */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            when (name) {
                "build_diagnose" -> {
                    val out = args["build_output"]?.trim()
                        ?: return ToolExecutionResult("build_output System awareness note", isError = true)
                    val cmd = args["build_command"]?.take(200) ?: ""
                    val diag = doctor.diagnose(out, cmd)
                    ToolExecutionResult(buildString {
                        appendLine(diag.summary)
                        if (diag.knownSolutions.isNotEmpty()) {
                            appendLine()
                            appendLine("✅ System awareness note System awareness note (${diag.knownSolutions.size}):")
                            for (k in diag.knownSolutions.take(5)) {
                                val confidence = computeConfidence(k.successfulFixCount, k.failedFixCount)
                                appendLine("  • [${k.category}] ${k.message.take(120)}")
                                appendLine("    System awareness note: ${(confidence * 100).toInt()}% (✓${k.successfulFixCount}/✗${k.failedFixCount}) | fp=${k.fingerprint}")
                                if (k.explanation.isNotBlank()) appendLine("    📝 ${k.explanation.take(180)}")
                                if (k.solutionDiff.isNotBlank()) {
                                    appendLine("    diff:")
                                    appendLine(k.solutionDiff.take(500).prependIndent("      "))
                                }
                            }
                        }
                        if (diag.newErrors.isNotEmpty()) {
                            appendLine()
                            appendLine("🆕 System awareness note System awareness note (${diag.newErrors.size}):")
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
                        ?: return ToolExecutionResult("error_fingerprint System awareness note", isError = true)
                    val diff = args["solution_diff"] ?: ""
                    val expl = args["explanation"] ?: ""
                    doctor.recordSuccessfulFix(fp, diff, expl)
                    ToolExecutionResult("✅ System awareness note System awareness note System awareness note fp=$fp")
                }
                "build_record_fail" -> {
                    val fp = args["error_fingerprint"]?.trim()
                        ?: return ToolExecutionResult("error_fingerprint System awareness note", isError = true)
                    doctor.recordFailedFix(fp)
                    ToolExecutionResult("⚠️ System awareness note System awareness note System awareness note System awareness note fp=$fp")
                }
                "build_top_solutions" -> {
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                    val sols = doctor.topSolutions(limit)
                    if (sols.isEmpty()) ToolExecutionResult("System awareness note System awareness note System awareness note System awareness note System awareness note.")
                    else ToolExecutionResult(buildString {
                        appendLine("📚 System awareness note ${sols.size} System awareness note:")
                        for (s in sols) {
                            val conf = computeConfidence(s.successfulFixCount, s.failedFixCount)
                            appendLine("  • [${s.category}] ${s.message.take(120)}")
                            appendLine("    System awareness note ${(conf * 100).toInt()}% | System awareness note ${s.occurrenceCount}× | fp=${s.fingerprint}")
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
