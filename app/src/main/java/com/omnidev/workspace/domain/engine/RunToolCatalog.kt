package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolSchemaCompactor
import com.omnidev.workspace.data.tools.*
import kotlin.math.ln

/** Run-local lexical retrieval over the permitted registry; loading is never permission. */
class RunToolCatalog(
    definitions: List<ToolDefinition>,
    objective: String,
    preferred: Set<String> = emptySet(),
    private val quality: Map<String, Float> = emptyMap(),
    private val additionalDomains: Set<IntentClassifier.ToolDomain> = emptySet()
) {
    private val catalog = definitions.filter { it.name != DISCOVER.name }.distinctBy { it.name }
    private val terms = catalog.associate { tool -> tool.name to words(
        tool.name + " " + tool.description + " " + tool.parameters.joinToString(" ") { it.name + " " + it.description }
    ) }
    private val frequency = terms.values.flatMap { it }.groupingBy { it }.eachCount()
    private val averageTerms = terms.values.map { it.size }.average().takeIf { it.isFinite() && it > 0 } ?: 1.0
    private val loaded = linkedSetOf<String>()

    init {
        loaded += catalog.filter { it.name in preferred }.take(8).map { it.name }
        loaded += search(objective, INITIAL_SIZE - loaded.size).map { it.name }
    }

    fun definitions(): List<ToolDefinition> = ToolSchemaCompactor.compact(
        listOf(DISCOVER) + catalog.filter { it.name in loaded }, emptyList()
    ).orEmpty()

    /** Runtime retrieval, not a model call. Observations are search data, never instructions. */
    fun prepare(objective: String, observation: String? = null): List<String> {
        val matches = (search(objective.take(2_000), SEARCH_SIZE) +
            observation?.takeIf { it.isNotBlank() }?.let { search(it.take(800), SEARCH_SIZE) }.orEmpty())
            .distinctBy { it.name }
        val added = matches.filter { it.name !in loaded }.map { it.name }
        load(matches)
        return added
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
        matches.forEach { tool -> loaded.remove(tool.name); loaded.add(tool.name) }
        while (loaded.size > MAX_LOADED) loaded.remove(loaded.first())
    }

    fun discover(query: String): ToolExecutionResult {
        if (query.isBlank()) return ToolExecutionResult("Use a concrete task or exact tool name as query.", true,
            classification = "INVALID_TOOL_ARGUMENTS")
        val matches = search(query, SEARCH_SIZE)
        if (matches.isEmpty()) return ToolExecutionResult("No registered tool matched. Describe the needed operation differently; do not invent tool names.")
        load(matches)
        return ToolExecutionResult(buildString {
            appendLine("Loaded for the NEXT model request; call only after reading its native schema:")
            matches.forEach { tool ->
                appendLine("${tool.name}: ${tool.description.take(500)}")
                tool.parameters.forEach { p ->
                    appendLine("  ${p.name}: ${p.type}${if (p.required) " required" else if (p.requiredForActions.isNotEmpty()) " required for ${p.requiredForActions.joinToString()}" else " optional"}${if (p.allowedValues.isEmpty()) "" else " enum=${p.allowedValues.joinToString()}"} — ${p.description.take(180)}")
                }
            }
        })
    }

    internal fun search(query: String, limit: Int): List<ToolDefinition> {
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
            val history = if (lexical > 0) (quality[tool.name] ?: 0f).coerceIn(-1f, 1f) * 0.3 else 0.0
            tool to (normalizedLexical + exact + domain + history)
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
        private const val MAX_LOADED = 40
        private const val SEARCH_SIZE = 6
        private val WORD = Regex("[\\p{L}\\p{N}_.-]+")
        private val STOP = setOf("the", "and", "for", "with", "tool", "tools", "use", "عايز", "اعمل", "من", "في", "علي")
        val DISCOVER = ToolDefinition("discover_tools",
            "Find and load real tools for a concrete task or exact name. Returns registered schemas. Use when no loaded tool fits; never guess a tool name. Loading does not grant access.",
            listOf(ToolParameter("query", "string", "Concrete operation, keywords, or exact tool name.")))
    }
}
