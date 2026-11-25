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
    private val budget: ToolSurfaceBudget = ToolSurfaceBudget(),
    private val registeredToolCount: Int? = null
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
    private var lastAdviceQuery = ""
    private var lastAdviceDefinitions = emptyList<ToolDefinition>()
    private var lastAdvice = ""
    private var lastRankingQuery = ""
    private var lastRanking = emptyList<String>()
    private var currentObjective = objective
    private var selection = ToolSurfaceSelector.select(emptyList(), budget)
    val surface: ToolSurfaceSnapshot get() = selection.snapshot

    init {
        refresh(initial = true)
    }

    fun definitions(): List<ToolDefinition> = selection.definitions

    /** Inventory is a read of the permitted registry, independent of the prompt schema budget. */
    internal fun inventory(): List<ToolDefinition> = catalog.sortedBy { it.name }

    internal data class InventoryCounts(val registered: Int, val authorized: Int, val loaded: Int)

    internal fun inventoryCounts() = InventoryCounts(registeredToolCount ?: catalog.size, catalog.size, loaded.size)

    fun advisoryContext(): String {
        val query = focus?.query ?: currentObjective
        val visible = definitions()
        if (query != lastAdviceQuery || visible != lastAdviceDefinitions) {
            lastAdviceQuery = query
            lastAdviceDefinitions = visible
            lastAdvice = LocalSpecialistAdvice.operationContext(query, visible) +
                LocalSpecialistAdvice.explanationContext(query)
        }
        return lastAdvice
    }

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

    /** Pin the whole submitted batch together so loading one call cannot evict another. */
    fun loadForExecution(names: List<String>) = load(names.distinct().take(ToolCallPreflight.MAX_BATCH_SIZE)
        .mapNotNull { name -> catalog.firstOrNull { it.name == name } })

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
        add(ExecutionEnvironmentContext.priorityNames(currentObjective, observation).mapNotNull { name ->
            catalog.firstOrNull { it.name == name }
        }, ToolSurfaceSource.OBJECTIVE)
        val step = focus
        if (step != null) add(search(step.query.take(2_000), INITIAL_SIZE, focusedDomains = step.domains), ToolSurfaceSource.ACTIVE_STEP)
        if (!initial || mentionedToolNames.isEmpty()) {
            // Preferences are hints, never permissions. Keep the old initial mention-only behavior.
            add(catalog.filter { it.name in preferences }.sortedBy { it.name }.take(8), ToolSurfaceSource.PREFERENCE)
            // Remote inspection starts with a narrow surface; other permitted tools remain loadable on demand.
            val objectiveLimit = if (step == null && ExecutionEnvironmentContext.remoteRepository(currentObjective) == null) INITIAL_SIZE else SEARCH_SIZE
            add(search(currentObjective, objectiveLimit), ToolSurfaceSource.OBJECTIVE)
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

    /** Read-only inventory: never loads schemas or changes the permitted set. */
    private fun inventoryPage(
        offset: Int,
        backendAvailability: String?,
        category: IntentClassifier.ToolDomain? = null
    ): ToolExecutionResult {
        val entries = inventory().filter { category == null || IntentClassifier.getToolDomain(it.name) == category }
        if (offset !in 0..entries.size) return ToolExecutionResult(
            "Inventory offset out of range (0..${entries.size}).", true,
            classification = "INVALID_TOOL_ARGUMENTS")
        val page = entries.drop(offset).take(INVENTORY_PAGE_SIZE)
        val next = offset + page.size
        val categoryName = category?.name?.lowercase()
        val counts = inventoryCounts()
        return ToolExecutionResult(buildString {
            appendLine("Run registry snapshot. Registered: ${counts.registered}; authorized: ${counts.authorized}; schemas loaded: ${counts.loaded}.")
            if (backendAvailability == null) appendLine("Live backend availability: unknown.")
            else appendLine("Shell backends observed in this run: $backendAvailability. Other backends: unknown.")
            appendLine("Registration is limited to this build and discovered MCP tools.")
            appendLine("Permitted inventory. Category: ${categoryName ?: "all"}; total: ${entries.size}; offset: $offset; count: ${page.size}.")
            page.forEach { tool ->
                appendLine("${tool.name} — ${if (isLoaded(tool.name)) "schema loaded" else "schema on demand"}")
            }
            val cursor = categoryName?.let { "$it:$next" } ?: next.toString()
            if (next < entries.size) appendLine("Next page: discover_tools(query=inventory:$cursor)")
            else appendLine("End of inventory.")
            appendLine("Use an exact tool name to load its schema.")
        })
    }

    private fun inventoryQuery(query: String, backendAvailability: String?): ToolExecutionResult {
        val parts = query.split(':', limit = 3)
        if (parts.size == 1) parts.single().toIntOrNull()?.let { return inventoryPage(it, backendAvailability) }
        val category = IntentClassifier.ToolDomain.entries.firstOrNull { it.name.lowercase() == parts.first() }
        val offset = if (parts.size == 1) 0 else parts[1].toIntOrNull()
        if (category != null && offset != null && parts.size < 3) return inventoryPage(offset, backendAvailability, category)
        val categories = IntentClassifier.ToolDomain.entries.joinToString { it.name.lowercase() }
        return ToolExecutionResult(
            "Use inventory:<offset> or inventory:<category>[:offset]. Categories: $categories.",
            true, classification = "INVALID_TOOL_ARGUMENTS"
        )
    }

    fun discover(query: String): ToolExecutionResult = discover(query, backendAvailability = null)

    internal fun discover(query: String, backendAvailability: String?): ToolExecutionResult {
        val request = query.trim().lowercase()
        if (request == "inventory" || request in INVENTORY_QUERIES ||
            (request.isEmpty() && currentObjective.trim().lowercase() in INVENTORY_QUERIES)) {
            return inventoryPage(0, backendAvailability)
        }
        if (request.startsWith("inventory:")) return inventoryQuery(request.substringAfter(':'), backendAvailability)
        // A model that calls discovery without a query (common with small models) is helped, not rejected:
        // Fall back to the same active step/latest objective used by automatic retrieval.
        val effectiveQuery = query.take(2_000).ifBlank { focus?.query ?: currentObjective }
        if (effectiveQuery.isBlank()) return ToolExecutionResult("Use a concrete task or exact tool name as query.", true,
            classification = "INVALID_TOOL_ARGUMENTS")
        val preferred = if (query.isBlank()) ExecutionEnvironmentContext.priorityNames(effectiveQuery, null)
            .mapNotNull { name -> catalog.firstOrNull { it.name == name } } else emptyList()
        val exact = catalog.firstOrNull { it.name == effectiveQuery.trim() }
        val matches = if (exact != null) listOf(exact) else (preferred + search(effectiveQuery, SEARCH_SIZE,
            focusedDomains = if (query.isBlank()) focus?.domains.orEmpty() else emptySet())).distinctBy { it.name }.take(SEARCH_SIZE)
        if (matches.isEmpty()) return ToolExecutionResult("No registered tool matched. Describe the needed operation differently; do not invent tool names.")
        load(matches)
        val visible = matches.filter { isLoaded(it.name) }
        if (visible.isEmpty() || exact != null && !isLoaded(exact.name)) return ToolExecutionResult(
            "Matching registered schemas exceed this model's tool-surface budget. Use a model with a larger context or a narrower exact tool query. No action executed.",
            true, classification = "TOOL_SCHEMA_BUDGET")
        return ToolExecutionResult(buildString {
            appendLine("Loaded for the NEXT model request. Use its advertised parameter schema:")
            visible.forEach { tool -> appendLine("${tool.name}: ${signature(tool)} — ${tool.description.trim().lineSequence().first().take(140)}") }
            if (visible.size < matches.size) appendLine("Other matches deferred by the schema budget; query an exact tool name to prioritize it.")
        })
    }

    internal fun search(query: String, limit: Int, lexicalOnly: Boolean = false,
        focusedDomains: Set<IntentClassifier.ToolDomain> = emptySet()): List<ToolDefinition> {
        val queryTerms = words(query)
        if (queryTerms.isEmpty() || limit <= 0) return emptyList()
        val workspaceSearch = queryTerms.any { it in WORKSPACE_TERMS } && queryTerms.any { it in SEARCH_TERMS }
        val domains = IntentClassifier.getRelevantDomains(query) + additionalDomains
        if (!lexicalOnly && query != lastRankingQuery) {
            lastRanking = LocalSpecialistAdvice.rank(query, catalog)
            lastRankingQuery = query
        }
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
            val learnedRank = if (lexicalOnly) -1 else lastRanking.indexOf(tool.name)
            val learned = if (learnedRank < 0) 0.0 else 3.0 / (learnedRank + 1)
            tool to (if (lexicalOnly && lexical <= 0 && exact <= 0) 0.0
                else (if (workspaceSearch && tool.name in PROJECT_LOOKUP) 16.0 else 0.0) + normalizedLexical + exact + domain + specialization + history + learned)
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
        private const val INVENTORY_PAGE_SIZE = 40
        private val INVENTORY_QUERIES = setOf("all tools", "list all tools", "all available tools",
            "all available tools and capabilities", "list tools", "كل الادوات", "كل الأدوات",
            "اعرض كل الأدوات", "اي هي ادواتك كلها", "اي هي أدواتك كلها")
        private val WORD = Regex("[\\p{L}\\p{N}_.-]+")
        private val WORKSPACE_TERMS = setOf("workspace", "project", "codebase", "repository", "المشروع", "مشروع")
        private val SEARCH_TERMS = setOf("search", "find", "locate", "list", "بحث", "ابحث", "دور", "اعرض")
        private val PROJECT_LOOKUP = setOf("search_codebase", "list_files", "repo_search_symbols", "repo_find_context")
        private val STOP = setOf("the", "and", "for", "with", "tool", "tools", "use", "عايز", "اعمل", "من", "في", "علي", "failed", "returned", "error", "tool_error", "success", "retryable", "persistent", "class", "false", "true", "omni-outcome")
        val DISCOVER = ToolDefinition("discover_tools",
            "Load tool schemas when the current tools do not cover an operation. Search by operation or exact name, then call the matching tool. Discovery does not execute it or grant access.",
            listOf(ToolParameter("query", "string", "Concrete operation or exact tool name. For all permitted tools use inventory. Continue with inventory:<offset> or filter with inventory:<category>[:offset]. Inventory does not load schemas. Optional: defaults to the run objective.", required = false)))
    }
}
