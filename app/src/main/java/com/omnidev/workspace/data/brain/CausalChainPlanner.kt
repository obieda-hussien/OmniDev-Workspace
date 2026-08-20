package com.omnidev.workspace.data.brain

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * CausalChainPlanner — System awareness note Multi-step Causal Chain Planning (Mobile-First)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note "System awareness note System awareness note" (Causal Graph)
 * System awareness note System awareness note System awareness note System awareness note System awareness note (System awareness note: System awareness note System awareness note System awareness note System awareness note System awareness note).
 *
 * ## System awareness note System awareness note:
 * 1. **System awareness note System awareness note** (analyzeToolCall): System awareness note System awareness note System awareness note → System awareness note System awareness note System awareness note + System awareness note
 * 2. **System awareness note System awareness note** (buildChain): System awareness note DAG System awareness note System awareness note System awareness note System awareness note
 * 3. **System awareness note System awareness note** (detectConflicts): System awareness note System awareness note System awareness note System awareness note (Read-After-DeleteSystem awareness note System awareness note)
 * 4. **System awareness note System awareness note** (simulate): System awareness note System awareness note System awareness note System awareness note System awareness note
 * 5. **System awareness note What-If** (whatIf): System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
 * 6. **System awareness note System awareness note Prompt** (buildPromptInjection): System awareness note System awareness note System awareness note System awareness note system prompt
 *
 * ## Mobile-First (4 GB RAM System awareness note System awareness note):
 * - System awareness note System awareness note LLM System awareness note — System awareness note System awareness note rule-based
 * - System awareness note System awareness note System awareness note — System awareness note System awareness note System awareness note (in-memory)
 * - System awareness note System awareness note [maxNodes] System awareness note System awareness note System awareness note
 * - System awareness note System awareness note < 5ms System awareness note 20 System awareness note
 */
class CausalChainPlanner(
    /** System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note (System awareness note System awareness note OOM). */
    val maxNodes: Int = 50
) {

    // ──────────────────────────────────────────────────────────────────────────
    // Data classes & enums (public so CausalChainPlannerTool can read them)
    // ──────────────────────────────────────────────────────────────────────────

    enum class EffectType {
        CREATE,   // System awareness note System awareness note System awareness note System awareness note System awareness note
        DELETE,   // System awareness note System awareness note System awareness note System awareness note
        MODIFY,   // System awareness note System awareness note System awareness note
        READ,     // System awareness note System awareness note (System awareness note System awareness note System awareness note)
        EXECUTE,  // System awareness note System awareness note System awareness note Shell System awareness note System awareness note System awareness note
        SYSTEM,   // System awareness note System awareness note System awareness note System awareness note (System awareness note System awareness note GitSystem awareness note System awareness note)
        NETWORK   // System awareness note System awareness note System awareness note System awareness note
    }

    enum class RiskLevel(val score: Int) {
        LOW(1), MEDIUM(2), HIGH(3), CRITICAL(4);

        fun label(): String = when (this) {
            LOW      -> "🟢 System awareness note"
            MEDIUM   -> "🟡 System awareness note"
            HIGH     -> "🔴 System awareness note"
            CRITICAL -> "💥 System awareness note"
        }
    }

    enum class ConflictType {
        READ_AFTER_DELETE,      // System awareness note System awareness note System awareness note System awareness note
        MODIFY_AFTER_DELETE,    // System awareness note System awareness note System awareness note System awareness note
        DOUBLE_CREATE,          // System awareness note System awareness note System awareness note System awareness note
        OVERWRITE_UNREAD,       // System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note (System awareness note System awareness note System awareness note)
        DELETE_AFTER_MODIFY,    // System awareness note System awareness note System awareness note System awareness note (System awareness note System awareness note)
        CRITICAL_COMMAND,       // System awareness note System awareness note System awareness note System awareness note (rm -rf, git reset --hard)
        CIRCULAR_DEPENDENCY,    // A System awareness note System awareness note B System awareness note B System awareness note System awareness note A
    }

    data class CausalEffect(
        val type: EffectType,
        /** System awareness note System awareness note System awareness note System awareness note (null System awareness note System awareness note System awareness note System awareness note). */
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
        val stepA: Int,       // System awareness note System awareness note System awareness note
        val stepB: Int,       // System awareness note System awareness note System awareness note (-1 System awareness note System awareness note System awareness note)
        val path: String?,
        val message: String,
        val isFatal: Boolean  // System awareness note System awareness note System awareness note System awareness note
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
        val firstFailureIndex: Int,   // -1 System awareness note System awareness note System awareness note
        val warningMessages: List<String>
    )

    /**
     * System awareness note System awareness note System awareness note/System awareness note System awareness note System awareness note.
     * System awareness note System awareness note — System awareness note sets System awareness note System awareness note.
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
     * System awareness note System awareness note System awareness note System awareness note System awareness note [CausalNode] System awareness note System awareness note.
     * System awareness note 100% rule-based — System awareness note LLMSystem awareness note System awareness note System awareness note System awareness note < 1ms.
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
     * System awareness note [CausalGraph] System awareness note System awareness note System awareness note System awareness note System awareness note.
     * System awareness note System awareness note System awareness note System awareness note System awareness note.
     * System awareness note System awareness note System awareness note [maxNodes]System awareness note System awareness note System awareness note.
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
     * System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
     */
    fun detectConflicts(nodes: List<CausalNode>): List<CausalConflict> {
        val conflicts = mutableListOf<CausalConflict>()
        // System awareness note: path → System awareness note System awareness note System awareness note
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
                                message = "⚠️ System awareness note $i System awareness note '$path' System awareness note System awareness note System awareness note System awareness note $delIdx.",
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
                                message = "❌ System awareness note $i System awareness note '$path' System awareness note System awareness note System awareness note System awareness note $delIdx.",
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
                                    message = "⚠️ System awareness note $i System awareness note '$path' System awareness note System awareness note (System awareness note System awareness note System awareness note System awareness note System awareness note $prevIdx).",
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
                                    message = "⚠️ System awareness note $i System awareness note '$path' System awareness note System awareness note System awareness note System awareness note $modIdx — System awareness note System awareness note.",
                                    isFatal = false
                                )
                            }
                        }
                        lastDelete[path] = i
                    }
                    EffectType.EXECUTE -> {
                        // CRITICAL risk System awareness note System awareness note System awareness note (rm -rf, git reset --hard, etc.)
                        if (node.riskLevel == RiskLevel.CRITICAL) {
                            conflicts += CausalConflict(
                                type = ConflictType.CRITICAL_COMMAND,
                                stepA = i, stepB = -1, path = path,
                                message = "💥 System awareness note $i System awareness note System awareness note System awareness note: ${node.humanSummary}",
                                isFatal = false // System awareness note System awareness note System awareness note System awareness note
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
     * System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
     * System awareness note "System awareness note System awareness note System awareness note" System awareness note System awareness note System awareness note System awareness note System awareness note.
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
            warnings += "⚠️ System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note — System awareness note System awareness note System awareness note System awareness note System awareness note (rollback group)."
        }
        if (graph.nodes.count { it.riskLevel == RiskLevel.CRITICAL } > 0) {
            warnings += "💥 System awareness note: System awareness note ${graph.nodes.count { it.riskLevel == RiskLevel.CRITICAL }} System awareness note(System awareness note) System awareness note(System awareness note) System awareness note System awareness note."
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
     * System awareness note What-If: "System awareness note System awareness note System awareness note System awareness note/System awareness note System awareness note System awareness note"
     * System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
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
            appendLine("🔬 System awareness note What-If:")
            appendLine()
            when {
                removeStepIndex != null -> {
                    val removed = baseline.nodes.find { it.stepIndex == removeStepIndex }
                    appendLine("❌ System awareness note System awareness note System awareness note $removeStepIndex (${removed?.toolName ?: "?"}):")
                }
                insertStep != null ->
                    appendLine("➕ System awareness note System awareness note System awareness note System awareness note (${insertStep.first}):")
                else ->
                    appendLine("📊 System awareness note System awareness note:")
            }
            appendLine()

            val baseConflicts = baseline.conflicts.size
            val modConflicts = modifiedGraph.conflicts.size
            when {
                modConflicts < baseConflicts ->
                    appendLine("✅ System awareness note System awareness note System awareness note: $baseConflicts → $modConflicts")
                modConflicts > baseConflicts ->
                    appendLine("⚠️ System awareness note System awareness note System awareness note: $baseConflicts → $modConflicts")
                else ->
                    appendLine("ℹ️ System awareness note System awareness note System awareness note System awareness note: $modConflicts")
            }

            val baseRisk = baseline.highestRisk
            val modRisk = modifiedGraph.highestRisk
            if (modRisk.score > baseRisk.score)
                appendLine("⬆️ System awareness note System awareness note: ${baseRisk.label()} → ${modRisk.label()}")
            else if (modRisk.score < baseRisk.score)
                appendLine("⬇️ System awareness note System awareness note: ${baseRisk.label()} → ${modRisk.label()}")

            val baseSuccess = baseSim.overallSuccess
            val modSuccess = modSim.overallSuccess
            when {
                !baseSuccess && modSuccess  -> appendLine("🎉 System awareness note System awareness note System awareness note System awareness note!")
                baseSuccess && !modSuccess  -> appendLine("💔 System awareness note System awareness note System awareness note System awareness note!")
                else                         -> appendLine("ℹ️ System awareness note System awareness note System awareness note System awareness note (${if (modSuccess) "System awareness note" else "System awareness note"})")
            }

            if (modifiedGraph.conflicts.isNotEmpty()) {
                appendLine()
                appendLine("📋 System awareness note System awareness note System awareness note System awareness note:")
                for (c in modifiedGraph.conflicts.take(5)) {
                    appendLine("  • ${c.message}")
                }
            }
        }
    }

    /**
     * System awareness note System awareness note System awareness note System awareness note system prompt System awareness note System awareness note System awareness note.
     * System awareness note System awareness note [maxChars] System awareness note context window.
     */
    fun buildPromptInjection(graph: CausalGraph, maxChars: Int = 700): String {
        if (graph.nodes.isEmpty()) return ""
        val fatalConflicts = graph.conflicts.filter { it.isFatal }
        val warnings = graph.conflicts.filter { !it.isFatal }
        if (fatalConflicts.isEmpty() && warnings.isEmpty() && graph.highestRisk < RiskLevel.HIGH) return ""

        return buildString {
            appendLine("\n🗺️ System awareness note System awareness note System awareness note (Causal Chain):")
            appendLine("  System awareness note: ${graph.nodes.size} | System awareness note System awareness note: ${graph.highestRisk.label()}")

            if (fatalConflicts.isNotEmpty()) {
                appendLine("❌ System awareness note System awareness note:")
                for (c in fatalConflicts.take(3)) {
                    val line = "  • ${c.message.take(120)}"
                    if (length + line.length > maxChars) return@buildString
                    appendLine(line)
                }
            }
            if (warnings.isNotEmpty()) {
                appendLine("⚠️ System awareness note:")
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

    /** System awareness note System awareness note System awareness note + System awareness note System awareness note System awareness note System awareness note. */
    private fun findRule(toolName: String, params: Map<String, String>): ToolRule {
        val path = params["path"]?.trim()
            ?: params["file_path"]?.trim()
            ?: params["target"]?.trim()

        return when (toolName) {
            // ── File Read Operations (LOW risk) ──────────────────────────────
            "read_file_lines", "read_file" ->
                readRule(path, "System awareness note System awareness note")
            "multi_read" ->
                readRule(path, "System awareness note System awareness note System awareness note")
            "search_codebase" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.READ, null, "System awareness note System awareness note System awareness note System awareness note")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🔍 System awareness note System awareness note System awareness note: ${params["query"]?.take(40) ?: "?"}"
                )

            // ── File Create Operations ────────────────────────────────────────
            "create_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.CREATE, path, "System awareness note System awareness note System awareness note")),
                    preconditions = if (path != null) listOf("'$path' System awareness note System awareness note") else emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "📄 System awareness note: ${path ?: "?"}"
                )

            // ── File Modify Operations ────────────────────────────────────────
            "patch_file_content", "multi_patch_file_content",
            "delete_text", "delete_lines", "insert_lines",
            "replace_lines", "append_to_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.MODIFY, path, "System awareness note System awareness note")),
                    preconditions = if (path != null) listOf("'$path' System awareness note") else emptyList(),
                    riskLevel = RiskLevel.MEDIUM,
                    humanSummary = "✏️ System awareness note: ${path ?: "?"}"
                )

            // ── Destructive: clear_file ───────────────────────────────────────
            "clear_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.MODIFY, path, "System awareness note System awareness note System awareness note System awareness note")),
                    preconditions = if (path != null) listOf("'$path' System awareness note") else emptyList(),
                    riskLevel = RiskLevel.HIGH,
                    humanSummary = "🗑️ System awareness note System awareness note System awareness note: ${path ?: "?"}"
                )

            // ── Delete Operations ─────────────────────────────────────────────
            "delete_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.DELETE, path, "System awareness note System awareness note")),
                    preconditions = if (path != null) listOf("'$path' System awareness note") else emptyList(),
                    riskLevel = RiskLevel.HIGH,
                    humanSummary = "🗑️ System awareness note System awareness note: ${path ?: "?"}"
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
                    effects = listOf(CausalEffect(EffectType.NETWORK, null, "System awareness note System awareness note")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🌐 System awareness note System awareness note: $toolName"
                )

            // ── Memory Operations (LOW risk) ──────────────────────────────────
            "remember_fact", "update_memory", "delete_memory",
            "vector_store", "brain_record_episode" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.MODIFY, null, "System awareness note System awareness note")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🧠 System awareness note System awareness note: $toolName"
                )

            // ── System Tools ──────────────────────────────────────────────────
            "hardware_toggle_tool", "vpn_control", "system_power" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.SYSTEM, null, "System awareness note System awareness note System awareness note")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.MEDIUM,
                    humanSummary = "⚙️ System awareness note System awareness note: $toolName"
                )

            // ── Default: unknown tool treated as low-risk read ────────────────
            else ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.READ, null, "System awareness note System awareness note System awareness note")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🔧 $toolName"
                )
        }
    }

    private fun readRule(path: String?, label: String): ToolRule = ToolRule(
        effects = listOf(CausalEffect(EffectType.READ, path, label)),
        preconditions = if (path != null) listOf("'$path' System awareness note") else emptyList(),
        riskLevel = RiskLevel.LOW,
        humanSummary = "📖 $label: ${path ?: "?"}"
    )

    /** System awareness note System awareness note System awareness note Shell System awareness note System awareness note System awareness note. */
    private fun analyzeShellCommand(toolName: String, params: Map<String, String>): ToolRule {
        val command = (params["command"] ?: params["code"] ?: "").lowercase()
        val (riskLevel, description) = when {
            "rm -rf" in command || "rm -r" in command ->
                RiskLevel.CRITICAL to "System awareness note System awareness note — System awareness note System awareness note System awareness note"
            Regex("""^rm\s""").containsMatchIn(command) || "unlink" in command ->
                RiskLevel.HIGH to "System awareness note System awareness note(System awareness note)"
            "git reset --hard" in command || "git clean -fd" in command ->
                RiskLevel.HIGH to "System awareness note System awareness note Git System awareness note"
            "git push --force" in command || "git push -f" in command ->
                RiskLevel.HIGH to "Git force push — System awareness note System awareness note System awareness note System awareness note System awareness note"
            "chmod 777" in command || "chmod -r" in command.replace(" ", "").replace("--", "-") ->
                RiskLevel.MEDIUM to "System awareness note System awareness note System awareness note"
            "apt install" in command || "pkg install" in command || "pip install" in command ->
                RiskLevel.LOW to "System awareness note System awareness note"
            "mkfs" in command || "fdisk" in command || "dd if=" in command ->
                RiskLevel.CRITICAL to "System awareness note System awareness note/System awareness note System awareness note — System awareness note System awareness note"
            else ->
                RiskLevel.MEDIUM to "System awareness note System awareness note: ${command.take(60)}"
        }
        val effectPath = extractPathFromCommand(command)

        // System awareness note System awareness note (rm, unlink) System awareness note System awareness note DELETE System awareness note System awareness note System awareness note READ_AFTER_DELETE/MODIFY_AFTER_DELETE
        // System awareness note System awareness note System awareness note System awareness note System awareness note EXECUTE (git reset, mkfs, ddSystem awareness note System awareness note)
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
            humanSummary = "${riskLevel.label()} System awareness note: ${command.take(80)}"
        )
    }

    /** System awareness note System awareness note Git System awareness note System awareness note System awareness note. */
    private fun analyzeGitAction(params: Map<String, String>): ToolRule {
        val action = params["action"]?.lowercase() ?: ""
        val (risk, desc, effType) = when {
            action in listOf("push", "force_push") ->
                Triple(RiskLevel.MEDIUM, "System awareness note System awareness note", EffectType.NETWORK)
            action in listOf("reset", "clean") ->
                Triple(RiskLevel.HIGH, "System awareness note System awareness note Git", EffectType.SYSTEM)
            action in listOf("merge", "rebase") ->
                Triple(RiskLevel.MEDIUM, "System awareness note/System awareness note System awareness note System awareness note", EffectType.MODIFY)
            action in listOf("commit", "add", "stage") ->
                Triple(RiskLevel.LOW, "System awareness note System awareness note", EffectType.SYSTEM)
            action in listOf("clone", "fetch", "pull") ->
                Triple(RiskLevel.LOW, "System awareness note System awareness note", EffectType.NETWORK)
            action in listOf("branch_delete", "tag_delete") ->
                Triple(RiskLevel.HIGH, "System awareness note System awareness note/System awareness note", EffectType.DELETE)
            else ->
                Triple(RiskLevel.LOW, "System awareness note Git: $action", EffectType.READ)
        }
        return ToolRule(
            effects = listOf(CausalEffect(effType, null, desc)),
            preconditions = emptyList(),
            riskLevel = risk,
            humanSummary = "🔀 Git $action: $desc"
        )
    }

    /**
     * System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note.
     * System awareness note (wouldSucceed, failReason, newState).
     */
    private fun simulateStep(
        node: CausalNode,
        state: VirtualState
    ): Triple<Boolean, String?, VirtualState> {
        // System awareness note System awareness note System awareness note System awareness note System awareness note
        for (precondition in node.preconditions) {
            // System awareness note System awareness note System awareness note System awareness note System awareness note (System awareness note: "'path/file' System awareness note")
            val pathMatch = Regex("'([^']+)'").find(precondition)
            val requiredPath = pathMatch?.groupValues?.get(1) ?: continue
            if (precondition.contains("System awareness note") && state.wasDeleted(requiredPath)) {
                return Triple(false, "System awareness note '$requiredPath' System awareness note System awareness note System awareness note System awareness note System awareness note.", state)
            }
        }

        // System awareness note System awareness note System awareness note System awareness note System awareness note
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

    /** System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note shell System awareness note System awareness note. */
    private fun extractPathFromCommand(command: String): String? {
        val pathRegex = Regex("""[/~][^\s'"]+|'([^']+)'|"([^"]+)"""")
        return pathRegex.find(command)?.let {
            it.groupValues.drop(1).firstOrNull { g -> g.isNotBlank() } ?: it.value
        }
    }
}
