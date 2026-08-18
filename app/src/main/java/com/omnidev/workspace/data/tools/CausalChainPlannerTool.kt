package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.brain.CausalChainPlanner

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * CausalChainPlannerTool — Context note Agent Context note Context note Context note Context note
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Context note Context note [CausalChainPlanner] Context note Context note Context note Context note:
 *
 *   - **causal_plan_analyze**: Context note Context note Context note Context note Context note Context note Context note
 *     Context note Context note.
 *
 *   - **causal_plan_simulate**: Context note Context note Context note Context note Context note Context note Context note Context note
 *     Context note Context note — Context note Context note Context note Context note Context note Context note Context note.
 *
 *   - **causal_plan_what_if**: Context note Context note "Context note Context note Context note Context note Context note Context note Context note"
 *     Context note Context note Context note Context note Context note Context note.
 *
 *   - **causal_plan_clear**: Context note Context note Context note Context note (session cache).
 *
 * ## Mobile-First:
 * - Context note LLM Context note — Context note rule-based Context note (< 5ms Context note 20 Context note)
 * - Context note Context note Context note — Context note Context note Context note Context note Context note 10 Context note Context note
 * - Context note Context note Context note Tiers (Lite → OEM) Context note Context note/Context note Context note
 *
 * ## Context note Context note Context note:
 * 1. Context note Context note Context note Context note Context note
 * 2. Context note `causal_plan_analyze` Context note
 * 3. Context note Context note Context note Context note → Context note Context note
 * 4. Context note `causal_plan_simulate` Context note Context note
 * 5. Context note Context note Context note rollback group Context note
 */
class CausalChainPlannerTool(
    private val planner: CausalChainPlanner = CausalChainPlanner()
) {

    /** Context note Context note Context note (session-scoped, max 10). */
    private val planCache = mutableMapOf<String, CausalChainPlanner.CausalGraph>()
    private val maxCacheSize = 10

    // ──────────────────────────────────────────────────────────────────────────
    // Tool Definitions
    // ──────────────────────────────────────────────────────────────────────────

    fun getDefinitions(): List<ToolDefinition> = listOf(

        ToolDefinition(
            name = "causal_plan_analyze",
            description = """Info Info Info Info Info Info Info (Causal Graph) Info.
Info Info Info Info Info Info:
- Info Info Info (Read-After-Delete)
- Info Info Info Info (Modify-After-Delete)
- Info Info Info Info (Double-Create)
- Info Info (Info Info Info Info)
- Info Info Info Info (rm -rf, git reset --hard)

Info Info Info Info Info Info Info Info Info.
Info steps: "create_file|path=/src/A.kt,patch_file_content|path=/src/A.kt,delete_file|path=/src/A.kt"
""",
            parameters = listOf(
                ToolParameter(
                    name = "steps",
                    type = "string",
                    description = """Info Info Info Info:
"toolName|param1=val1&param2=val2,toolName2|param1=val1"
Info: "create_file|path=/src/Main.kt,patch_file_content|path=/src/Main.kt,run_terminal|command=rm -rf /tmp"
Info Info Info (,) Info Info Info.""",
                    required = true
                ),
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "Info Info Info Info (Info Info Info simulate/what_if). Info.",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "causal_plan_simulate",
            description = """Info Info Info Info Info Info Info Info Info Info.
Info "Info Info Info" Info Info Info Info Info.
Info: Info Info Info Info Info Info Info Info Info.

Info Info causal_plan_analyze Info Info Info Info.""",
            parameters = listOf(
                ToolParameter(
                    name = "steps",
                    type = "string",
                    description = "Info Info causal_plan_analyze. Info Info Info Info Info plan_id.",
                    required = false
                ),
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "Info Info Info Info Info causal_plan_analyze.",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "causal_plan_what_if",
            description = """Info What-If: "Info Info Info Info Info Info Info"
Info Info Info Info Info Info Info/Info Info Info Info Info.
Info Info Info Info Info Info Info Info.""",
            parameters = listOf(
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "Info Info Info (Info causal_plan_analyze).",
                    required = true
                ),
                ToolParameter(
                    name = "insert_step",
                    type = "string",
                    description = "Info Info Info 'toolName|param1=val1&param2=val2'. Info.",
                    required = false
                ),
                ToolParameter(
                    name = "remove_step_index",
                    type = "string",
                    description = "Info Info Info Info (0-based). Info.",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "causal_plan_clear",
            description = "Info Info Info Info. Info Info Info Info Info.",
            parameters = listOf(
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "Info Info Info Info. Info Info Info Info Info.",
                    required = false
                )
            )
        )
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Execution
    // ──────────────────────────────────────────────────────────────────────────

    /** Context note null Context note Context note Context note Context note Context note Context note wrapper. */
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
     * Context note Context note Context note Prompt Context note Context note Context note Context note Context note cache.
     * Context note Context note SmartLearningBridge Context note System Prompt Context note Context note.
     * Context note null Context note Context note Context note cache Context note Context note Context note Context note Context note Context note Context note.
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
            ?: return ToolExecutionResult("steps Info", isError = true)

        val steps = parseSteps(stepsRaw)
        if (steps.isEmpty()) return ToolExecutionResult(
            "Info Info Info Info Info Info Info Info Info.", isError = true
        )

        val graph = planner.buildChain(steps)

        // Context note Context note Context note cache Context note Context note
        val planId = args["plan_id"]?.trim()
        if (!planId.isNullOrBlank()) {
            // Context note Context note Context note Context note Context note Context note cache
            if (planCache.size >= maxCacheSize) {
                planCache.keys.firstOrNull()?.let { planCache.remove(it) }
            }
            planCache[planId] = graph
        }

        return ToolExecutionResult(buildString {
            appendLine("🗺️ Info Info Info ${graph.nodes.size} Info:")
            appendLine()

            // Context note Context note
            appendLine("📋 Info:")
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

            // Context note Context note
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

            if (deletedPaths.isNotEmpty()) appendLine("🗑️ Info: ${deletedPaths.take(5).joinToString(", ")}")
            if (createdPaths.isNotEmpty()) appendLine("📄 Info: ${createdPaths.take(5).joinToString(", ")}")
            if (modifiedPaths.isNotEmpty()) appendLine("✏️ Info: ${modifiedPaths.take(5).joinToString(", ")}")
            appendLine()

            // Context note Context note Context note
            appendLine("⚠️ Info Info Infohighest: ${graph.highestRisk.label()}")
            appendLine()

            // Context note
            if (graph.conflicts.isEmpty()) {
                appendLine("✅ Info Info Info — Info Info.")
            } else {
                val fatal = graph.conflicts.filter { it.isFatal }
                val warnings = graph.conflicts.filter { !it.isFatal }
                if (fatal.isNotEmpty()) {
                    appendLine("❌ Info Info (${fatal.size}):")
                    for (c in fatal) appendLine("  • ${c.message}")
                    appendLine()
                }
                if (warnings.isNotEmpty()) {
                    appendLine("⚠️ Info (${warnings.size}):")
                    for (c in warnings) appendLine("  • ${c.message}")
                }
            }

            if (!planId.isNullOrBlank()) appendLine("\n💾 Info Info Info '$planId' Info Info simulate/what_if.")
        })
    }

    private fun doSimulate(args: Map<String, String>): ToolExecutionResult {
        val graph = resolveGraph(args)
            ?: return ToolExecutionResult(
                "Info Info 'steps' Info 'plan_id' Info Info Info.", isError = true
            )

        val result = planner.simulate(graph)
        return ToolExecutionResult(buildString {
            appendLine("🎬 Info Info Info:")
            appendLine()
            for (step in result.steps) {
                val status = if (step.wouldSucceed) "✅" else "❌"
                val risk = when (step.riskLevel) {
                    CausalChainPlanner.RiskLevel.LOW      -> ""
                    CausalChainPlanner.RiskLevel.MEDIUM   -> " [Info]"
                    CausalChainPlanner.RiskLevel.HIGH     -> " [⚠️ Info]"
                    CausalChainPlanner.RiskLevel.CRITICAL -> " [💥 Info]"
                }
                appendLine("  ${step.stepIndex}. $status${risk} ${step.humanSummary}")
                if (!step.wouldSucceed && step.failReason != null) {
                    appendLine("       💔 Info: ${step.failReason}")
                }
            }
            appendLine()
            if (result.overallSuccess) {
                appendLine("✅ Info Info — Info Info Info Info.")
            } else {
                appendLine("❌ Info Info Info Info Info ${result.firstFailureIndex}.")
                appendLine("   📌 Info Info Info Info Info causal_plan_what_if.")
            }
            if (result.warningMessages.isNotEmpty()) {
                appendLine()
                appendLine("⚠️ Info Info:")
                for (w in result.warningMessages) appendLine("  • $w")
            }
        })
    }

    private fun doWhatIf(args: Map<String, String>): ToolExecutionResult {
        val planId = args["plan_id"]?.trim()
            ?: return ToolExecutionResult("plan_id Info", isError = true)
        val baseline = planCache[planId]
            ?: return ToolExecutionResult(
                "Info Info Info Info '$planId'. Info causal_plan_analyze Info.", isError = true
            )

        val insertRaw = args["insert_step"]?.trim()
        val removeIdx = args["remove_step_index"]?.toIntOrNull()

        val insertStep = if (!insertRaw.isNullOrBlank()) {
            parseSteps(insertRaw).firstOrNull()
        } else null

        if (insertStep == null && removeIdx == null) {
            return ToolExecutionResult(
                "Info Info insert_step Info remove_step_index.", isError = true
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
            ToolExecutionResult("✅ Info Info $count Info Info Info Info.")
        } else {
            val existed = planCache.remove(planId) != null
            if (existed) ToolExecutionResult("✅ Info Info Info '$planId'.")
            else ToolExecutionResult("⚠️ Info Info Info Info Info '$planId'.")
        }
    }

    /**
     * Context note Context note Context note Context note Context note (toolName → parameters).
     *
     * Context note Context note:
     *   "toolName|param1=val1&param2=val2,toolName2|param1=val1"
     * Context note Context note Context note Context note Context note.
     */
    private fun parseSteps(raw: String): List<Pair<String, Map<String, String>>> {
        return raw
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val pipeIdx = entry.indexOf('|')
                if (pipeIdx < 0) {
                    // Context note Context note Context note Context note Context note
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

    /** Context note Context note CausalGraph Context note Context note cache Context note Context note Context note steps. */
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
