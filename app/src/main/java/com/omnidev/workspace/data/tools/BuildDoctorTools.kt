package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.builddoctor.BuildDoctorPro

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * BuildDoctorTools — Agent tools for Build Doctor Pro (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *   - build_diagnose: Analyze build output and retrieve previous solutions
 *   - build_record_fix: Record a successful fix for reuse
 *   - build_record_fail: Record a failed attempt and reduce solution confidence
 *   - build_top_solutions: List the best known solutions
 */
class BuildDoctorTools(private val doctor: BuildDoctorPro) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "build_diagnose",
            description = "Analyze build output (stdout/stderr) and retrieve previous solutions for known errors. " +
                "Use after a failed build to check whether the error has been seen before.",
            parameters = listOf(
                ToolParameter("build_output", "string", "Build output (stdout + stderr)"),
                ToolParameter("build_command", "string", "Build command (e.g. 'gradle build')", required = false)
            )
        ),
        ToolDefinition(
            name = "build_record_fix",
            description = "Record a successful fix for a diagnosed error. Saves the diff " +
                "for reuse when the error recurs.",
            parameters = listOf(
                ToolParameter("error_fingerprint", "string", "Error fingerprint from build_diagnose"),
                ToolParameter("solution_diff", "string", "Diff or change that fixed the error", required = false),
                ToolParameter("explanation", "string", "Short explanation (up to 200 characters)", required = false)
            )
        ),
        ToolDefinition(
            name = "build_record_fail",
            description = "Record a failed fix attempt and reduce confidence in the stored solution.",
            parameters = listOf(
                ToolParameter("error_fingerprint", "string", "Error fingerprint from build_diagnose")
            )
        ),
        ToolDefinition(
            name = "build_top_solutions",
            description = "List the best known knowledge-base solutions, ordered by success rate.",
            parameters = listOf(
                ToolParameter("limit", "integer", "Number of items (1-50; default: 20)", required = false)
            )
        )
    )

    /** Returns null for tools not handled by this wrapper, allowing fall-through. */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            when (name) {
                "build_diagnose" -> {
                    val out = args["build_output"]?.trim()
                        ?: return ToolExecutionResult("build_output is required", isError = true)
                    val cmd = args["build_command"]?.take(200) ?: ""
                    val diag = doctor.diagnose(out, cmd)
                    ToolExecutionResult(buildString {
                        appendLine(diag.summary)
                        if (diag.knownSolutions.isNotEmpty()) {
                            appendLine()
                            appendLine("✅ Known solutions (${diag.knownSolutions.size}):")
                            for (k in diag.knownSolutions.take(5)) {
                                val confidence = computeConfidence(k.successfulFixCount, k.failedFixCount)
                                appendLine("  • [${k.category}] ${k.message.take(120)}")
                                appendLine("    Confidence: ${(confidence * 100).toInt()}% (✓${k.successfulFixCount}/✗${k.failedFixCount}) | fp=${k.fingerprint}")
                                if (k.explanation.isNotBlank()) appendLine("    📝 ${k.explanation.take(180)}")
                                if (k.solutionDiff.isNotBlank()) {
                                    appendLine("    diff:")
                                    appendLine(k.solutionDiff.take(500).prependIndent("      "))
                                }
                            }
                        }
                        if (diag.newErrors.isNotEmpty()) {
                            appendLine()
                            appendLine("🆕 New errors (${diag.newErrors.size}):")
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
                        ?: return ToolExecutionResult("error_fingerprint is required", isError = true)
                    val diff = args["solution_diff"] ?: ""
                    val expl = args["explanation"] ?: ""
                    doctor.recordSuccessfulFix(fp, diff, expl)
                    ToolExecutionResult("✅ Recorded solution for fp=$fp")
                }
                "build_record_fail" -> {
                    val fp = args["error_fingerprint"]?.trim()
                        ?: return ToolExecutionResult("error_fingerprint is required", isError = true)
                    doctor.recordFailedFix(fp)
                    ToolExecutionResult("⚠️ Recorded failed attempt for fp=$fp")
                }
                "build_top_solutions" -> {
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                    val sols = doctor.topSolutions(limit)
                    if (sols.isEmpty()) ToolExecutionResult("No solutions have been recorded yet.")
                    else ToolExecutionResult(buildString {
                        appendLine("📚 Top ${sols.size} solutions:")
                        for (s in sols) {
                            val conf = computeConfidence(s.successfulFixCount, s.failedFixCount)
                            appendLine("  • [${s.category}] ${s.message.take(120)}")
                            appendLine("    Confidence ${(conf * 100).toInt()}% | Occurrences: ${s.occurrenceCount}× | fp=${s.fingerprint}")
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
