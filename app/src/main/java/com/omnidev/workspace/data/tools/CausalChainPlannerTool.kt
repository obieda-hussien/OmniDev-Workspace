package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.brain.CausalChainPlanner

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * CausalChainPlannerTool — أداة Agent للتخطيط السببي متعدد الخطوات
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * تعرض قدرات [CausalChainPlanner] للوكيل عبر أربع أدوات:
 *
 *   - **causal_plan_analyze**: يُحلّل خطوات مخطّطة ويُنشئ خريطة سببية كاملة
 *     بالتعارضات والتحذيرات.
 *
 *   - **causal_plan_simulate**: يُحاكي تنفيذ الخطوات افتراضياً دون أي تأثير حقيقي
 *     على الجهاز — يكتشف الخطوات التي ستفشل قبل التنفيذ الفعلي.
 *
 *   - **causal_plan_what_if**: يُجيب على "ماذا يحدث لو أضفنا أو حذفنا خطوة؟"
 *     ويُظهر الفرق في مستوى الخطر والتعارضات.
 *
 *   - **causal_plan_clear**: يُفرغ مخزن الخطط المؤقتة (session cache).
 *
 * ## Mobile-First:
 * - لا LLM إضافي — تحليل rule-based بالكامل (< 5ms لـ 20 خطوة)
 * - لا قاعدة بيانات — ذاكرة الجلسة فقط، حد أقصى 10 خطط مؤقتة
 * - آمن لجميع الـ Tiers (Lite → OEM) لأنه قراءة/تحليل فقط
 *
 * ## طريقة الاستخدام المثالية:
 * 1. الوكيل يُنشئ قائمة الخطوات المخطّطة
 * 2. يستدعي `causal_plan_analyze` أولاً
 * 3. إذا وجد تعارضات حرجة → يُعدّل الخطة
 * 4. يستدعي `causal_plan_simulate` للتحقق النهائي
 * 5. يُنفّذ الخطوات مع rollback group واحد
 */
class CausalChainPlannerTool(
    private val planner: CausalChainPlanner = CausalChainPlanner()
) {

    /** مخزن الخطط المؤقتة (session-scoped, max 10). */
    private val planCache = mutableMapOf<String, CausalChainPlanner.CausalGraph>()
    private val maxCacheSize = 10

    // ──────────────────────────────────────────────────────────────────────────
    // Tool Definitions
    // ──────────────────────────────────────────────────────────────────────────

    fun getDefinitions(): List<ToolDefinition> = listOf(

        ToolDefinition(
            name = "causal_plan_analyze",
            description = """تحليل خطة متعددة الخطوات وبناء خريطة سببية (Causal Graph) كاملة.
يكتشف التعارضات المنطقية قبل التنفيذ مثل:
- قراءة ملف محذوف (Read-After-Delete)
- تعديل ملف بعد حذفه (Modify-After-Delete)
- إنشاء نفس الملف مرتين (Double-Create)
- عمل ضائع (تعديل ثم حذف فوراً)
- أوامر ذات خطر حرج (rm -rf, git reset --hard)

استخدم قبل تنفيذ أي سلسلة عمليات معقدة لتجنب الكوارث.
مثال steps: "create_file|path=/src/A.kt,patch_file_content|path=/src/A.kt,delete_file|path=/src/A.kt"
""",
            parameters = listOf(
                ToolParameter(
                    name = "steps",
                    type = "string",
                    description = """قائمة الخطوات المخطّطة بالتنسيق:
"toolName|param1=val1&param2=val2,toolName2|param1=val1"
مثال: "create_file|path=/src/Main.kt,patch_file_content|path=/src/Main.kt,run_terminal|command=rm -rf /tmp"
يمكن الفصل بفاصلة (,) أو سطر جديد.""",
                    required = true
                ),
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "معرف الخطة لحفظها مؤقتاً (للاستخدام لاحقاً في simulate/what_if). اختياري.",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "causal_plan_simulate",
            description = """محاكاة افتراضية لتنفيذ خطة دون أي تأثير حقيقي على الجهاز.
يبني "نظام ملفات افتراضي" ويُتحقق من كل خطوة ضده.
يُخبرك: أي خطوة ستفشل، ولماذا، وما هي حالة النظام بعدها.

استخدم بعد causal_plan_analyze للتحقق النهائي قبل التنفيذ.""",
            parameters = listOf(
                ToolParameter(
                    name = "steps",
                    type = "string",
                    description = "نفس تنسيق causal_plan_analyze. يمكن تركه فارغاً إذا حددت plan_id.",
                    required = false
                ),
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "استخدم خطة محفوظة مسبقاً بـ causal_plan_analyze.",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "causal_plan_what_if",
            description = """تحليل What-If: "ماذا يحدث لو أضفنا أو حذفنا خطوة؟"
يُظهر الفرق في مستوى الخطر والتعارضات والنجاح/الفشل بين الخطة الأصلية والمُعدَّلة.
مفيد جداً قبل اتخاذ قرار بتغيير ترتيب الخطوات.""",
            parameters = listOf(
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "معرف الخطة المحفوظة (من causal_plan_analyze).",
                    required = true
                ),
                ToolParameter(
                    name = "insert_step",
                    type = "string",
                    description = "خطوة جديدة بتنسيق 'toolName|param1=val1&param2=val2'. اختياري.",
                    required = false
                ),
                ToolParameter(
                    name = "remove_step_index",
                    type = "string",
                    description = "فهرس الخطوة المراد حذفها (0-based). اختياري.",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "causal_plan_clear",
            description = "إفراغ مخزن الخطط المؤقتة. استخدم في بداية مهمة جديدة.",
            parameters = listOf(
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "معرف خطة بعينها لحذفها. إذا لم يُحدد، يُفرغ الكل.",
                    required = false
                )
            )
        )
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Execution
    // ──────────────────────────────────────────────────────────────────────────

    /** يُرجع null إذا الأداة ليست مملوكة لهذا الـ wrapper. */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            when (name) {
                "causal_plan_analyze"  -> doAnalyze(args)
                "causal_plan_simulate" -> doSimulate(args)
                "causal_plan_what_if"  -> doWhatIf(args)
                "causal_plan_clear"    -> doClear(args)
                else -> ToolExecutionResult("Unknown causal tool: $name", isError = true)
            }
        } catch (t: Throwable) {
            ToolExecutionResult("CausalChainPlanner error: ${t.message}", isError = true)
        }
    }

    fun handles(name: String): Boolean = name in HANDLED

    /**
     * يُرجع نص حقن Prompt لآخر خطة مؤقتة في الـ cache.
     * يُستخدم من SmartLearningBridge لإثراء System Prompt بالتحذيرات السببية.
     * يُرجع null إذا كان الـ cache فارغاً أو لا يوجد تحذيرات تستحق الحقن.
     */
    fun getLastPlanInjection(maxChars: Int = 500): String? {
        val lastGraph = planCache.values.lastOrNull() ?: return null
        val injection = planner.buildPromptInjection(lastGraph, maxChars)
        return injection.ifBlank { null }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun doAnalyze(args: Map<String, String>): ToolExecutionResult {
        val stepsRaw = args["steps"]?.trim()
            ?: return ToolExecutionResult("steps مطلوبة", isError = true)

        val steps = parseSteps(stepsRaw)
        if (steps.isEmpty()) return ToolExecutionResult(
            "لم يتم التعرف على أي خطوة صحيحة في المدخلات.", isError = true
        )

        val graph = planner.buildChain(steps)

        // حفظ في الـ cache إذا طُلب
        val planId = args["plan_id"]?.trim()
        if (!planId.isNullOrBlank()) {
            // إزالة أقدم عنصر إذا امتلأ الـ cache
            if (planCache.size >= maxCacheSize) {
                planCache.keys.firstOrNull()?.let { planCache.remove(it) }
            }
            planCache[planId] = graph
        }

        return ToolExecutionResult(buildString {
            appendLine("🗺️ خريطة سببية لـ ${graph.nodes.size} خطوة:")
            appendLine()

            // ملخص الخطوات
            appendLine("📋 الخطوات:")
            for (node in graph.nodes) {
                val icon = when (node.riskLevel) {
                    CausalChainPlanner.RiskLevel.LOW      -> "🟢"
                    CausalChainPlanner.RiskLevel.MEDIUM   -> "🟡"
                    CausalChainPlanner.RiskLevel.HIGH     -> "🔴"
                    CausalChainPlanner.RiskLevel.CRITICAL -> "💥"
                }
                appendLine("  ${node.stepIndex}. $icon ${node.humanSummary}")
            }
            appendLine()

            // التأثيرات المجمّعة
            val allEffects = graph.nodes.flatMap { it.effects }
            val deletedPaths = allEffects
                .filter { it.type == CausalChainPlanner.EffectType.DELETE && it.targetPath != null }
                .mapNotNull { it.targetPath }.distinct()
            val createdPaths = allEffects
                .filter { it.type == CausalChainPlanner.EffectType.CREATE && it.targetPath != null }
                .mapNotNull { it.targetPath }.distinct()
            val modifiedPaths = allEffects
                .filter { it.type == CausalChainPlanner.EffectType.MODIFY && it.targetPath != null }
                .mapNotNull { it.targetPath }.distinct()

            if (deletedPaths.isNotEmpty()) appendLine("🗑️ سيُحذف: ${deletedPaths.take(5).joinToString(", ")}")
            if (createdPaths.isNotEmpty()) appendLine("📄 سيُنشأ: ${createdPaths.take(5).joinToString(", ")}")
            if (modifiedPaths.isNotEmpty()) appendLine("✏️ سيُعدَّل: ${modifiedPaths.take(5).joinToString(", ")}")
            appendLine()

            // مستوى الخطر الكلي
            appendLine("⚠️ مستوى الخطر الأعلى: ${graph.highestRisk.label()}")
            appendLine()

            // التعارضات
            if (graph.conflicts.isEmpty()) {
                appendLine("✅ لا توجد تعارضات — الخطة آمنة.")
            } else {
                val fatal = graph.conflicts.filter { it.isFatal }
                val warnings = graph.conflicts.filter { !it.isFatal }
                if (fatal.isNotEmpty()) {
                    appendLine("❌ تعارضات حرجة (${fatal.size}):")
                    for (c in fatal) appendLine("  • ${c.message}")
                    appendLine()
                }
                if (warnings.isNotEmpty()) {
                    appendLine("⚠️ تحذيرات (${warnings.size}):")
                    for (c in warnings) appendLine("  • ${c.message}")
                }
            }

            if (!planId.isNullOrBlank()) appendLine("\n💾 حُفظت الخطة كـ '$planId' للاستخدام في simulate/what_if.")
        })
    }

    private fun doSimulate(args: Map<String, String>): ToolExecutionResult {
        val graph = resolveGraph(args)
            ?: return ToolExecutionResult(
                "يجب تحديد 'steps' أو 'plan_id' خطة محفوظة مسبقاً.", isError = true
            )

        val result = planner.simulate(graph)
        return ToolExecutionResult(buildString {
            appendLine("🎬 نتيجة المحاكاة الافتراضية:")
            appendLine()
            for (step in result.steps) {
                val status = if (step.wouldSucceed) "✅" else "❌"
                val risk = when (step.riskLevel) {
                    CausalChainPlanner.RiskLevel.LOW      -> ""
                    CausalChainPlanner.RiskLevel.MEDIUM   -> " [متوسط]"
                    CausalChainPlanner.RiskLevel.HIGH     -> " [⚠️ مرتفع]"
                    CausalChainPlanner.RiskLevel.CRITICAL -> " [💥 حرج]"
                }
                appendLine("  ${step.stepIndex}. $status${risk} ${step.humanSummary}")
                if (!step.wouldSucceed && step.failReason != null) {
                    appendLine("       💔 السبب: ${step.failReason}")
                }
            }
            appendLine()
            if (result.overallSuccess) {
                appendLine("✅ المحاكاة نجحت — جميع الخطوات قابلة للتنفيذ.")
            } else {
                appendLine("❌ المحاكاة اكتشفت فشلاً في الخطوة ${result.firstFailureIndex}.")
                appendLine("   📌 يُنصح بتعديل الخطة أو استخدام causal_plan_what_if.")
            }
            if (result.warningMessages.isNotEmpty()) {
                appendLine()
                appendLine("⚠️ تحذيرات إضافية:")
                for (w in result.warningMessages) appendLine("  • $w")
            }
        })
    }

    private fun doWhatIf(args: Map<String, String>): ToolExecutionResult {
        val planId = args["plan_id"]?.trim()
            ?: return ToolExecutionResult("plan_id مطلوب", isError = true)
        val baseline = planCache[planId]
            ?: return ToolExecutionResult(
                "لم يُعثر على خطة '$planId'. استخدم causal_plan_analyze أولاً.", isError = true
            )

        val insertRaw = args["insert_step"]?.trim()
        val removeIdx = args["remove_step_index"]?.toIntOrNull()

        val insertStep = if (!insertRaw.isNullOrBlank()) {
            parseSteps(insertRaw).firstOrNull()
        } else null

        if (insertStep == null && removeIdx == null) {
            return ToolExecutionResult(
                "يجب تحديد insert_step أو remove_step_index.", isError = true
            )
        }

        val diff = planner.whatIf(baseline, insertStep, removeIdx)
        return ToolExecutionResult(diff)
    }

    private fun doClear(args: Map<String, String>): ToolExecutionResult {
        val planId = args["plan_id"]?.trim()
        return if (planId.isNullOrBlank()) {
            val count = planCache.size
            planCache.clear()
            ToolExecutionResult("✅ تم حذف $count خطة مؤقتة من الذاكرة.")
        } else {
            val existed = planCache.remove(planId) != null
            if (existed) ToolExecutionResult("✅ تم حذف الخطة '$planId'.")
            else ToolExecutionResult("⚠️ لم تُعثر على خطة بمعرف '$planId'.")
        }
    }

    /**
     * يُحلّل نص الخطوات إلى قائمة (toolName → parameters).
     *
     * التنسيق المقبول:
     *   "toolName|param1=val1&param2=val2,toolName2|param1=val1"
     * أو سطر جديد بدل الفاصلة.
     */
    private fun parseSteps(raw: String): List<Pair<String, Map<String, String>>> {
        return raw
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val pipeIdx = entry.indexOf('|')
                if (pipeIdx < 0) {
                    // اسم الأداة فقط بدون معاملات
                    entry.trim() to emptyMap<String, String>()
                } else {
                    val toolName = entry.substring(0, pipeIdx).trim()
                    val paramsRaw = entry.substring(pipeIdx + 1)
                    val params = paramsRaw
                        .split('&')
                        .mapNotNull { kv ->
                            val eqIdx = kv.indexOf('=')
                            if (eqIdx < 0) null
                            else kv.substring(0, eqIdx).trim() to kv.substring(eqIdx + 1).trim()
                        }.toMap()
                    toolName to params
                }
            }
    }

    /** يجلب الـ CausalGraph من الـ cache أو يبنيه من steps. */
    private fun resolveGraph(args: Map<String, String>): CausalChainPlanner.CausalGraph? {
        val planId = args["plan_id"]?.trim()
        if (!planId.isNullOrBlank() && planCache.containsKey(planId)) {
            return planCache[planId]
        }
        val stepsRaw = args["steps"]?.trim() ?: return null
        val steps = parseSteps(stepsRaw)
        if (steps.isEmpty()) return null
        return planner.buildChain(steps)
    }

    companion object {
        val HANDLED = setOf(
            "causal_plan_analyze",
            "causal_plan_simulate",
            "causal_plan_what_if",
            "causal_plan_clear"
        )
    }
}
