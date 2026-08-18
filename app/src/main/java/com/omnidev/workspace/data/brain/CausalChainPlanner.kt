package com.omnidev.workspace.data.brain

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * CausalChainPlanner — [Localized] [Localized] [Localized] [Localized] [Localized] (Mobile-First)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] "[Localized] [Localized]" (Causal Graph)
 * [Localized] [Localized] [Localized] [Localized] [Localized] ([Localized]: [Localized] [Localized] [Localized] [Localized] [Localized]).
 *
 * ## [Localized] [Localized]:
 * 1. **[Localized] [Localized]** (analyzeToolCall): [Localized] [Localized] [Localized] → [Localized] [Localized] [Localized] + [Localized]
 * 2. **[Localized] [Localized]** (buildChain): [Localized] DAG [Localized] [Localized] [Localized] [Localized]
 * 3. **[Localized] [Localized]** (detectConflicts): [Localized] [Localized] [Localized] [Localized] (Read-After-Delete[Localized] [Localized])
 * 4. **[Localized] [Localized]** (simulate): [Localized] [Localized] [Localized] [Localized] [Localized]
 * 5. **[Localized] What-If** (whatIf): [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
 * 6. **[Localized] [Localized] Prompt** (buildPromptInjection): [Localized] [Localized] [Localized] [Localized] system prompt
 *
 * ## Mobile-First (4 GB RAM [Localized] [Localized]):
 * - [Localized] [Localized] LLM [Localized] — [Localized] [Localized] rule-based
 * - [Localized] [Localized] [Localized] — [Localized] [Localized] [Localized] (in-memory)
 * - [Localized] [Localized] [maxNodes] [Localized] [Localized] [Localized]
 * - [Localized] [Localized] < 5ms [Localized] 20 [Localized]
 */
class CausalChainPlanner(
    /** [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized] OOM). */
    val maxNodes: Int = 50
) {

    // ──────────────────────────────────────────────────────────────────────────
    // Data classes & enums (public so CausalChainPlannerTool can read them)
    // ──────────────────────────────────────────────────────────────────────────

    enum class EffectType {
        CREATE,   // [Localized] [Localized] [Localized] [Localized] [Localized]
        DELETE,   // [Localized] [Localized] [Localized] [Localized]
        MODIFY,   // [Localized] [Localized] [Localized]
        READ,     // [Localized] [Localized] ([Localized] [Localized] [Localized])
        EXECUTE,  // [Localized] [Localized] [Localized] Shell [Localized] [Localized] [Localized]
        SYSTEM,   // [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized] Git[Localized] [Localized])
        NETWORK   // [Localized] [Localized] [Localized] [Localized]
    }

    enum class RiskLevel(val score: Int) {
        LOW(1), MEDIUM(2), HIGH(3), CRITICAL(4);

        fun label(): String = when (this) {
            LOW      -> "🟢 [Localized]"
            MEDIUM   -> "🟡 [Localized]"
            HIGH     -> "🔴 [Localized]"
            CRITICAL -> "💥 [Localized]"
        }
    }

    enum class ConflictType {
        READ_AFTER_DELETE,      // [Localized] [Localized] [Localized] [Localized]
        MODIFY_AFTER_DELETE,    // [Localized] [Localized] [Localized] [Localized]
        DOUBLE_CREATE,          // [Localized] [Localized] [Localized] [Localized]
        OVERWRITE_UNREAD,       // [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized])
        DELETE_AFTER_MODIFY,    // [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized])
        CRITICAL_COMMAND,       // [Localized] [Localized] [Localized] [Localized] (rm -rf, git reset --hard)
        CIRCULAR_DEPENDENCY,    // A [Localized] [Localized] B [Localized] B [Localized] [Localized] A
    }

    data class CausalEffect(
        val type: EffectType,
        /** [Localized] [Localized] [Localized] [Localized] (null [Localized] [Localized] [Localized] [Localized]). */
        val targetPath: String?,
        val description: String
    )

    data class CausalNode(
        val id: String,
        val stepIndex: Int,
        val toolName: String,
        val parameters: Map<String, String>,
        val preconditions: List<String>,
        val effects: List<CausalEffect>,
        val riskLevel: RiskLevel,
        val humanSummary: String
    )

    data class CausalConflict(
        val type: ConflictType,
        val stepA: Int,       // [Localized] [Localized] [Localized]
        val stepB: Int,       // [Localized] [Localized] [Localized] (-1 [Localized] [Localized] [Localized])
        val path: String?,
        val message: String,
        val isFatal: Boolean  // [Localized] [Localized] [Localized] [Localized]
    )

    data class CausalGraph(
        val nodes: List<CausalNode>,
        val conflicts: List<CausalConflict>
    ) {
        val highestRisk: RiskLevel
            get() = nodes.maxByOrNull { it.riskLevel.score }?.riskLevel ?: RiskLevel.LOW
        val hasFatalConflict: Boolean
            get() = conflicts.any { it.isFatal }
    }

    data class SimulationStep(
        val stepIndex: Int,
        val toolName: String,
        val humanSummary: String,
        val riskLevel: RiskLevel,
        val wouldSucceed: Boolean,
        val failReason: String?,
        val virtualStateAfter: VirtualState
    )

    data class SimulationResult(
        val steps: List<SimulationStep>,
        val overallSuccess: Boolean,
        val firstFailureIndex: Int,   // -1 [Localized] [Localized] [Localized]
        val warningMessages: List<String>
    )

    /**
     * [Localized] [Localized] [Localized]/[Localized] [Localized] [Localized].
     * [Localized] [Localized] — [Localized] sets [Localized] [Localized].
     */
    data class VirtualState(
        val createdPaths: Set<String>,
        val deletedPaths: Set<String>,
        val modifiedPaths: Set<String>,
        val readPaths: Set<String>
    ) {
        fun exists(path: String): Boolean = path in createdPaths && path !in deletedPaths
        fun wasDeleted(path: String): Boolean = path in deletedPaths
        companion object {
            val EMPTY = VirtualState(emptySet(), emptySet(), emptySet(), emptySet())
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized] [CausalNode] [Localized] [Localized].
     * [Localized] 100% rule-based — [Localized] LLM[Localized] [Localized] [Localized] [Localized] < 1ms.
     */
    fun analyzeToolCall(
        stepIndex: Int,
        toolName: String,
        parameters: Map<String, String>
    ): CausalNode {
        val rule = findRule(toolName, parameters)
        return CausalNode(
            id = "${stepIndex}_${toolName}",
            stepIndex = stepIndex,
            toolName = toolName,
            parameters = parameters,
            preconditions = rule.preconditions,
            effects = rule.effects,
            riskLevel = rule.riskLevel,
            humanSummary = rule.humanSummary
        )
    }

    /**
     * [Localized] [CausalGraph] [Localized] [Localized] [Localized] [Localized] [Localized].
     * [Localized] [Localized] [Localized] [Localized] [Localized].
     * [Localized] [Localized] [Localized] [maxNodes][Localized] [Localized] [Localized].
     */
    fun buildChain(steps: List<Pair<String, Map<String, String>>>): CausalGraph {
        val bounded = steps.take(maxNodes)
        val nodes = bounded.mapIndexed { idx, (toolName, params) ->
            analyzeToolCall(idx, toolName, params)
        }
        val conflicts = detectConflicts(nodes)
        return CausalGraph(nodes, conflicts)
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     */
    fun detectConflicts(nodes: List<CausalNode>): List<CausalConflict> {
        val conflicts = mutableListOf<CausalConflict>()
        // [Localized]: path → [Localized] [Localized] [Localized]
        val lastCreate  = mutableMapOf<String, Int>()
        val lastDelete  = mutableMapOf<String, Int>()
        val lastModify  = mutableMapOf<String, Int>()
        val lastRead    = mutableMapOf<String, Int>()

        for (node in nodes) {
            val i = node.stepIndex
            for (eff in node.effects) {
                val path = eff.targetPath ?: continue
                when (eff.type) {
                    EffectType.READ -> {
                        lastDelete[path]?.let { delIdx ->
                            conflicts += CausalConflict(
                                type = ConflictType.READ_AFTER_DELETE,
                                stepA = delIdx, stepB = i, path = path,
                                message = "⚠️ [Localized] $i [Localized] '$path' [Localized] [Localized] [Localized] [Localized] $delIdx.",
                                isFatal = true
                            )
                        }
                        lastRead[path] = i
                    }
                    EffectType.MODIFY -> {
                        lastDelete[path]?.let { delIdx ->
                            conflicts += CausalConflict(
                                type = ConflictType.MODIFY_AFTER_DELETE,
                                stepA = delIdx, stepB = i, path = path,
                                message = "❌ [Localized] $i [Localized] '$path' [Localized] [Localized] [Localized] [Localized] $delIdx.",
                                isFatal = true
                            )
                        }
                        lastModify[path] = i
                    }
                    EffectType.CREATE -> {
                        lastCreate[path]?.let { prevIdx ->
                            if (lastDelete[path] == null || (lastDelete[path] ?: -1) < prevIdx) {
                                conflicts += CausalConflict(
                                    type = ConflictType.DOUBLE_CREATE,
                                    stepA = prevIdx, stepB = i, path = path,
                                    message = "⚠️ [Localized] $i [Localized] '$path' [Localized] [Localized] ([Localized] [Localized] [Localized] [Localized] [Localized] $prevIdx).",
                                    isFatal = false
                                )
                            }
                        }
                        lastCreate[path] = i
                    }
                    EffectType.DELETE -> {
                        lastModify[path]?.let { modIdx ->
                            val readAfterMod = (lastRead[path] ?: -1) > modIdx
                            if (!readAfterMod) {
                                conflicts += CausalConflict(
                                    type = ConflictType.DELETE_AFTER_MODIFY,
                                    stepA = modIdx, stepB = i, path = path,
                                    message = "⚠️ [Localized] $i [Localized] '$path' [Localized] [Localized] [Localized] [Localized] $modIdx — [Localized] [Localized].",
                                    isFatal = false
                                )
                            }
                        }
                        lastDelete[path] = i
                    }
                    EffectType.EXECUTE -> {
                        // CRITICAL risk [Localized] [Localized] [Localized] (rm -rf, git reset --hard, etc.)
                        if (node.riskLevel == RiskLevel.CRITICAL) {
                            conflicts += CausalConflict(
                                type = ConflictType.CRITICAL_COMMAND,
                                stepA = i, stepB = -1, path = path,
                                message = "💥 [Localized] $i [Localized] [Localized] [Localized]: ${node.humanSummary}",
                                isFatal = false // [Localized] [Localized] [Localized] [Localized]
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
        return conflicts
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     * [Localized] "[Localized] [Localized] [Localized]" [Localized] [Localized] [Localized] [Localized] [Localized].
     */
    fun simulate(graph: CausalGraph): SimulationResult {
        val warnings = mutableListOf<String>()
        var state = VirtualState.EMPTY
        val steps = mutableListOf<SimulationStep>()
        var firstFailure = -1

        for (node in graph.nodes) {
            val (wouldSucceed, failReason, newState) = simulateStep(node, state)
            if (!wouldSucceed && firstFailure == -1) firstFailure = node.stepIndex
            steps += SimulationStep(
                stepIndex = node.stepIndex,
                toolName = node.toolName,
                humanSummary = node.humanSummary,
                riskLevel = node.riskLevel,
                wouldSucceed = wouldSucceed,
                failReason = failReason,
                virtualStateAfter = newState
            )
            state = newState
        }

        if (graph.highestRisk >= RiskLevel.HIGH) {
            warnings += "⚠️ [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] — [Localized] [Localized] [Localized] [Localized] [Localized] (rollback group)."
        }
        if (graph.nodes.count { it.riskLevel == RiskLevel.CRITICAL } > 0) {
            warnings += "💥 [Localized]: [Localized] ${graph.nodes.count { it.riskLevel == RiskLevel.CRITICAL }} [Localized]([Localized]) [Localized]([Localized]) [Localized] [Localized]."
        }
        for (c in graph.conflicts) {
            if (c.isFatal) warnings += c.message
        }

        return SimulationResult(
            steps = steps,
            overallSuccess = firstFailure == -1,
            firstFailureIndex = firstFailure,
            warningMessages = warnings
        )
    }

    /**
     * [Localized] What-If: "[Localized] [Localized] [Localized] [Localized]/[Localized] [Localized] [Localized]"
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     */
    fun whatIf(
        baseline: CausalGraph,
        insertStep: Pair<String, Map<String, String>>? = null,
        removeStepIndex: Int? = null
    ): String {
        val modifiedSteps = baseline.nodes
            .filter { removeStepIndex == null || it.stepIndex != removeStepIndex }
            .map { it.toolName to it.parameters }
            .toMutableList()

        insertStep?.let { modifiedSteps += it }
        val modifiedGraph = buildChain(modifiedSteps)
        val baseSim = simulate(baseline)
        val modSim = simulate(modifiedGraph)

        return buildString {
            appendLine("🔬 [Localized] What-If:")
            appendLine()
            when {
                removeStepIndex != null -> {
                    val removed = baseline.nodes.find { it.stepIndex == removeStepIndex }
                    appendLine("❌ [Localized] [Localized] [Localized] $removeStepIndex (${removed?.toolName ?: "?"}):")
                }
                insertStep != null ->
                    appendLine("➕ [Localized] [Localized] [Localized] [Localized] (${insertStep.first}):")
                else ->
                    appendLine("📊 [Localized] [Localized]:")
            }
            appendLine()

            val baseConflicts = baseline.conflicts.size
            val modConflicts = modifiedGraph.conflicts.size
            when {
                modConflicts < baseConflicts ->
                    appendLine("✅ [Localized] [Localized] [Localized]: $baseConflicts → $modConflicts")
                modConflicts > baseConflicts ->
                    appendLine("⚠️ [Localized] [Localized] [Localized]: $baseConflicts → $modConflicts")
                else ->
                    appendLine("ℹ️ [Localized] [Localized] [Localized] [Localized]: $modConflicts")
            }

            val baseRisk = baseline.highestRisk
            val modRisk = modifiedGraph.highestRisk
            if (modRisk.score > baseRisk.score)
                appendLine("⬆️ [Localized] [Localized]: ${baseRisk.label()} → ${modRisk.label()}")
            else if (modRisk.score < baseRisk.score)
                appendLine("⬇️ [Localized] [Localized]: ${baseRisk.label()} → ${modRisk.label()}")

            val baseSuccess = baseSim.overallSuccess
            val modSuccess = modSim.overallSuccess
            when {
                !baseSuccess && modSuccess  -> appendLine("🎉 [Localized] [Localized] [Localized] [Localized]!")
                baseSuccess && !modSuccess  -> appendLine("💔 [Localized] [Localized] [Localized] [Localized]!")
                else                         -> appendLine("ℹ️ [Localized] [Localized] [Localized] [Localized] (${if (modSuccess) "[Localized]" else "[Localized]"})")
            }

            if (modifiedGraph.conflicts.isNotEmpty()) {
                appendLine()
                appendLine("📋 [Localized] [Localized] [Localized] [Localized]:")
                for (c in modifiedGraph.conflicts.take(5)) {
                    appendLine("  • ${c.message}")
                }
            }
        }
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] system prompt [Localized] [Localized] [Localized].
     * [Localized] [Localized] [maxChars] [Localized] context window.
     */
    fun buildPromptInjection(graph: CausalGraph, maxChars: Int = 700): String {
        if (graph.nodes.isEmpty()) return ""
        val fatalConflicts = graph.conflicts.filter { it.isFatal }
        val warnings = graph.conflicts.filter { !it.isFatal }
        if (fatalConflicts.isEmpty() && warnings.isEmpty() && graph.highestRisk < RiskLevel.HIGH) return ""

        return buildString {
            appendLine("\n🗺️ [Localized] [Localized] [Localized] (Causal Chain):")
            appendLine("  [Localized]: ${graph.nodes.size} | [Localized] [Localized]: ${graph.highestRisk.label()}")

            if (fatalConflicts.isNotEmpty()) {
                appendLine("❌ [Localized] [Localized]:")
                for (c in fatalConflicts.take(3)) {
                    val line = "  • ${c.message.take(120)}"
                    if (length + line.length > maxChars) return@buildString
                    appendLine(line)
                }
            }
            if (warnings.isNotEmpty()) {
                appendLine("⚠️ [Localized]:")
                for (c in warnings.take(3)) {
                    val line = "  • ${c.message.take(100)}"
                    if (length + line.length > maxChars) return@buildString
                    appendLine(line)
                }
            }
        }.trimEnd()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private data class ToolRule(
        val effects: List<CausalEffect>,
        val preconditions: List<String>,
        val riskLevel: RiskLevel,
        val humanSummary: String
    )

    /** [Localized] [Localized] [Localized] + [Localized] [Localized] [Localized] [Localized]. */
    private fun findRule(toolName: String, params: Map<String, String>): ToolRule {
        val path = params["path"]?.trim()
            ?: params["file_path"]?.trim()
            ?: params["target"]?.trim()

        return when (toolName) {
            // ── File Read Operations (LOW risk) ──────────────────────────────
            "read_file_lines", "read_file" ->
                readRule(path, "[Localized] [Localized]")
            "multi_read" ->
                readRule(path, "[Localized] [Localized] [Localized]")
            "search_codebase" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.READ, null, "[Localized] [Localized] [Localized] [Localized]")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🔍 [Localized] [Localized] [Localized]: ${params["query"]?.take(40) ?: "?"}"
                )

            // ── File Create Operations ────────────────────────────────────────
            "create_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.CREATE, path, "[Localized] [Localized] [Localized]")),
                    preconditions = if (path != null) listOf("'$path' [Localized] [Localized]") else emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "📄 [Localized]: ${path ?: "?"}"
                )

            // ── File Modify Operations ────────────────────────────────────────
            "patch_file_content", "multi_patch_file_content",
            "delete_text", "delete_lines", "insert_lines",
            "replace_lines", "append_to_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.MODIFY, path, "[Localized] [Localized]")),
                    preconditions = if (path != null) listOf("'$path' [Localized]") else emptyList(),
                    riskLevel = RiskLevel.MEDIUM,
                    humanSummary = "✏️ [Localized]: ${path ?: "?"}"
                )

            // ── Destructive: clear_file ───────────────────────────────────────
            "clear_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.MODIFY, path, "[Localized] [Localized] [Localized] [Localized]")),
                    preconditions = if (path != null) listOf("'$path' [Localized]") else emptyList(),
                    riskLevel = RiskLevel.HIGH,
                    humanSummary = "🗑️ [Localized] [Localized] [Localized]: ${path ?: "?"}"
                )

            // ── Delete Operations ─────────────────────────────────────────────
            "delete_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.DELETE, path, "[Localized] [Localized]")),
                    preconditions = if (path != null) listOf("'$path' [Localized]") else emptyList(),
                    riskLevel = RiskLevel.HIGH,
                    humanSummary = "🗑️ [Localized] [Localized]: ${path ?: "?"}"
                )

            // ── Terminal / Shell ──────────────────────────────────────────────
            "run_terminal", "python_runner" ->
                analyzeShellCommand(toolName, params)

            // ── Git Operations ────────────────────────────────────────────────
            "git_manager" ->
                analyzeGitAction(params)

            // ── Web / Network (LOW risk read-only) ────────────────────────────
            "web_search", "web_search_deep", "web_scraper", "scrape_multiple",
            "network_request" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.NETWORK, null, "[Localized] [Localized]")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🌐 [Localized] [Localized]: $toolName"
                )

            // ── Memory Operations (LOW risk) ──────────────────────────────────
            "remember_fact", "update_memory", "delete_memory",
            "vector_store", "brain_record_episode" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.MODIFY, null, "[Localized] [Localized]")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🧠 [Localized] [Localized]: $toolName"
                )

            // ── System Tools ──────────────────────────────────────────────────
            "hardware_toggle_tool", "vpn_control", "system_power" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.SYSTEM, null, "[Localized] [Localized] [Localized]")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.MEDIUM,
                    humanSummary = "⚙️ [Localized] [Localized]: $toolName"
                )

            // ── Default: unknown tool treated as low-risk read ────────────────
            else ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.READ, null, "[Localized] [Localized] [Localized]")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🔧 $toolName"
                )
        }
    }

    private fun readRule(path: String?, label: String): ToolRule = ToolRule(
        effects = listOf(CausalEffect(EffectType.READ, path, label)),
        preconditions = if (path != null) listOf("'$path' [Localized]") else emptyList(),
        riskLevel = RiskLevel.LOW,
        humanSummary = "📖 $label: ${path ?: "?"}"
    )

    /** [Localized] [Localized] [Localized] Shell [Localized] [Localized] [Localized]. */
    private fun analyzeShellCommand(toolName: String, params: Map<String, String>): ToolRule {
        val command = (params["command"] ?: params["code"] ?: "").lowercase()
        val (riskLevel, description) = when {
            "rm -rf" in command || "rm -r" in command ->
                RiskLevel.CRITICAL to "[Localized] [Localized] — [Localized] [Localized] [Localized]"
            Regex("""^rm\s""").containsMatchIn(command) || "unlink" in command ->
                RiskLevel.HIGH to "[Localized] [Localized]([Localized])"
            "git reset --hard" in command || "git clean -fd" in command ->
                RiskLevel.HIGH to "[Localized] [Localized] Git [Localized]"
            "git push --force" in command || "git push -f" in command ->
                RiskLevel.HIGH to "Git force push — [Localized] [Localized] [Localized] [Localized] [Localized]"
            "chmod 777" in command || "chmod -r" in command.replace(" ", "").replace("--", "-") ->
                RiskLevel.MEDIUM to "[Localized] [Localized] [Localized]"
            "apt install" in command || "pkg install" in command || "pip install" in command ->
                RiskLevel.LOW to "[Localized] [Localized]"
            "mkfs" in command || "fdisk" in command || "dd if=" in command ->
                RiskLevel.CRITICAL to "[Localized] [Localized]/[Localized] [Localized] — [Localized] [Localized]"
            else ->
                RiskLevel.MEDIUM to "[Localized] [Localized]: ${command.take(60)}"
        }
        val effectPath = extractPathFromCommand(command)

        // [Localized] [Localized] (rm, unlink) [Localized] [Localized] DELETE [Localized] [Localized] [Localized] READ_AFTER_DELETE/MODIFY_AFTER_DELETE
        // [Localized] [Localized] [Localized] [Localized] [Localized] EXECUTE (git reset, mkfs, dd[Localized] [Localized])
        val effectType = when {
            "rm -rf" in command || "rm -r" in command -> EffectType.DELETE
            Regex("""^rm\s""").containsMatchIn(command) || "unlink" in command -> EffectType.DELETE
            else -> EffectType.EXECUTE
        }

        return ToolRule(
            effects = listOf(
                CausalEffect(
                    type = effectType,
                    targetPath = effectPath,
                    description = description
                )
            ),
            preconditions = emptyList(),
            riskLevel = riskLevel,
            humanSummary = "${riskLevel.label()} [Localized]: ${command.take(80)}"
        )
    }

    /** [Localized] [Localized] Git [Localized] [Localized] [Localized]. */
    private fun analyzeGitAction(params: Map<String, String>): ToolRule {
        val action = params["action"]?.lowercase() ?: ""
        val (risk, desc, effType) = when {
            action in listOf("push", "force_push") ->
                Triple(RiskLevel.MEDIUM, "[Localized] [Localized]", EffectType.NETWORK)
            action in listOf("reset", "clean") ->
                Triple(RiskLevel.HIGH, "[Localized] [Localized] Git", EffectType.SYSTEM)
            action in listOf("merge", "rebase") ->
                Triple(RiskLevel.MEDIUM, "[Localized]/[Localized] [Localized] [Localized]", EffectType.MODIFY)
            action in listOf("commit", "add", "stage") ->
                Triple(RiskLevel.LOW, "[Localized] [Localized]", EffectType.SYSTEM)
            action in listOf("clone", "fetch", "pull") ->
                Triple(RiskLevel.LOW, "[Localized] [Localized]", EffectType.NETWORK)
            action in listOf("branch_delete", "tag_delete") ->
                Triple(RiskLevel.HIGH, "[Localized] [Localized]/[Localized]", EffectType.DELETE)
            else ->
                Triple(RiskLevel.LOW, "[Localized] Git: $action", EffectType.READ)
        }
        return ToolRule(
            effects = listOf(CausalEffect(effType, null, desc)),
            preconditions = emptyList(),
            riskLevel = risk,
            humanSummary = "🔀 Git $action: $desc"
        )
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     * [Localized] (wouldSucceed, failReason, newState).
     */
    private fun simulateStep(
        node: CausalNode,
        state: VirtualState
    ): Triple<Boolean, String?, VirtualState> {
        // [Localized] [Localized] [Localized] [Localized] [Localized]
        for (precondition in node.preconditions) {
            // [Localized] [Localized] [Localized] [Localized] [Localized] ([Localized]: "'path/file' [Localized]")
            val pathMatch = Regex("'([^']+)'").find(precondition)
            val requiredPath = pathMatch?.groupValues?.get(1) ?: continue
            if (precondition.contains("[Localized]") && state.wasDeleted(requiredPath)) {
                return Triple(false, "[Localized] '$requiredPath' [Localized] [Localized] [Localized] [Localized] [Localized].", state)
            }
        }

        // [Localized] [Localized] [Localized] [Localized] [Localized]
        var created = state.createdPaths.toMutableSet()
        var deleted = state.deletedPaths.toMutableSet()
        var modified = state.modifiedPaths.toMutableSet()
        var read = state.readPaths.toMutableSet()

        for (eff in node.effects) {
            val p = eff.targetPath ?: continue
            when (eff.type) {
                EffectType.CREATE  -> { created += p; deleted -= p }
                EffectType.DELETE  -> { deleted += p; created -= p; modified -= p }
                EffectType.MODIFY  -> modified += p
                EffectType.READ    -> read += p
                else               -> {}
            }
        }

        return Triple(
            true, null,
            VirtualState(created, deleted, modified, read)
        )
    }

    /** [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] shell [Localized] [Localized]. */
    private fun extractPathFromCommand(command: String): String? {
        val pathRegex = Regex("""[/~][^\s'"]+|'([^']+)'|"([^"]+)"""")
        return pathRegex.find(command)?.let {
            it.groupValues.drop(1).firstOrNull { g -> g.isNotBlank() } ?: it.value
        }
    }
}
