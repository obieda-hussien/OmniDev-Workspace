package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.brain.CausalChainPlanner

/** Expose [CausalChainPlanner] through analyze, simulate, what-if and clear tools. Rule-based analysis uses session memory (up to 10 plans), with no additional LLM or database. Analyze a planned sequence, revise critical conflicts, simulate, then execute with one rollback group. Analysis itself does not modify the device. */
class CausalChainPlannerTool(
    private val planner: CausalChainPlanner = CausalChainPlanner()
) {

    /** Session-scoped temporary plan cache (up to 10 plans). */
    private val planCache = mutableMapOf<String, CausalChainPlanner.CausalGraph>()
    private val maxCacheSize = 10

    // ──────────────────────────────────────────────────────────────────────────
    // Tool Definitions
    // ──────────────────────────────────────────────────────────────────────────

    fun getDefinitions(): List<ToolDefinition> = listOf(

        ToolDefinition(
            name = "causal_plan_analyze",
            description = """Analyze a multi-step plan and build its causal graph.
Detect logical conflicts before execution, including:
- Reading a deleted file (Read-After-Delete)
- Modifying a deleted file (Modify-After-Delete)
- Creating the same file twice (Double-Create)
- Wasted work (modify followed immediately by delete)
- Critical-risk commands (rm -rf, git reset --hard)

Use before executing complex action sequences to identify conflicts and risks.
Example steps: "create_file|path=/src/A.kt,patch_file_content|path=/src/A.kt,delete_file|path=/src/A.kt"
""",
            parameters = listOf(
                ToolParameter(
                    name = "steps",
                    type = "string",
                    description = """Planned steps in this format:
"toolName|param1=val1&param2=val2,toolName2|param1=val1"
Example: "create_file|path=/src/Main.kt,patch_file_content|path=/src/Main.kt,run_terminal|command=rm -rf /tmp"
Separate steps with a comma (,) or newline.""",
                    required = true
                ),
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "Optional plan ID for temporary storage and later simulate/what_if calls.",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "causal_plan_simulate",
            description = """Simulate plan execution without changing the device.
Builds a virtual file system and checks each step against it.
Reports which step would fail, why, and the resulting system state.

Use after causal_plan_analyze for a final check before execution.""",
            parameters = listOf(
                ToolParameter(
                    name = "steps",
                    type = "string",
                    description = "Same format as causal_plan_analyze; optional when plan_id is provided.",
                    required = false
                ),
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "Use a plan previously saved by causal_plan_analyze.",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "causal_plan_what_if",
            description = """What-if analysis: assess the effect of adding or removing a step.
Compare risk, conflicts and success/failure between the original and modified plans.
Useful before deciding to change the order of steps.""",
            parameters = listOf(
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "Saved plan ID from causal_plan_analyze.",
                    required = true
                ),
                ToolParameter(
                    name = "insert_step",
                    type = "string",
                    description = "Optional new step in 'toolName|param1=val1&param2=val2' format.",
                    required = false
                ),
                ToolParameter(
                    name = "remove_step_index",
                    type = "string",
                    description = "Optional zero-based index of the step to remove.",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "causal_plan_clear",
            description = "Clear temporary plans at the start of a new task.",
            parameters = listOf(
                ToolParameter(
                    name = "plan_id",
                    type = "string",
                    description = "Plan ID to delete; clears all plans when omitted.",
                    required = false
                )
            )
        )
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Execution
    // ──────────────────────────────────────────────────────────────────────────

    /** Return null for tools not handled by this wrapper. */
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

    /** Build prompt context for the most recent cached plan. SmartLearningBridge uses it for causal warnings. Return null for an empty cache or a plan with no relevant warnings. */
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
            ?: return ToolExecutionResult("steps are required", isError = true)

        val steps = parseSteps(stepsRaw)
        if (steps.isEmpty()) return ToolExecutionResult(
            "No valid steps were recognized in the input.", isError = true
        )

        val graph = planner.buildChain(steps)

        // Save in the cache when requested.
        val planId = args["plan_id"]?.trim()
        if (!planId.isNullOrBlank()) {
            // Evict the oldest entry when the cache is full.
            if (planCache.size >= maxCacheSize) {
                planCache.keys.firstOrNull()?.let { planCache.remove(it) }
            }
            planCache[planId] = graph
        }

        return ToolExecutionResult(buildString {
            appendLine("🗺️ Causal graph for ${graph.nodes.size} steps:")
            appendLine()

            // Step summary.
            appendLine("📋 Steps:")
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

            // Aggregate effects.
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

            if (deletedPaths.isNotEmpty()) appendLine("🗑️ Will delete: ${deletedPaths.take(5).joinToString(", ")}")
            if (createdPaths.isNotEmpty()) appendLine("📄 Will create: ${createdPaths.take(5).joinToString(", ")}")
            if (modifiedPaths.isNotEmpty()) appendLine("✏️ Will modify: ${modifiedPaths.take(5).joinToString(", ")}")
            appendLine()

            // Overall risk level.
            appendLine("⚠️ Highest risk: ${graph.highestRisk.label()}")
            appendLine()

            // Conflicts.
            if (graph.conflicts.isEmpty()) {
                appendLine("✅ No modeled conflicts were found in the plan.")
            } else {
                val fatal = graph.conflicts.filter { it.isFatal }
                val warnings = graph.conflicts.filter { !it.isFatal }
                if (fatal.isNotEmpty()) {
                    appendLine("❌ Critical conflicts (${fatal.size}):")
                    for (c in fatal) appendLine("  • ${c.message}")
                    appendLine()
                }
                if (warnings.isNotEmpty()) {
                    appendLine("⚠️ Warnings (${warnings.size}):")
                    for (c in warnings) appendLine("  • ${c.message}")
                }
            }

            if (!planId.isNullOrBlank()) appendLine("\n💾 Saved plan '$planId' for simulate/what_if.")
        })
    }

    private fun doSimulate(args: Map<String, String>): ToolExecutionResult {
        val graph = resolveGraph(args)
            ?: return ToolExecutionResult(
                "Provide 'steps' or the 'plan_id' of a saved plan.", isError = true
            )

        val result = planner.simulate(graph)
        return ToolExecutionResult(buildString {
            appendLine("🎬 Simulation result:")
            appendLine()
            for (step in result.steps) {
                val status = if (step.wouldSucceed) "✅" else "❌"
                val risk = when (step.riskLevel) {
                    CausalChainPlanner.RiskLevel.LOW      -> ""
                    CausalChainPlanner.RiskLevel.MEDIUM   -> " [medium]"
                    CausalChainPlanner.RiskLevel.HIGH     -> " [⚠️ high]"
                    CausalChainPlanner.RiskLevel.CRITICAL -> " [💥 critical]"
                }
                appendLine("  ${step.stepIndex}. $status${risk} ${step.humanSummary}")
                if (!step.wouldSucceed && step.failReason != null) {
                    appendLine("       💔 Reason: ${step.failReason}")
                }
            }
            appendLine()
            if (result.overallSuccess) {
                appendLine("✅ Simulation succeeded: all modeled steps can execute.")
            } else {
                appendLine("❌ Simulation detected failure at step ${result.firstFailureIndex}.")
                appendLine("   📌 Revise the plan or use causal_plan_what_if.")
            }
            if (result.warningMessages.isNotEmpty()) {
                appendLine()
                appendLine("⚠️ Additional warnings:")
                for (w in result.warningMessages) appendLine("  • $w")
            }
        })
    }

    private fun doWhatIf(args: Map<String, String>): ToolExecutionResult {
        val planId = args["plan_id"]?.trim()
            ?: return ToolExecutionResult("plan_id is required", isError = true)
        val baseline = planCache[planId]
            ?: return ToolExecutionResult(
                "Plan '$planId' was not found. Use causal_plan_analyze first.", isError = true
            )

        val insertRaw = args["insert_step"]?.trim()
        val removeIdx = args["remove_step_index"]?.toIntOrNull()

        val insertStep = if (!insertRaw.isNullOrBlank()) {
            parseSteps(insertRaw).firstOrNull()
        } else null

        if (insertStep == null && removeIdx == null) {
            return ToolExecutionResult(
                "Provide insert_step or remove_step_index.", isError = true
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
            ToolExecutionResult("✅ Deleted $count temporary plans from memory.")
        } else {
            val existed = planCache.remove(planId) != null
            if (existed) ToolExecutionResult("✅ Deleted plan '$planId'.")
            else ToolExecutionResult("⚠️ No plan with ID '$planId' was found.")
        }
    }

    /** Parse tool steps in toolName|param1=val1&param2=val2 format, separated by commas or newlines. */
    private fun parseSteps(raw: String): List<Pair<String, Map<String, String>>> {
        return raw
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { entry ->
                val pipeIdx = entry.indexOf('|')
                if (pipeIdx < 0) {
                    // Tool name without arguments.
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

    /** Retrieve a cached CausalGraph or build one from steps. */
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
