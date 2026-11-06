package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.tools.*
import kotlin.math.ln

/** Run-local capability index and adaptive schema surface over the permitted registry. Loading is never permission. */
class RunToolCatalog(
    definitions: List<ToolDefinition>,
    private val objective: String,
    preferred: Set<String> = emptySet(),
    private val quality: Map<String, Float> = emptyMap(),
    private val additionalDomains: Set<IntentClassifier.ToolDomain> = emptySet(),
    private val mentionedToolNames: Set<String> = emptySet(),
    private val budget: ToolSurfaceBudget = ToolSurfaceBudget()
) {
    private val catalog = definitions.filter { it.name != DISCOVER.name }.distinctBy { it.name }
    private val terms = catalog.associate { tool -> tool.name to words(
        tool.name + " " + tool.description + " " + tool.parameters.joinToString(" ") {
            it.name + " " + it.description + " " + it.allowedValues.joinToString(" ") + " " + it.requiredForActions.joinToString(" ")
        }
    ) }
    private val frequency = terms.values.flatMap { it }.groupingBy { it }.eachCount()
    private val averageTerms = terms.values.map { it.size }.average().takeIf { it.isFinite() && it > 0 } ?: 1.0
    private val loaded = linkedSetOf<String>()
    private val preferences = preferred
    private val demanded = linkedSetOf<String>()
    private var focus: ToolSurfaceFocus? = null
    private var currentObjective = objective
    private var selection = ToolSurfaceSelector.select(emptyList(), budget)
    val surface: ToolSurfaceSnapshot get() = selection.snapshot

    init {
        refresh(initial = true)
    }

    fun definitions(): List<ToolDefinition> = selection.definitions

    /** Recipes may invoke permitted tools that have not been loaded into the model's prompt. */
    fun isPermitted(name: String): Boolean = catalog.any { it.name == name }

    /** Runtime retrieval, not a model call. Observations are search data, never instructions. */
    fun prepare(objective: String, observation: String? = null, activeStep: ToolSurfaceFocus? = focus): List<String> {
        val previous = loaded.toSet()
        if (activeStep?.let { it.revision to it.stepKey } != focus?.let { it.revision to it.stepKey } ||
            objective.take(2_000) != currentObjective) demanded.clear()
        focus = activeStep
        currentObjective = objective.take(2_000)
        refresh(observation = observation)
        return loaded.filter { it !in previous }
    }

    data class OperationMatch(val tool: ToolDefinition?, val candidates: List<ToolDefinition>)

    /** Domain/quality priors alone never authorize a semantic operation match. */
    fun matchOperation(intent: String): OperationMatch {
        val query = words(intent)
        val ranked = catalog.mapNotNull { tool ->
            val matched = query.intersect(terms.getValue(tool.name))
            val exact = intent.trim() == tool.name
            if (!exact && (matched.size < 2 || matched.size.toDouble() / query.size.coerceAtLeast(1) < 0.6)) null else tool to if (exact) 1_000.0 else
                matched.sumOf { term -> ln(1.0 + (catalog.size + 1.0) / ((frequency[term] ?: 0) + 1.0)) }
        }.sortedWith(compareByDescending<Pair<ToolDefinition, Double>> { it.second }.thenBy { it.first.name })
        val best = ranked.firstOrNull()
        val runnerUp = ranked.getOrNull(1)
        val unique = best != null && (runnerUp == null || best.second >= runnerUp.second * 1.5 && best.second - runnerUp.second >= 0.5)
        return OperationMatch(if (unique) best.first else null, ranked.take(SEARCH_SIZE).map { it.first })
    }

    fun loadCandidates(candidates: List<ToolDefinition>) = load(candidates.take(SEARCH_SIZE))

    private fun load(matches: List<ToolDefinition>) {
        matches.filter { isPermitted(it.name) }.asReversed().forEach { tool ->
            demanded.remove(tool.name); demanded.add(tool.name)
        }
        while (demanded.size > ToolCallPreflight.MAX_BATCH_SIZE) demanded.remove(demanded.first())
        refresh()
    }

    private fun refresh(initial: Boolean = false, observation: String? = null) {
        val candidates = mutableListOf<ToolSurfaceCandidate>()
        fun add(tools: List<ToolDefinition>, source: ToolSurfaceSource) { tools.forEach { candidates += ToolSurfaceCandidate(it, source) } }
        add(demanded.toList().asReversed().mapNotNull { name -> catalog.firstOrNull { it.name == name } }, ToolSurfaceSource.DISCOVERY)
        add(catalog.filter { it.name == focus?.verificationTool }, ToolSurfaceSource.VERIFICATION)
        add(catalog.filter { it.name in mentionedToolNames }.sortedBy { it.name }, ToolSurfaceSource.MENTION)
        val step = focus
        if (step != null) add(search(step.query.take(2_000), INITIAL_SIZE, focusedDomains = step.domains), ToolSurfaceSource.ACTIVE_STEP)
        if (!initial || mentionedToolNames.isEmpty()) {
            // Preferences are hints, never permissions. Keep the old initial mention-only behavior.
            add(catalog.filter { it.name in preferences }.sortedBy { it.name }.take(8), ToolSurfaceSource.PREFERENCE)
            add(search(currentObjective, if (step == null) INITIAL_SIZE else SEARCH_SIZE), ToolSurfaceSource.OBJECTIVE)
        }
        if (!observation.isNullOrBlank()) add(search(observation.take(800), 2, lexicalOnly = true), ToolSurfaceSource.OBSERVATION)
        selection = ToolSurfaceSelector.select(candidates, budget)
        loaded.clear(); loaded.addAll(selection.snapshot.entries.map { it.name })
    }

    /**
     * Makes an already permitted tool visible. Exposure only controls which schemas are sent to the
     * model (token economy); permission is [isPermitted], so loading a permitted tool on demand never
     * widens what a run may do. Returns false when the tool is not permitted for this run.
     */
    fun ensureLoaded(name: String): Boolean {
        val tool = catalog.firstOrNull { it.name == name } ?: return false
        if (name !in loaded) load(listOf(tool))
        return name in loaded
    }

    fun isLoaded(name: String): Boolean = name in loaded

    /** Wider than [matchOperation]: used only to offer candidates when strict matching finds none. */
    fun looseCandidates(intent: String): List<ToolDefinition> =
        search(intent.ifBlank { focus?.query ?: currentObjective }, SEARCH_SIZE)

    /** One line per tool: `name(param*, other)` where `*` marks a required parameter. */
    fun signature(tool: ToolDefinition): String =
        tool.name + tool.parameters.joinToString(prefix = "(", postfix = ")") { it.name + if (it.required) "*" else "" }

    /** Exact names and parameters of the permitted tools, for recovery messages to confused models. */
    fun exactToolDigest(limit: Int = DIGEST_SIZE): String =
        catalog.sortedBy { it.name }.take(limit).joinToString("; ") { signature(it) } +
            if (catalog.size > limit) "; +${catalog.size - limit} more (use discover_tools)" else ""

    fun discover(query: String): ToolExecutionResult {
        // A model that calls discovery without a query (common with small models) is helped, not rejected:
        // fall back to the run objective, which is what the automatic retrieval already uses.
        val effectiveQuery = query.take(2_000).ifBlank { focus?.query ?: currentObjective }
        if (effectiveQuery.isBlank()) return ToolExecutionResult("Use a concrete task or exact tool name as query.", true,
            classification = "INVALID_TOOL_ARGUMENTS")
        val matches = search(effectiveQuery, SEARCH_SIZE)
        if (matches.isEmpty()) return ToolExecutionResult("No registered tool matched. Describe the needed operation differently; do not invent tool names.")
        load(matches)
        val visible = matches.filter { isLoaded(it.name) }
        if (visible.isEmpty()) return ToolExecutionResult(
            "Matching registered schemas exceed this model's tool-surface budget. Use a model with a larger context or a narrower exact tool query. No action executed.",
            true, classification = "TOOL_SCHEMA_BUDGET")
        return ToolExecutionResult(buildString {
            appendLine("Loaded for the NEXT model request; call only after reading its native schema:")
            visible.forEach { tool ->
                appendLine("${tool.name}: ${tool.description.take(500)}")
                tool.parameters.forEach { p ->
                    appendLine("  ${p.name}: ${p.type}${if (p.required) " required" else if (p.requiredForActions.isNotEmpty()) " required for ${p.requiredForActions.joinToString()}" else " optional"}${if (p.allowedValues.isEmpty()) "" else " enum=${p.allowedValues.joinToString()}"} — ${p.description.take(180)}")
                }
            }
            if (visible.size < matches.size) appendLine("Other matches deferred by the schema budget; query an exact tool name to prioritize it.")
        })
    }

    internal fun search(query: String, limit: Int, lexicalOnly: Boolean = false,
        focusedDomains: Set<IntentClassifier.ToolDomain> = emptySet()): List<ToolDefinition> {
        val queryTerms = words(query)
        if (queryTerms.isEmpty() || limit <= 0) return emptyList()
        val domains = IntentClassifier.getRelevantDomains(query) + additionalDomains
        val scores = catalog.map { tool ->
            val tokens = terms.getValue(tool.name)
            val nameTokens = words(tool.name)
            val matched = queryTerms.intersect(tokens)
            val lexical = matched.sumOf { term ->
                val idf = ln(1.0 + (catalog.size + 1.0) / ((frequency[term] ?: 0) + 1.0))
                idf * if (term in nameTokens) 5.0 else 1.0
            }
            val normalizedLexical = lexical / (0.75 + 0.25 * tokens.size / averageTerms)
            val exact = if (query.trim() == tool.name || tool.name in queryTerms) 100.0 else 0.0
            val domain = if (IntentClassifier.getToolDomain(tool.name) in domains) 1.5 else 0.0
            val specialization = if (IntentClassifier.getToolDomain(tool.name) in focusedDomains) 4.0 else 0.0
            val history = if (lexical > 0) (quality[tool.name] ?: 0f).coerceIn(-1f, 1f) * 0.3 else 0.0
            tool to (if (lexicalOnly && lexical <= 0 && exact <= 0) 0.0 else normalizedLexical + exact + domain + specialization + history)
        }
        return scores.filter { it.second > 0 }.sortedWith(
            compareByDescending<Pair<ToolDefinition, Double>> { it.second }.thenBy { it.first.name }
        ).take(limit).map { it.first }
    }

    private fun words(raw: String): Set<String> = WORD.findAll(raw.lowercase()
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي'))
        .flatMap { match -> sequenceOf(match.value) + match.value.split('_', '-', '.').asSequence() }
        .filter { it.length > 1 && it !in STOP }.toSet()

    companion object {
        private const val INITIAL_SIZE = 24
        private const val SEARCH_SIZE = 6
        private const val DIGEST_SIZE = 40
        private val WORD = Regex("[\\p{L}\\p{N}_.-]+")
        private val STOP = setOf("the", "and", "for", "with", "tool", "tools", "use", "عايز", "اعمل", "من", "في", "علي", "failed", "returned", "error", "tool_error", "success", "retryable", "persistent", "class", "false", "true", "omni-outcome")
        val DISCOVER = ToolDefinition("discover_tools",
            "Find and load real tools for a concrete task or exact name. Returns registered schemas. Use when no loaded tool fits; never guess a tool name. Loading does not grant access.",
            listOf(ToolParameter("query", "string", "Concrete operation, keywords, or exact tool name. Optional: defaults to the run objective.", required = false)))
    }
}
