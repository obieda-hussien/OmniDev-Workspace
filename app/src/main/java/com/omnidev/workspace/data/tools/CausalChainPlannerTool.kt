package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.brain.CausalChainPlanner

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * CausalChainPlannerTool — System awareness note Agent System awareness note System awareness note System awareness note System awareness note
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * System awareness note System awareness note [CausalChainPlanner] System awareness note System awareness note System awareness note System awareness note:
 *
 *   - **causal_plan_analyze**: System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
 *     System awareness note System awareness note.
 *
 *   - **causal_plan_simulate**: System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
 *     System awareness note System awareness note — System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
 *
 *   - **causal_plan_what_if**: System awareness note System awareness note "System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note"
 *     System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
 *
 *   - **causal_plan_clear**: System awareness note System awareness note System awareness note System awareness note (session cache).
 *
 * ## Mobile-First:
 * - System awareness note LLM System awareness note — System awareness note rule-based System awareness note (< 5ms System awareness note 20 System awareness note)
 * - System awareness note System awareness note System awareness note — System awareness note System awareness note System awareness note System awareness note System awareness note 10 System awareness note System awareness note
 * - System awareness note System awareness note System awareness note Tiers (Lite → OEM) System awareness note System awareness note/System awareness note System awareness note
 *
 * ## System awareness note System awareness note System awareness note:
 * 1. System awareness note System awareness note System awareness note System awareness note System awareness note
 * 2. System awareness note `causal_plan_analyze` System awareness note
 * 3. System awareness note System awareness note System awareness note System awareness note → System awareness note System awareness note
 * 4. System awareness note `causal_plan_simulate` System awareness note System awareness note
 * 5. System awareness note System awareness note System awareness note rollback group System awareness note
 */
class CausalChainPlannerTool(
    private val planner: CausalChainPlanner = CausalChainPlanner()
) {

    /** System awareness note System awareness note System awareness note (session-scoped, max 10). */
    private val planCache = mutableMapOf<String, CausalChainPlanner.CausalGraph>()
    private val maxCacheSize = 10

    // ──────────────────────────────────────────────────────────────────────────
    // Tool Definitions
    // ──────────────────────────────────────────────────────────────────────────

    fun getDefinitions(): List<ToolDefinition> = listOf(

        ToolDefinition(
            name = "causal_plan_analyze",
            description = """System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note (Causal Graph) System awareness note.
System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note:
- System awareness note System awareness note System awareness note (Read-After-Delete)
- System awareness note System awareness note System awareness note System awareness note (Modify-After-Delete)
- System awareness note System awareness note System awareness note System awareness note (Double-Create)
- System awareness note System awareness note (System awareness note System awareness note System awareness note System awareness note)
- System awareness note System awareness note System awareness note System awareness note (rm -rf, git reset --hard)

System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
System awareness note steps: "create_file|path=/src/A.kt,patch_file_content|path=/src/A.kt,delete_file|path=/src/A.kt"
""",
            parameters = listOf(
                ToolParameter(
                    name = "steps",
                    type = "string",
                    description = """System awareness note System awareness note System awareness note System awareness note:
"toolName|param1=val1&param2=val2,toolName2|param1=val1"
System awareness note: "create_file|path=/src/Main.kt,patch_file_content|path=/src/Main.kt,run_terminal|command=rm -rf /tmp"
System awareness note System awareness note System awareness note (,) System awareness note System awareness note System awareness note.""",
                    required = true
                ),
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "System awareness note System awareness note System awareness note System awareness note (System awareness note System awareness note System awareness note simulate/what_if). System awareness note.",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "causal_plan_simulate",
            description = """System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
System awareness note "System awareness note System awareness note System awareness note" System awareness note System awareness note System awareness note System awareness note System awareness note.
System awareness note: System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.

System awareness note System awareness note causal_plan_analyze System awareness note System awareness note System awareness note System awareness note.""",
            parameters = listOf(
                ToolParameter(
                    name = "steps",
                    type = "string",
                    description = "System awareness note System awareness note causal_plan_analyze. System awareness note System awareness note System awareness note System awareness note System awareness note plan_id.",
                    required = false
                ),
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "System awareness note System awareness note System awareness note System awareness note System awareness note causal_plan_analyze.",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "causal_plan_what_if",
            description = """System awareness note What-If: "System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note"
System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note/System awareness note System awareness note System awareness note System awareness note System awareness note.
System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.""",
            parameters = listOf(
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "System awareness note System awareness note System awareness note (System awareness note causal_plan_analyze).",
                    required = true
                ),
                ToolParameter(
                    name = "insert_step",
                    type = "string",
                    description = "System awareness note System awareness note System awareness note 'toolName|param1=val1&param2=val2'. System awareness note.",
                    required = false
                ),
                ToolParameter(
                    name = "remove_step_index",
                    type = "string",
                    description = "System awareness note System awareness note System awareness note System awareness note (0-based). System awareness note.",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "causal_plan_clear",
            description = "System awareness note System awareness note System awareness note System awareness note. System awareness note System awareness note System awareness note System awareness note System awareness note.",
            parameters = listOf(
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "System awareness note System awareness note System awareness note System awareness note. System awareness note System awareness note System awareness note System awareness note System awareness note.",
                    required = false
                )
            )
        )
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Execution
    // ──────────────────────────────────────────────────────────────────────────

    /** System awareness note null System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note wrapper. */
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
     * System awareness note System awareness note System awareness note Prompt System awareness note System awareness note System awareness note System awareness note System awareness note cache.
     * System awareness note System awareness note SmartLearningBridge System awareness note System Prompt System awareness note System awareness note.
     * System awareness note null System awareness note System awareness note System awareness note cache System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
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
            ?: return ToolExecutionResult("steps System awareness note", isError = true)

        val steps = parseSteps(stepsRaw)
        if (steps.isEmpty()) return ToolExecutionResult(
            "System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.", isError = true
        )

        val graph = planner.buildChain(steps)

        // System awareness note System awareness note System awareness note cache System awareness note System awareness note
        val planId = args["plan_id"]?.trim()
        if (!planId.isNullOrBlank()) {
            // System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note cache
            if (planCache.size >= maxCacheSize) {
                planCache.keys.firstOrNull()?.let { planCache.remove(it) }
            }
            planCache[planId] = graph
        }

        return ToolExecutionResult(buildString {
            appendLine("🗺️ System awareness note System awareness note System awareness note ${graph.nodes.size} System awareness note:")
            appendLine()

            // System awareness note System awareness note
            appendLine("📋 System awareness note:")
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

            // System awareness note System awareness note
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

            if (deletedPaths.isNotEmpty()) appendLine("🗑️ System awareness note: ${deletedPaths.take(5).joinToString(", ")}")
            if (createdPaths.isNotEmpty()) appendLine("📄 System awareness note: ${createdPaths.take(5).joinToString(", ")}")
            if (modifiedPaths.isNotEmpty()) appendLine("✏️ System awareness note: ${modifiedPaths.take(5).joinToString(", ")}")
            appendLine()

            // System awareness note System awareness note System awareness note
            appendLine("⚠️ System awareness note System awareness note System awareness note: ${graph.highestRisk.label()}")
            appendLine()

            // System awareness note
            if (graph.conflicts.isEmpty()) {
                appendLine("✅ System awareness note System awareness note System awareness note — System awareness note System awareness note.")
            } else {
                val fatal = graph.conflicts.filter { it.isFatal }
                val warnings = graph.conflicts.filter { !it.isFatal }
                if (fatal.isNotEmpty()) {
                    appendLine("❌ System awareness note System awareness note (${fatal.size}):")
                    for (c in fatal) appendLine("  • ${c.message}")
                    appendLine()
                }
                if (warnings.isNotEmpty()) {
                    appendLine("⚠️ System awareness note (${warnings.size}):")
                    for (c in warnings) appendLine("  • ${c.message}")
                }
            }

            if (!planId.isNullOrBlank()) appendLine("\n💾 System awareness note System awareness note System awareness note '$planId' System awareness note System awareness note simulate/what_if.")
        })
    }

    private fun doSimulate(args: Map<String, String>): ToolExecutionResult {
        val graph = resolveGraph(args)
            ?: return ToolExecutionResult(
                "System awareness note System awareness note 'steps' System awareness note 'plan_id' System awareness note System awareness note System awareness note.", isError = true
            )

        val result = planner.simulate(graph)
        return ToolExecutionResult(buildString {
            appendLine("🎬 System awareness note System awareness note System awareness note:")
            appendLine()
            for (step in result.steps) {
                val status = if (step.wouldSucceed) "✅" else "❌"
                val risk = when (step.riskLevel) {
                    CausalChainPlanner.RiskLevel.LOW      -> ""
                    CausalChainPlanner.RiskLevel.MEDIUM   -> " [System awareness note]"
                    CausalChainPlanner.RiskLevel.HIGH     -> " [⚠️ System awareness note]"
                    CausalChainPlanner.RiskLevel.CRITICAL -> " [💥 System awareness note]"
                }
                appendLine("  ${step.stepIndex}. $status${risk} ${step.humanSummary}")
                if (!step.wouldSucceed && step.failReason != null) {
                    appendLine("       💔 System awareness note: ${step.failReason}")
                }
            }
            appendLine()
            if (result.overallSuccess) {
                appendLine("✅ System awareness note System awareness note — System awareness note System awareness note System awareness note System awareness note.")
            } else {
                appendLine("❌ System awareness note System awareness note System awareness note System awareness note System awareness note ${result.firstFailureIndex}.")
                appendLine("   📌 System awareness note System awareness note System awareness note System awareness note System awareness note causal_plan_what_if.")
            }
            if (result.warningMessages.isNotEmpty()) {
                appendLine()
                appendLine("⚠️ System awareness note System awareness note:")
                for (w in result.warningMessages) appendLine("  • $w")
            }
        })
    }

    private fun doWhatIf(args: Map<String, String>): ToolExecutionResult {
        val planId = args["plan_id"]?.trim()
            ?: return ToolExecutionResult("plan_id System awareness note", isError = true)
        val baseline = planCache[planId]
            ?: return ToolExecutionResult(
                "System awareness note System awareness note System awareness note System awareness note '$planId'. System awareness note causal_plan_analyze System awareness note.", isError = true
            )

        val insertRaw = args["insert_step"]?.trim()
        val removeIdx = args["remove_step_index"]?.toIntOrNull()

        val insertStep = if (!insertRaw.isNullOrBlank()) {
            parseSteps(insertRaw).firstOrNull()
        } else null

        if (insertStep == null && removeIdx == null) {
            return ToolExecutionResult(
                "System awareness note System awareness note insert_step System awareness note remove_step_index.", isError = true
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
            ToolExecutionResult("✅ System awareness note System awareness note $count System awareness note System awareness note System awareness note System awareness note.")
        } else {
            val existed = planCache.remove(planId) != null
            if (existed) ToolExecutionResult("✅ System awareness note System awareness note System awareness note '$planId'.")
            else ToolExecutionResult("⚠️ System awareness note System awareness note System awareness note System awareness note System awareness note '$planId'.")
        }
    }

    /**
     * System awareness note System awareness note System awareness note System awareness note System awareness note (toolName → parameters).
     *
     * System awareness note System awareness note:
     *   "toolName|param1=val1&param2=val2,toolName2|param1=val1"
     * System awareness note System awareness note System awareness note System awareness note System awareness note.
     */
    private fun parseSteps(raw: String): List<Pair<String, Map<String, String>>> {
        return raw
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val pipeIdx = entry.indexOf('|')
                if (pipeIdx < 0) {
                    // System awareness note System awareness note System awareness note System awareness note System awareness note
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

    /** System awareness note System awareness note CausalGraph System awareness note System awareness note cache System awareness note System awareness note System awareness note steps. */
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
