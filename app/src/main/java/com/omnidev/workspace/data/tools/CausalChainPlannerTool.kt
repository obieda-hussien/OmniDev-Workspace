package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.brain.CausalChainPlanner

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * CausalChainPlannerTool — [Localized] Agent [Localized] [Localized] [Localized] [Localized]
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * [Localized] [Localized] [CausalChainPlanner] [Localized] [Localized] [Localized] [Localized]:
 *
 *   - **causal_plan_analyze**: [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
 *     [Localized] [Localized].
 *
 *   - **causal_plan_simulate**: [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
 *     [Localized] [Localized] — [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
 *
 *   - **causal_plan_what_if**: [Localized] [Localized] "[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]"
 *     [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
 *
 *   - **causal_plan_clear**: [Localized] [Localized] [Localized] [Localized] (session cache).
 *
 * ## Mobile-First:
 * - [Localized] LLM [Localized] — [Localized] rule-based [Localized] (< 5ms [Localized] 20 [Localized])
 * - [Localized] [Localized] [Localized] — [Localized] [Localized] [Localized] [Localized] [Localized] 10 [Localized] [Localized]
 * - [Localized] [Localized] [Localized] Tiers (Lite → OEM) [Localized] [Localized]/[Localized] [Localized]
 *
 * ## [Localized] [Localized] [Localized]:
 * 1. [Localized] [Localized] [Localized] [Localized] [Localized]
 * 2. [Localized] `causal_plan_analyze` [Localized]
 * 3. [Localized] [Localized] [Localized] [Localized] → [Localized] [Localized]
 * 4. [Localized] `causal_plan_simulate` [Localized] [Localized]
 * 5. [Localized] [Localized] [Localized] rollback group [Localized]
 */
class CausalChainPlannerTool(
    private val planner: CausalChainPlanner = CausalChainPlanner()
) {

    /** [Localized] [Localized] [Localized] (session-scoped, max 10). */
    private val planCache = mutableMapOf<String, CausalChainPlanner.CausalGraph>()
    private val maxCacheSize = 10

    // ──────────────────────────────────────────────────────────────────────────
    // Tool Definitions
    // ──────────────────────────────────────────────────────────────────────────

    fun getDefinitions(): List<ToolDefinition> = listOf(

        ToolDefinition(
            name = "causal_plan_analyze",
            description = """[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] (Causal Graph) [Localized].
[Localized] [Localized] [Localized] [Localized] [Localized] [Localized]:
- [Localized] [Localized] [Localized] (Read-After-Delete)
- [Localized] [Localized] [Localized] [Localized] (Modify-After-Delete)
- [Localized] [Localized] [Localized] [Localized] (Double-Create)
- [Localized] [Localized] ([Localized] [Localized] [Localized] [Localized])
- [Localized] [Localized] [Localized] [Localized] (rm -rf, git reset --hard)

[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
[Localized] steps: "create_file|path=/src/A.kt,patch_file_content|path=/src/A.kt,delete_file|path=/src/A.kt"
""",
            parameters = listOf(
                ToolParameter(
                    name = "steps",
                    type = "string",
                    description = """[Localized] [Localized] [Localized] [Localized]:
"toolName|param1=val1&param2=val2,toolName2|param1=val1"
[Localized]: "create_file|path=/src/Main.kt,patch_file_content|path=/src/Main.kt,run_terminal|command=rm -rf /tmp"
[Localized] [Localized] [Localized] (,) [Localized] [Localized] [Localized].""",
                    required = true
                ),
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "[Localized] [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized] simulate/what_if). [Localized].",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "causal_plan_simulate",
            description = """[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
[Localized] "[Localized] [Localized] [Localized]" [Localized] [Localized] [Localized] [Localized] [Localized].
[Localized]: [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].

[Localized] [Localized] causal_plan_analyze [Localized] [Localized] [Localized] [Localized].""",
            parameters = listOf(
                ToolParameter(
                    name = "steps",
                    type = "string",
                    description = "[Localized] [Localized] causal_plan_analyze. [Localized] [Localized] [Localized] [Localized] [Localized] plan_id.",
                    required = false
                ),
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "[Localized] [Localized] [Localized] [Localized] [Localized] causal_plan_analyze.",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "causal_plan_what_if",
            description = """[Localized] What-If: "[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]"
[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]/[Localized] [Localized] [Localized] [Localized] [Localized].
[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].""",
            parameters = listOf(
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "[Localized] [Localized] [Localized] ([Localized] causal_plan_analyze).",
                    required = true
                ),
                ToolParameter(
                    name = "insert_step",
                    type = "string",
                    description = "[Localized] [Localized] [Localized] 'toolName|param1=val1&param2=val2'. [Localized].",
                    required = false
                ),
                ToolParameter(
                    name = "remove_step_index",
                    type = "string",
                    description = "[Localized] [Localized] [Localized] [Localized] (0-based). [Localized].",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "causal_plan_clear",
            description = "[Localized] [Localized] [Localized] [Localized]. [Localized] [Localized] [Localized] [Localized] [Localized].",
            parameters = listOf(
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "[Localized] [Localized] [Localized] [Localized]. [Localized] [Localized] [Localized] [Localized] [Localized].",
                    required = false
                )
            )
        )
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Execution
    // ──────────────────────────────────────────────────────────────────────────

    /** [Localized] null [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] wrapper. */
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
     * [Localized] [Localized] [Localized] Prompt [Localized] [Localized] [Localized] [Localized] [Localized] cache.
     * [Localized] [Localized] SmartLearningBridge [Localized] System Prompt [Localized] [Localized].
     * [Localized] null [Localized] [Localized] [Localized] cache [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
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
            ?: return ToolExecutionResult("steps [Localized]", isError = true)

        val steps = parseSteps(stepsRaw)
        if (steps.isEmpty()) return ToolExecutionResult(
            "[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].", isError = true
        )

        val graph = planner.buildChain(steps)

        // [Localized] [Localized] [Localized] cache [Localized] [Localized]
        val planId = args["plan_id"]?.trim()
        if (!planId.isNullOrBlank()) {
            // [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] cache
            if (planCache.size >= maxCacheSize) {
                planCache.keys.firstOrNull()?.let { planCache.remove(it) }
            }
            planCache[planId] = graph
        }

        return ToolExecutionResult(buildString {
            appendLine("🗺️ [Localized] [Localized] [Localized] ${graph.nodes.size} [Localized]:")
            appendLine()

            // [Localized] [Localized]
            appendLine("📋 [Localized]:")
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

            // [Localized] [Localized]
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

            if (deletedPaths.isNotEmpty()) appendLine("🗑️ [Localized]: ${deletedPaths.take(5).joinToString(", ")}")
            if (createdPaths.isNotEmpty()) appendLine("📄 [Localized]: ${createdPaths.take(5).joinToString(", ")}")
            if (modifiedPaths.isNotEmpty()) appendLine("✏️ [Localized]: ${modifiedPaths.take(5).joinToString(", ")}")
            appendLine()

            // [Localized] [Localized] [Localized]
            appendLine("⚠️ [Localized] [Localized] [Localized]: ${graph.highestRisk.label()}")
            appendLine()

            // [Localized]
            if (graph.conflicts.isEmpty()) {
                appendLine("✅ [Localized] [Localized] [Localized] — [Localized] [Localized].")
            } else {
                val fatal = graph.conflicts.filter { it.isFatal }
                val warnings = graph.conflicts.filter { !it.isFatal }
                if (fatal.isNotEmpty()) {
                    appendLine("❌ [Localized] [Localized] (${fatal.size}):")
                    for (c in fatal) appendLine("  • ${c.message}")
                    appendLine()
                }
                if (warnings.isNotEmpty()) {
                    appendLine("⚠️ [Localized] (${warnings.size}):")
                    for (c in warnings) appendLine("  • ${c.message}")
                }
            }

            if (!planId.isNullOrBlank()) appendLine("\n💾 [Localized] [Localized] [Localized] '$planId' [Localized] [Localized] simulate/what_if.")
        })
    }

    private fun doSimulate(args: Map<String, String>): ToolExecutionResult {
        val graph = resolveGraph(args)
            ?: return ToolExecutionResult(
                "[Localized] [Localized] 'steps' [Localized] 'plan_id' [Localized] [Localized] [Localized].", isError = true
            )

        val result = planner.simulate(graph)
        return ToolExecutionResult(buildString {
            appendLine("🎬 [Localized] [Localized] [Localized]:")
            appendLine()
            for (step in result.steps) {
                val status = if (step.wouldSucceed) "✅" else "❌"
                val risk = when (step.riskLevel) {
                    CausalChainPlanner.RiskLevel.LOW      -> ""
                    CausalChainPlanner.RiskLevel.MEDIUM   -> " [[Localized]]"
                    CausalChainPlanner.RiskLevel.HIGH     -> " [⚠️ [Localized]]"
                    CausalChainPlanner.RiskLevel.CRITICAL -> " [💥 [Localized]]"
                }
                appendLine("  ${step.stepIndex}. $status${risk} ${step.humanSummary}")
                if (!step.wouldSucceed && step.failReason != null) {
                    appendLine("       💔 [Localized]: ${step.failReason}")
                }
            }
            appendLine()
            if (result.overallSuccess) {
                appendLine("✅ [Localized] [Localized] — [Localized] [Localized] [Localized] [Localized].")
            } else {
                appendLine("❌ [Localized] [Localized] [Localized] [Localized] [Localized] ${result.firstFailureIndex}.")
                appendLine("   📌 [Localized] [Localized] [Localized] [Localized] [Localized] causal_plan_what_if.")
            }
            if (result.warningMessages.isNotEmpty()) {
                appendLine()
                appendLine("⚠️ [Localized] [Localized]:")
                for (w in result.warningMessages) appendLine("  • $w")
            }
        })
    }

    private fun doWhatIf(args: Map<String, String>): ToolExecutionResult {
        val planId = args["plan_id"]?.trim()
            ?: return ToolExecutionResult("plan_id [Localized]", isError = true)
        val baseline = planCache[planId]
            ?: return ToolExecutionResult(
                "[Localized] [Localized] [Localized] [Localized] '$planId'. [Localized] causal_plan_analyze [Localized].", isError = true
            )

        val insertRaw = args["insert_step"]?.trim()
        val removeIdx = args["remove_step_index"]?.toIntOrNull()

        val insertStep = if (!insertRaw.isNullOrBlank()) {
            parseSteps(insertRaw).firstOrNull()
        } else null

        if (insertStep == null && removeIdx == null) {
            return ToolExecutionResult(
                "[Localized] [Localized] insert_step [Localized] remove_step_index.", isError = true
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
            ToolExecutionResult("✅ [Localized] [Localized] $count [Localized] [Localized] [Localized] [Localized].")
        } else {
            val existed = planCache.remove(planId) != null
            if (existed) ToolExecutionResult("✅ [Localized] [Localized] [Localized] '$planId'.")
            else ToolExecutionResult("⚠️ [Localized] [Localized] [Localized] [Localized] [Localized] '$planId'.")
        }
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized] (toolName → parameters).
     *
     * [Localized] [Localized]:
     *   "toolName|param1=val1&param2=val2,toolName2|param1=val1"
     * [Localized] [Localized] [Localized] [Localized] [Localized].
     */
    private fun parseSteps(raw: String): List<Pair<String, Map<String, String>>> {
        return raw
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val pipeIdx = entry.indexOf('|')
                if (pipeIdx < 0) {
                    // [Localized] [Localized] [Localized] [Localized] [Localized]
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

    /** [Localized] [Localized] CausalGraph [Localized] [Localized] cache [Localized] [Localized] [Localized] steps. */
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
