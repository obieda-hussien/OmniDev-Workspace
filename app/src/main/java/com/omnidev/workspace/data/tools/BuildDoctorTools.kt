package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.builddoctor.BuildDoctorPro

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * BuildDoctorTools — أدوات Agent لـ Build Doctor Pro (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *   - build_diagnose: تحليل output بناء + استرجاع الحلول السابقة
 *   - build_record_fix: تسجيل أن الإصلاح نجح (يحفظ الحل لإعادة استخدامه)
 *   - build_record_fail: تسجيل فشل المحاولة (يخفض ثقة الحل)
 *   - build_top_solutions: عرض أفضل الحلول المعروفة
 */
class BuildDoctorTools(private val doctor: BuildDoctorPro) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "build_diagnose",
            description = "تحليل ناتج build (stdout/stderr) + استرجاع حلول سابقة لأخطاء معروفة. " +
                "استخدم بعد كل بناء فاشل لمعرفة هل سبق رؤية الخطأ.",
            parameters = listOf(
                ToolParameter("build_output", "string", "output الـ build (stdout + stderr)"),
                ToolParameter("build_command", "string", "الأمر المُستخدم للبناء (e.g. 'gradle build')", required = false)
            )
        ),
        ToolDefinition(
            name = "build_record_fix",
            description = "تسجيل إصلاح ناجح لخطأ سبق تشخيصه. يحفظ الـ diff " +
                "لاسترجاعه عند تكرار نفس الخطأ.",
            parameters = listOf(
                ToolParameter("error_fingerprint", "string", "بصمة الخطأ من build_diagnose"),
                ToolParameter("solution_diff", "string", "الـ diff/تعديل الذي أصلح الخطأ", required = false),
                ToolParameter("explanation", "string", "شرح قصير (≤ 200 حرف)", required = false)
            )
        ),
        ToolDefinition(
            name = "build_record_fail",
            description = "تسجيل أن المحاولة فشلت في إصلاح الخطأ (يخفض ثقة الحل المخزن).",
            parameters = listOf(
                ToolParameter("error_fingerprint", "string", "بصمة الخطأ من build_diagnose")
            )
        ),
        ToolDefinition(
            name = "build_top_solutions",
            description = "عرض أفضل الحلول المعروفة في قاعدة المعرفة (مرتبة بنسبة النجاح).",
            parameters = listOf(
                ToolParameter("limit", "integer", "عدد العناصر (1-50، الافتراضي 20)", required = false)
            )
        )
    )

    /** يُرجع null إذا الأداة ليست مملوكة لهذا الـ wrapper (لتمرير fall-through). */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            when (name) {
                "build_diagnose" -> {
                    val out = args["build_output"]?.trim()
                        ?: return ToolExecutionResult("build_output مطلوب", isError = true)
                    val cmd = args["build_command"]?.take(200) ?: ""
                    val diag = doctor.diagnose(out, cmd)
                    ToolExecutionResult(buildString {
                        appendLine(diag.summary)
                        if (diag.knownSolutions.isNotEmpty()) {
                            appendLine()
                            appendLine("✅ حلول معروفة (${diag.knownSolutions.size}):")
                            for (k in diag.knownSolutions.take(5)) {
                                val confidence = computeConfidence(k.successfulFixCount, k.failedFixCount)
                                appendLine("  • [${k.category}] ${k.message.take(120)}")
                                appendLine("    ثقة: ${(confidence * 100).toInt()}% (✓${k.successfulFixCount}/✗${k.failedFixCount}) | fp=${k.fingerprint}")
                                if (k.explanation.isNotBlank()) appendLine("    📝 ${k.explanation.take(180)}")
                                if (k.solutionDiff.isNotBlank()) {
                                    appendLine("    diff:")
                                    appendLine(k.solutionDiff.take(500).prependIndent("      "))
                                }
                            }
                        }
                        if (diag.newErrors.isNotEmpty()) {
                            appendLine()
                            appendLine("🆕 أخطاء جديدة (${diag.newErrors.size}):")
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
                        ?: return ToolExecutionResult("error_fingerprint مطلوب", isError = true)
                    val diff = args["solution_diff"] ?: ""
                    val expl = args["explanation"] ?: ""
                    doctor.recordSuccessfulFix(fp, diff, expl)
                    ToolExecutionResult("✅ سُجِّل الحل لـ fp=$fp")
                }
                "build_record_fail" -> {
                    val fp = args["error_fingerprint"]?.trim()
                        ?: return ToolExecutionResult("error_fingerprint مطلوب", isError = true)
                    doctor.recordFailedFix(fp)
                    ToolExecutionResult("⚠️ سُجِّل فشل المحاولة لـ fp=$fp")
                }
                "build_top_solutions" -> {
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                    val sols = doctor.topSolutions(limit)
                    if (sols.isEmpty()) ToolExecutionResult("لا توجد حلول مسجّلة بعد.")
                    else ToolExecutionResult(buildString {
                        appendLine("📚 أفضل ${sols.size} حل:")
                        for (s in sols) {
                            val conf = computeConfidence(s.successfulFixCount, s.failedFixCount)
                            appendLine("  • [${s.category}] ${s.message.take(120)}")
                            appendLine("    ثقة ${(conf * 100).toInt()}% | تكرر ${s.occurrenceCount}× | fp=${s.fingerprint}")
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
