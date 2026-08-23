package com.omnidev.workspace.data.brain

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * CausalChainPlanner —      (Mobile-First)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *           " " (Causal Graph)
 *      (:     ).
 *
 * ##  :
 * 1. ** ** (analyzeToolCall):    →    +
 * 2. ** ** (buildChain):  DAG
 * 3. ** ** (detectConflicts):     (Read-After-Delete )
 * 4. ** ** (simulate):
 * 5. ** What-If** (whatIf):
 * 6. **  Prompt** (buildPromptInjection):     system prompt
 *
 * ## Mobile-First (4 GB RAM  ):
 * -   LLM  —   rule-based
 * -    —    (in-memory)
 * -   [maxNodes]
 * -   < 5ms  20
 */
class CausalChainPlanner(
    /**        (  OOM). */
    val maxNodes: Int = 50
) {

    // ──────────────────────────────────────────────────────────────────────────
    // Data classes & enums (public so CausalChainPlannerTool can read them)
    // ──────────────────────────────────────────────────────────────────────────

    enum class EffectType {
        CREATE,   //
        DELETE,   //
        MODIFY,   //
        READ,     //   (  )
        EXECUTE,  //    Shell
        SYSTEM,   //     (  Git )
        NETWORK   //
    }

    enum class RiskLevel(val score: Int) {
        LOW(1), MEDIUM(2), HIGH(3), CRITICAL(4);

        fun label(): String = when (this) {
            LOW      -> "🟢 "
            MEDIUM   -> "🟡 "
            HIGH     -> "🔴 "
            CRITICAL -> "💥 "
        }
    }

    enum class ConflictType {
        READ_AFTER_DELETE,      //
        MODIFY_AFTER_DELETE,    //
        DOUBLE_CREATE,          //
        OVERWRITE_UNREAD,       //       (  )
        DELETE_AFTER_MODIFY,    //     ( )
        CRITICAL_COMMAND,       //     (rm -rf, git reset --hard)
        CIRCULAR_DEPENDENCY,    // A   B  B   A
    }

    data class CausalEffect(
        val type: EffectType,
        /**     (null    ). */
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
        val stepA: Int,       //
        val stepB: Int,       //    (-1   )
        val path: String?,
        val message: String,
        val isFatal: Boolean  //
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
        val firstFailureIndex: Int,   // -1
        val warningMessages: List<String>
    )

    /**
     *   /  .
     *   —  sets  .
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
     *      [CausalNode]  .
     *  100% rule-based —  LLM    < 1ms.
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
     *  [CausalGraph]     .
     *     .
     *    [maxNodes]  .
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
     *        .
     */
    fun detectConflicts(nodes: List<CausalNode>): List<CausalConflict> {
        val conflicts = mutableListOf<CausalConflict>()
        // : path →
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
                                message = "⚠️  $i  '$path'     $delIdx.",
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
                                message = "❌  $i  '$path'     $delIdx.",
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
                                    message = "⚠️  $i  '$path'   (     $prevIdx).",
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
                                    message = "⚠️  $i  '$path'     $modIdx —  .",
                                    isFatal = false
                                )
                            }
                        }
                        lastDelete[path] = i
                    }
                    EffectType.EXECUTE -> {
                        // CRITICAL risk    (rm -rf, git reset --hard, etc.)
                        if (node.riskLevel == RiskLevel.CRITICAL) {
                            conflicts += CausalConflict(
                                type = ConflictType.CRITICAL_COMMAND,
                                stepA = i, stepB = -1, path = path,
                                message = "💥  $i   : ${node.humanSummary}",
                                isFatal = false //
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
     *         .
     *  "  "     .
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
            warnings += "⚠️        —      (rollback group)."
        }
        if (graph.nodes.count { it.riskLevel == RiskLevel.CRITICAL } > 0) {
            warnings += "💥 :  ${graph.nodes.count { it.riskLevel == RiskLevel.CRITICAL }} () ()  ."
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
     *  What-If: "   /  "
     *      .
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
            appendLine("🔬  What-If:")
            appendLine()
            when {
                removeStepIndex != null -> {
                    val removed = baseline.nodes.find { it.stepIndex == removeStepIndex }
                    appendLine("❌    $removeStepIndex (${removed?.toolName ?: "?"}):")
                }
                insertStep != null ->
                    appendLine("➕     (${insertStep.first}):")
                else ->
                    appendLine("📊  :")
            }
            appendLine()

            val baseConflicts = baseline.conflicts.size
            val modConflicts = modifiedGraph.conflicts.size
            when {
                modConflicts < baseConflicts ->
                    appendLine("✅   : $baseConflicts → $modConflicts")
                modConflicts > baseConflicts ->
                    appendLine("⚠️   : $baseConflicts → $modConflicts")
                else ->
                    appendLine("ℹ️    : $modConflicts")
            }

            val baseRisk = baseline.highestRisk
            val modRisk = modifiedGraph.highestRisk
            if (modRisk.score > baseRisk.score)
                appendLine("⬆️  : ${baseRisk.label()} → ${modRisk.label()}")
            else if (modRisk.score < baseRisk.score)
                appendLine("⬇️  : ${baseRisk.label()} → ${modRisk.label()}")

            val baseSuccess = baseSim.overallSuccess
            val modSuccess = modSim.overallSuccess
            when {
                !baseSuccess && modSuccess  -> appendLine("🎉    !")
                baseSuccess && !modSuccess  -> appendLine("💔    !")
                else                         -> appendLine("ℹ️     (${if (modSuccess) "English Text" else "English Text"})")
            }

            if (modifiedGraph.conflicts.isNotEmpty()) {
                appendLine()
                appendLine("📋    :")
                for (c in modifiedGraph.conflicts.take(5)) {
                    appendLine("  • ${c.message}")
                }
            }
        }
    }

    /**
     *     system prompt   .
     *   [maxChars]  context window.
     */
    fun buildPromptInjection(graph: CausalGraph, maxChars: Int = 700): String {
        if (graph.nodes.isEmpty()) return ""
        val fatalConflicts = graph.conflicts.filter { it.isFatal }
        val warnings = graph.conflicts.filter { !it.isFatal }
        if (fatalConflicts.isEmpty() && warnings.isEmpty() && graph.highestRisk < RiskLevel.HIGH) return ""

        return buildString {
            appendLine("\n🗺️    (Causal Chain):")
            appendLine("  : ${graph.nodes.size} |  : ${graph.highestRisk.label()}")

            if (fatalConflicts.isNotEmpty()) {
                appendLine("❌  :")
                for (c in fatalConflicts.take(3)) {
                    val line = "  • ${c.message.take(120)}"
                    if (length + line.length > maxChars) return@buildString
                    appendLine(line)
                }
            }
            if (warnings.isNotEmpty()) {
                appendLine("⚠️ Warnings:")
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

    /** Matches tool name + params to extract causal rule. */
    private fun findRule(toolName: String, params: Map<String, String>): ToolRule {
        val path = params["path"]?.trim()
            ?: params["file_path"]?.trim()
            ?: params["target"]?.trim()

        return when (toolName) {
            // ── File Read Operations (LOW risk) ──────────────────────────────
            "read_file_lines", "read_file" ->
                readRule(path, " ")
            "multi_read" ->
                readRule(path, "  ")
            "search_codebase" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.READ, null, "Search in Codebase")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🔍 Code Search: ${params["query"]?.take(40) ?: "?"}"
                )

            // ── File Create Operations ────────────────────────────────────────
            "create_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.CREATE, path, "  ")),
                    preconditions = if (path != null) listOf("'$path'  ") else emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "📄 : ${path ?: "?"}"
                )

            // ── File Modify Operations ────────────────────────────────────────
            "patch_file_content", "multi_patch_file_content",
            "delete_text", "delete_lines", "insert_lines",
            "replace_lines", "append_to_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.MODIFY, path, " ")),
                    preconditions = if (path != null) listOf("'$path' exists") else emptyList(),
                    riskLevel = RiskLevel.MEDIUM,
                    humanSummary = "✏️ : ${path ?: "?"}"
                )

            // ── Destructive: clear_file ───────────────────────────────────────
            "clear_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.MODIFY, path, "Clear File Content Completely")),
                    preconditions = if (path != null) listOf("'$path' exists") else emptyList(),
                    riskLevel = RiskLevel.HIGH,
                    humanSummary = "🗑️ Clear File Content: ${path ?: "?"}"
                )

            // ── Delete Operations ─────────────────────────────────────────────
            "delete_file" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.DELETE, path, " ")),
                    preconditions = if (path != null) listOf("'$path' exists") else emptyList(),
                    riskLevel = RiskLevel.HIGH,
                    humanSummary = "🗑️  : ${path ?: "?"}"
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
                    effects = listOf(CausalEffect(EffectType.NETWORK, null, " ")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🌐  : $toolName"
                )

            // ── Memory Operations (LOW risk) ──────────────────────────────────
            "remember_fact", "update_memory", "delete_memory",
            "vector_store", "brain_record_episode" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.MODIFY, null, "Modify Memory")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🧠 Update Memory: $toolName"
                )

            // ── System Tools ──────────────────────────────────────────────────
            "hardware_toggle_tool", "vpn_control", "system_power" ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.SYSTEM, null, "  ")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.MEDIUM,
                    humanSummary = "⚙️  : $toolName"
                )

            // ── Default: unknown tool treated as low-risk read ────────────────
            else ->
                ToolRule(
                    effects = listOf(CausalEffect(EffectType.READ, null, "  ")),
                    preconditions = emptyList(),
                    riskLevel = RiskLevel.LOW,
                    humanSummary = "🔧 $toolName"
                )
        }
    }

    private fun readRule(path: String?, label: String): ToolRule = ToolRule(
        effects = listOf(CausalEffect(EffectType.READ, path, label)),
        preconditions = if (path != null) listOf("'$path' exists") else emptyList(),
        riskLevel = RiskLevel.LOW,
        humanSummary = "📖 $label: ${path ?: "?"}"
    )

    /** Analyzes Shell commands to determine causal effects. */
    private fun analyzeShellCommand(toolName: String, params: Map<String, String>): ToolRule {
        val command = (params["command"] ?: params["code"] ?: "").lowercase()
        val (riskLevel, description) = when {
            "rm -rf" in command || "rm -r" in command ->
                RiskLevel.CRITICAL to "  —   "
            Regex("""^rm\s""").containsMatchIn(command) || "unlink" in command ->
                RiskLevel.HIGH to "Delete file(s)"
            "git reset --hard" in command || "git clean -fd" in command ->
                RiskLevel.HIGH to "Strict Git Reset"
            "git push --force" in command || "git push -f" in command ->
                RiskLevel.HIGH to "Git force push - Cannot revert on server"
            "chmod 777" in command || "chmod -r" in command.replace(" ", "").replace("--", "-") ->
                RiskLevel.MEDIUM to "Change file permissions"
            "apt install" in command || "pkg install" in command || "pip install" in command ->
                RiskLevel.LOW to "Install package"
            "mkfs" in command || "fdisk" in command || "dd if=" in command ->
                RiskLevel.CRITICAL to "Disk format/copy - Extreme risk"
            else ->
                RiskLevel.MEDIUM to "Execute command: ${command.take(60)}"
        }
        val effectPath = extractPathFromCommand(command)

        // Delete commands produce DELETE effect to allow READ_AFTER_DELETE detection
        // All other commands produce EXECUTE effect
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
            humanSummary = "${riskLevel.label()} : ${command.take(80)}"
        )
    }

    /** Analyzes Git operations for risk level. */
    private fun analyzeGitAction(params: Map<String, String>): ToolRule {
        val action = params["action"]?.lowercase() ?: ""
        val (risk, desc, effType) = when {
            action in listOf("push", "force_push") ->
                Triple(RiskLevel.MEDIUM, " ", EffectType.NETWORK)
            action in listOf("reset", "clean") ->
                Triple(RiskLevel.HIGH, "  Git", EffectType.SYSTEM)
            action in listOf("merge", "rebase") ->
                Triple(RiskLevel.MEDIUM, "/  ", EffectType.MODIFY)
            action in listOf("commit", "add", "stage") ->
                Triple(RiskLevel.LOW, " ", EffectType.SYSTEM)
            action in listOf("clone", "fetch", "pull") ->
                Triple(RiskLevel.LOW, " ", EffectType.NETWORK)
            action in listOf("branch_delete", "tag_delete") ->
                Triple(RiskLevel.HIGH, " /", EffectType.DELETE)
            else ->
                Triple(RiskLevel.LOW, " Git: $action", EffectType.READ)
        }
        return ToolRule(
            effects = listOf(CausalEffect(effType, null, desc)),
            preconditions = emptyList(),
            riskLevel = risk,
            humanSummary = "🔀 Git $action: $desc"
        )
    }

    /**
     * Simulates one step against current virtual state.
     * Returns (wouldSucceed, failReason, newState).
     */
    private fun simulateStep(
        node: CausalNode,
        state: VirtualState
    ): Triple<Boolean, String?, VirtualState> {
        // Check preconditions against virtual state
        for (precondition in node.preconditions) {
            //      (: "'path/file' exists")
            val pathMatch = Regex("'([^']+)'").find(precondition)
            val requiredPath = pathMatch?.groupValues?.get(1) ?: continue
            if (precondition.contains("English Text") && state.wasDeleted(requiredPath)) {
                return Triple(false, "File '$requiredPath' is deleted and inaccessible.", state)
            }
        }

        // Apply effects to virtual state
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

    /**       shell  . */
    private fun extractPathFromCommand(command: String): String? {
        val pathRegex = Regex("""[/~][^\s'"]+|'([^']+)'|"([^"]+)"""")
        return pathRegex.find(command)?.let {
            it.groupValues.drop(1).firstOrNull { g -> g.isNotBlank() } ?: it.value
        }
    }
}
