package com.omnidev.workspace.data.brain

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * CausalChainPlanner — Context note Context note Context note Context note Context note (Mobile-First)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Context note Context note Context note Context note Context note Context note Context note Context note Context note Context note "Context note Context note" (Causal Graph)
 * Context note Context note Context note Context note Context note (Context note: Context note Context note Context note Context note Context note).
 *
 * ## Context note Context note:
 * 1. **Context note Context note** (analyzeToolCall): Context note Context note Context note → Context note Context note Context note + Context note
 * 2. **Context note Context note** (buildChain): Context note DAG Context note Context note Context note Context note
 * 3. **Context note Context note** (detectConflicts): Context note Context note Context note Context note (Read-After-DeleteContext note Context note)
 * 4. **Context note Context note** (simulate): Context note Context note Context note Context note Context note
 * 5. **Context note What-If** (whatIf): Context note Context note Context note Context note Context note Context note Context note
 * 6. **Context note Context note Prompt** (buildPromptInjection): Context note Context note Context note Context note system prompt
 *
 * ## Mobile-First (4 GB RAM Context note Context note):
 * - Context note Context note LLM Context note — Context note Context note rule-based
 * - Context note Context note Context note — Context note Context note Context note (in-memory)
 * - Context note Context note [maxNodes] Context note Context note Context note
 * - Context note Context note < 5ms Context note 20 Context note
 */
class CausalChainPlanner(
    /** Context note Context note Context note Context note Context note Context note Context note (Context note Context note OOM). */
    val maxNodes: Int = 50
) {

    // ──────────────────────────────────────────────────────────────────────────
    // Data classes & enums (public so CausalChainPlannerTool can read them)
    // ──────────────────────────────────────────────────────────────────────────

    enum class EffectType {
        CREATE,   // Context note Context note Context note Context note Context note
        DELETE,   // Context note Context note Context note Context note
        MODIFY,   // Context note Context note Context note
        READ,     // Context note Context note (Context note Context note Context note)
        EXECUTE,  // Context note Context note Context note Shell Context note Context note Context note
        SYSTEM,   // Context note Context note Context note Context note (Context note Context note GitContext note Context note)
        NETWORK   // Context note Context note Context note Context note
    }

    enum class RiskLevel(val score: Int) {
        LOW(1), MEDIUM(2), HIGH(3), CRITICAL(4);

        fun label(): String = when (this) {
            LOW      -> "🟢 Info"
            MEDIUM   -> "🟡 Info"
            HIGH     -> "🔴 Info"
            CRITICAL -> "💥 Info"
        }
    }

    enum class ConflictType {
        READ_AFTER_DELETE,      // Context note Context note Context note Context note
        MODIFY_AFTER_DELETE,    // Context note Context note Context note Context note
        DOUBLE_CREATE,          // Context note Context note Context note Context note
        OVERWRITE_UNREAD,       // Context note Context note Context note Context note Context note Context note (Context note Context note Context note)
        DELETE_AFTER_MODIFY,    // Context note Context note Context note Context note (Context note Context note)
        CRITICAL_COMMAND,       // Context note Context note Context note Context note (rm -rf, git reset --hard)
        CIRCULAR_DEPENDENCY,    // A Context note Context note B Context note B Context note Context note A
    }

    data class CausalEffect(
        val type: EffectType,
        /** Context note Context note Context note Context note (null Context note Context note Context note Context note). */
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
        val stepA: Int,       // Context note Context note Context note
        val stepB: Int,       // Context note Context note Context note (-1 Context note Context note Context note)
        val path: String?,
        val message: String,
        val isFatal: Boolean  // Context note Context note Context note Context note
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
        val firstFailureIndex: Int,   // -1 Context note Context note Context note
        val warningMessages: List<String>
    )

    /**
     * Context note Context note Context note/Context note Context note Context note.
     * Context note Context note — Context note sets Context note Context note.
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
     * Context note Context note Context note Context note Context note [CausalNode] Context note Context note.
     * Context note 100% rule-based — Context note LLMContext note Context note Context note Context note < 1ms.
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
     * Context note [CausalGraph] Context note Context note Context note Context note Context note.
     * Context note Context note Context note Context note Context note.
     * Context note Context note Context note [maxNodes]Context note Context note Context note.
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
     * Context note Context note Context note Context note Context note Context note Context note Context note.
     */
    fun detectConflicts(nodes: List<CausalNode>): List<CausalConflict> {
        val conflicts = mutableListOf<CausalConflict>()
        // Context note: path → Context note Context note Context note
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
                                message = "⚠️ Info $i Info '$path' Info Info Info Info $delIdx.",
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
                                message = "❌ Info $i Info '$path' Info Info Info Info $delIdx.",
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
                                    message = "⚠️ Info $i Info '$path' Info Info (Info Info Info Info Info $prevIdx).",
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
                                    message = "⚠️ Info $i Info '$path' Info Info Info Info $modIdx — Info Info.",
                                    isFatal = false
                                )
                            }
                        }
                        lastDelete[path] = i
                    }
                    EffectType.EXECUTE -> {
                        // CRITICAL risk Context note Context note Context note (rm -rf, git reset --hard, etc.)
                        if (node.riskLevel == RiskLevel.CRITICAL) {
                            conflicts += CausalConflict(
                                type = ConflictType.CRITICAL_COMMAND,
                                stepA = i, stepB = -1, path = path,
                                message = "💥 Info $i Info Info Info: ${node.humanSummary}",
                                isFatal = false // Context note Context note Context note Context note
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
     * Context note Context note Context note Context note Context note Context note Context note Context note Context note.
     * Context note "Context note Context note Context note" Context note Context note Context note Context note Context note.
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
            warnings += "⚠️ Info Info Info Info Info Info Info — Info Info Info Info Info (rollback group)."
        }
        if (graph.nodes.count { it.riskLevel == RiskLevel.CRITICAL } > 0) {
            warnings += "💥 Info: Info ${graph.nodes.count { it.riskLevel == RiskLevel.CRITICAL }} Info(Info) Info(Info) Info Info."
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
     * Context note What-If: "Context note Context note Context note Context note/Context note Context note Context note"
     * Context note Context note Context note Context note Context note Context note.
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
            appendLine("🔬 Info What-If:")
            appendLine()
            when {
                removeStepIndex != null -> {
                    val removed = baseline.nodes.find { it.stepIndex == removeStepIndex }
                    appendLine("❌ Info Info Info $removeStepIndex (${removed?.toolName ?: "?"}):")
                }
                insertStep != null ->
                    appendLine("➕ Info Info Info Info (${insertStep.first}):")
                else ->
                    appendLine("📊 Info Info:")
            }
            appendLine()

            val baseConflicts = baseline.conflicts.size
            val modConflicts = modifiedGraph.conflicts.size
            when {
                modConflicts < baseConflicts ->
                    appendLine("✅ Info Info Info: $baseConflicts → $modConflicts")
                modConflicts > baseConflicts ->
                    appendLine("⚠️ Info Info Info: $baseConflicts → $modConflicts")
                else ->
                    appendLine("ℹ️ Info Info Info Info: $modConflicts")
            }

            val baseRisk = baseline.highestRisk
            val modRisk = modifiedGraph.highestRisk
            if (modRisk.score > baseRisk.score)
                appendLine("⬆️ Info Info: ${baseRisk.label()} → ${modRisk.label()}")
            else if (modRisk.score < baseRisk.score)
                appendLine("⬇️ Info Info: ${baseRisk.label()} → ${modRisk.label()}")

            val baseSuccess = baseSim.overallSuccess
            val modSuccess = modSim.overallSuccess
            when {
                !baseSuccess && modSuccess  -> appendLine("🎉 Info Info Info Info!")
                baseSuccess && !modSuccess  -> appendLine("💔 Info Info Info Info!")
                else                         -> appendLine("ℹ️ Info Info Info Info (${if (modSuccess) "Info" else "Info"})")
            }

            if (modifiedGraph.conflicts.isNotEmpty()) {
                appendLine()
                appendLine("📋 Info Info Info Info:")
                for (c in modifiedGraph.conflicts.take(5)) {
                    appendLine("  • ${c.message}")
                }
            }
        }
    }

    /**
     * Context note Context note Context note Context note system prompt Context note Context note Context note.
     * Context note Context note [maxChars] Context note context window.
     */
    fun buildPromptInjection(graph: CausalGraph, maxChars: Int = 700): String {
        if (graph.nodes.isEmpty()) return ""
        val fatalConflicts = graph.conflicts.filter { it.isFatal }
        val warnings = graph.conflicts.filter { !it.isFatal }
        if (fatalConflicts.isEmpty() && warnings.isEmpty() && graph.highestRisk < RiskLevel.HIGH) return ""

        return buildString {
            appendLine("\n🗺️ Info Info Info (Causal Chain):")
            appendLine("  Info: ${graph.nodes.size} | Info Info: ${graph.highestRisk.label()}")

            if (fatalConflicts.isNotEmpty()) {
                appendLine("❌ Info Info:")
                for (c in fatalConflicts.take(3)) {
                    val line = "  • ${c.message.take(120)}"
                    if (length + line.length > maxChars) return@buildString
                    appendLine(line)
                }
            }
            if (warnings.isNotEmpty()) {
                appendLine("⚠️ Info:")
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

    /** Context note Context note Context note + Context note Context note Context note Context note. */
    private fun findRule(toolName: String, params: Map<String, String>): ToolRule {
        val path = params["path"]?.trim()
            ?: params["file_path"]?.trim()
            ?: params["target"]?.trim()

        return when (toolName) {
            // ── File Read Operations (LOW risk) ──────────────────────────────
            "read_file_lines", "read_file" ->
                readRule(path, "Info Info")
            "multi_read" ->
                readRule(path, "Info Info Info")
            "search_codebase" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.READ, null, "Info Info Info Info")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🔍 Info Info Info: ${params["query"]?.take(40) ?: "?"}"
                )

            // ── File Create Operations ────────────────────────────────────────
            "create_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.CREATE, path, "Info Info Info")),
                    preconditions = if (path != null) listOf("'$path' Info Info") else emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "📄 Info: ${path ?: "?"}"
                )

            // ── File Modify Operations ────────────────────────────────────────
            "patch_file_content", "multi_patch_file_content",
            "delete_text", "delete_lines", "insert_lines",
            "replace_lines", "append_to_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.MODIFY, path, "Info Info")),
                    preconditions = if (path != null) listOf("'$path' Info") else emptyList(),
                    riskLevel = RiskLevel.MEDIUM,
                    humanSummary = "✏️ Info: ${path ?: "?"}"
                )

            // ── Destructive: clear_file ───────────────────────────────────────
            "clear_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.MODIFY, path, "Info Info Info Info")),
                    preconditions = if (path != null) listOf("'$path' Info") else emptyList(),
                    riskLevel = RiskLevel.HIGH,
                    humanSummary = "🗑️ Info Info Info: ${path ?: "?"}"
                )

            // ── Delete Operations ─────────────────────────────────────────────
            "delete_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.DELETE, path, "Info Info")),
                    preconditions = if (path != null) listOf("'$path' Info") else emptyList(),
                    riskLevel = RiskLevel.HIGH,
                    humanSummary = "🗑️ Info Info: ${path ?: "?"}"
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
                    effects = listOf(CausalEffect(EffectType.NETWORK, null, "Info Info")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🌐 Info Info: $toolName"
                )

            // ── Memory Operations (LOW risk) ──────────────────────────────────
            "remember_fact", "update_memory", "delete_memory",
            "vector_store", "brain_record_episode" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.MODIFY, null, "Info Info")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🧠 Info Info: $toolName"
                )

            // ── System Tools ──────────────────────────────────────────────────
            "hardware_toggle_tool", "vpn_control", "system_power" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.SYSTEM, null, "Info Info Info")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.MEDIUM,
                    humanSummary = "⚙️ Info Info: $toolName"
                )

            // ── Default: unknown tool treated as low-risk read ────────────────
            else ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.READ, null, "Info Info Info")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🔧 $toolName"
                )
        }
    }

    private fun readRule(path: String?, label: String): ToolRule = ToolRule(
        effects = listOf(CausalEffect(EffectType.READ, path, label)),
        preconditions = if (path != null) listOf("'$path' Info") else emptyList(),
        riskLevel = RiskLevel.LOW,
        humanSummary = "📖 $label: ${path ?: "?"}"
    )

    /** Context note Context note Context note Shell Context note Context note Context note. */
    private fun analyzeShellCommand(toolName: String, params: Map<String, String>): ToolRule {
        val command = (params["command"] ?: params["code"] ?: "").lowercase()
        val (riskLevel, description) = when {
            "rm -rf" in command || "rm -r" in command ->
                RiskLevel.CRITICAL to "Info Info — Info Info Info"
            Regex("""^rm\s""").containsMatchIn(command) || "unlink" in command ->
                RiskLevel.HIGH to "Info Info(Info)"
            "git reset --hard" in command || "git clean -fd" in command ->
                RiskLevel.HIGH to "Info Info Git Info"
            "git push --force" in command || "git push -f" in command ->
                RiskLevel.HIGH to "Git force push — Info Info Info Info Info"
            "chmod 777" in command || "chmod -r" in command.replace(" ", "").replace("--", "-") ->
                RiskLevel.MEDIUM to "Info Info Info"
            "apt install" in command || "pkg install" in command || "pip install" in command ->
                RiskLevel.LOW to "Info Info"
            "mkfs" in command || "fdisk" in command || "dd if=" in command ->
                RiskLevel.CRITICAL to "Info Info/Info Info — Info Info"
            else ->
                RiskLevel.MEDIUM to "Info Info: ${command.take(60)}"
        }
        val effectPath = extractPathFromCommand(command)

        // Context note Context note (rm, unlink) Context note Context note DELETE Context note Context note Context note READ_AFTER_DELETE/MODIFY_AFTER_DELETE
        // Context note Context note Context note Context note Context note EXECUTE (git reset, mkfs, ddContext note Context note)
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
            humanSummary = "${riskLevel.label()} Info: ${command.take(80)}"
        )
    }

    /** Context note Context note Git Context note Context note Context note. */
    private fun analyzeGitAction(params: Map<String, String>): ToolRule {
        val action = params["action"]?.lowercase() ?: ""
        val (risk, desc, effType) = when {
            action in listOf("push", "force_push") ->
                Triple(RiskLevel.MEDIUM, "Info Info", EffectType.NETWORK)
            action in listOf("reset", "clean") ->
                Triple(RiskLevel.HIGH, "Info Info Git", EffectType.SYSTEM)
            action in listOf("merge", "rebase") ->
                Triple(RiskLevel.MEDIUM, "Info/Info Info Info", EffectType.MODIFY)
            action in listOf("commit", "add", "stage") ->
                Triple(RiskLevel.LOW, "Info Info", EffectType.SYSTEM)
            action in listOf("clone", "fetch", "pull") ->
                Triple(RiskLevel.LOW, "Info Info", EffectType.NETWORK)
            action in listOf("branch_delete", "tag_delete") ->
                Triple(RiskLevel.HIGH, "Info Info/Info", EffectType.DELETE)
            else ->
                Triple(RiskLevel.LOW, "Info Git: $action", EffectType.READ)
        }
        return ToolRule(
            effects = listOf(CausalEffect(effType, null, desc)),
            preconditions = emptyList(),
            riskLevel = risk,
            humanSummary = "🔀 Git $action: $desc"
        )
    }

    /**
     * Context note Context note Context note Context note Context note Context note Context note.
     * Context note (wouldSucceed, failReason, newState).
     */
    private fun simulateStep(
        node: CausalNode,
        state: VirtualState
    ): Triple<Boolean, String?, VirtualState> {
        // Context note Context note Context note Context note Context note
        for (precondition in node.preconditions) {
            // Context note Context note Context note Context note Context note (Context note: "'path/file' Context note")
            val pathMatch = Regex("'([^']+)'").find(precondition)
            val requiredPath = pathMatch?.groupValues?.get(1) ?: continue
            if (precondition.contains("Info") && state.wasDeleted(requiredPath)) {
                return Triple(false, "Info '$requiredPath' Info Info Info Info Info.", state)
            }
        }

        // Context note Context note Context note Context note Context note
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

    /** Context note Context note Context note Context note Context note Context note shell Context note Context note. */
    private fun extractPathFromCommand(command: String): String? {
        val pathRegex = Regex("""[/~][^\s'"]+|'([^']+)'|"([^"]+)"""")
        return pathRegex.find(command)?.let {
            it.groupValues.drop(1).firstOrNull { g -> g.isNotBlank() } ?: it.value
        }
    }
}
