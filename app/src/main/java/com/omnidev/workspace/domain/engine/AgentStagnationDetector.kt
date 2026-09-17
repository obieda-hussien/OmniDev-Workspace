package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.ToolExecutionResult
import kotlin.math.max

/**
 * Mobile-safe multi-signal stagnation detector for ReAct execution.
 *
 * It does not use an LLM, embeddings or network calls. Each iteration contributes a compact
 * observation (tool fingerprints + normalized result signatures) into a tiny bounded window.
 * The detector distinguishes two fundamentally different situations:
 *
 * 1) STRATEGY_STAGNATION — the agent is looping, oscillating or repeatedly observing the same
 *    state. A different decomposition/mode may help.
 * 2) INFRASTRUCTURE_BLOCK — auth/network/provider/permission/backend failures dominate. More
 *    workers would amplify the same failure, so callers should stop without Team escalation.
 */
class AgentStagnationDetector(
    private val windowSize: Int = 8,
    private val minIterationsBeforeAbort: Int = 3,
    private val abortThreshold: Float = 0.76f
) {

    init {
        require(windowSize >= 3) { "windowSize must be >= 3" }
        require(minIterationsBeforeAbort in 2..windowSize) {
            "minIterationsBeforeAbort must be within the detector window"
        }
        require(abortThreshold in 0.5f..1f) { "abortThreshold must be in [0.5, 1.0]" }
    }

    enum class Kind {
        HEALTHY,
        STRATEGY_STAGNATION,
        INFRASTRUCTURE_BLOCK
    }

    data class Snapshot(
        val kind: Kind,
        val score: Float,
        val iterationsObserved: Int,
        val novelty: Float,
        val repeatedObservationRatio: Float,
        val errorRatio: Float,
        val persistentFailureRatio: Float,
        val readOnlyRatio: Float,
        val oscillationScore: Float,
        val noActionStreak: Int,
        val shouldAbort: Boolean,
        val canBenefitFromDecomposition: Boolean,
        val reasons: List<String>
    ) {
        fun errorMessage(): String = when (kind) {
            Kind.INFRASTRUCTURE_BLOCK -> buildString {
                append("Persistent infrastructure/backend failure detected")
                if (reasons.isNotEmpty()) append(": ${reasons.joinToString("; ")}")
                append(". Spawning more agents would repeat the same blocked operation.")
            }
            Kind.STRATEGY_STAGNATION -> buildString {
                append("Agent no-progress loop detected")
                if (reasons.isNotEmpty()) append(": ${reasons.joinToString("; ")}")
                append(". A different strategy or decomposition is required.")
            }
            Kind.HEALTHY -> "Agent execution is making progress."
        }
    }

    private data class IterationObservation(
        val primaryTool: String?,
        val callFingerprints: Set<String>,
        val resultSignatures: Set<String>,
        val errorCount: Int,
        val persistentFailureCount: Int,
        val retryableErrorCount: Int,
        val totalResults: Int,
        val readOnlyCalls: Int,
        val actionCalls: Int,
        val infrastructureSignals: Int,
        val classifications: Set<String>,
        val backends: Set<String>
    )

    private val history = ArrayDeque<IterationObservation>()
    private val seenResultSignatures = LinkedHashSet<String>()
    private var consecutiveNoAction = 0

    fun reset() {
        history.clear()
        seenResultSignatures.clear()
        consecutiveNoAction = 0
    }

    fun observe(
        toolCalls: List<ToolCall>,
        results: List<ToolExecutionResult>
    ): Snapshot {
        require(toolCalls.size == results.size) {
            "Tool calls/results must preserve one-to-one ordering"
        }

        val callFingerprints = toolCalls.mapTo(linkedSetOf())(::fingerprintCall)
        val resultSignatures = results.mapTo(linkedSetOf())(::signatureOfResult)
        val newSignatures = resultSignatures.count { it !in seenResultSignatures }
        val novelty = if (resultSignatures.isEmpty()) {
            1f
        } else {
            newSignatures.toFloat() / resultSignatures.size.toFloat()
        }

        val readOnlyCalls = toolCalls.count(::isReadOnlyCall)
        val actionCalls = toolCalls.size - readOnlyCalls
        if (actionCalls == 0 && toolCalls.isNotEmpty()) {
            consecutiveNoAction++
        } else {
            consecutiveNoAction = 0
        }

        val classifications = results.mapNotNullTo(linkedSetOf()) {
            it.classification?.trim()?.uppercase()?.takeIf(String::isNotBlank)
        }
        val backends = results.mapNotNullTo(linkedSetOf()) {
            it.backend?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        }
        val persistentFailureCount = results.count { it.isError && it.persistentFailure }
        val infrastructureSignals = results.count(::isInfrastructureFailure)

        val observation = IterationObservation(
            primaryTool = toolCalls.firstOrNull()?.name,
            callFingerprints = callFingerprints,
            resultSignatures = resultSignatures,
            errorCount = results.count { it.isError },
            persistentFailureCount = persistentFailureCount,
            retryableErrorCount = results.count { it.isError && it.retryable },
            totalResults = results.size,
            readOnlyCalls = readOnlyCalls,
            actionCalls = actionCalls,
            infrastructureSignals = infrastructureSignals,
            classifications = classifications,
            backends = backends
        )

        val previousSignatures = history.flatMapTo(linkedSetOf()) { it.resultSignatures }
        val repeatedObservationRatio = if (resultSignatures.isEmpty()) {
            0f
        } else {
            resultSignatures.count { it in previousSignatures }.toFloat() /
                resultSignatures.size.toFloat()
        }

        history.addLast(observation)
        while (history.size > windowSize) history.removeFirst()
        seenResultSignatures += resultSignatures

        // Bound lifetime memory even for extremely long sessions.
        if (seenResultSignatures.size > 128) {
            val keep = seenResultSignatures.toList().takeLast(96)
            seenResultSignatures.clear()
            seenResultSignatures.addAll(keep)
        }

        return evaluate(
            currentNovelty = novelty,
            currentRepeatedObservationRatio = repeatedObservationRatio
        )
    }

    private fun evaluate(
        currentNovelty: Float,
        currentRepeatedObservationRatio: Float
    ): Snapshot {
        val observations = history.toList()
        val totalResults = observations.sumOf { it.totalResults }.coerceAtLeast(1)
        val totalCalls = observations.sumOf { it.readOnlyCalls + it.actionCalls }.coerceAtLeast(1)

        val errorRatio = observations.sumOf { it.errorCount }.toFloat() / totalResults.toFloat()
        val persistentFailureRatio = observations.sumOf { it.persistentFailureCount }.toFloat() /
            totalResults.toFloat()
        val infrastructureRatio = observations.sumOf { it.infrastructureSignals }.toFloat() /
            totalResults.toFloat()
        val readOnlyRatio = observations.sumOf { it.readOnlyCalls }.toFloat() / totalCalls.toFloat()
        val retryableRatio = observations.sumOf { it.retryableErrorCount }.toFloat() /
            totalResults.toFloat()
        val oscillation = calculateOscillation(observations.mapNotNull { it.primaryTool })
        val callRepetition = calculateCallRepetition(observations)
        val resultRepetition = calculateResultRepetition(observations)

        val backendLock = observations.takeLast(3)
            .flatMap { it.backends }
            .let { recent -> recent.isNotEmpty() && recent.distinct().size == 1 }
        val classificationLock = observations.takeLast(3)
            .flatMap { it.classifications }
            .let { recent -> recent.isNotEmpty() && recent.distinct().size == 1 }

        val reasons = mutableListOf<String>()
        if (currentRepeatedObservationRatio >= 0.75f || resultRepetition >= 0.68f) {
            reasons += "observations are repeating"
        }
        if (oscillation >= 0.70f) reasons += "tool strategy is oscillating"
        if (callRepetition >= 0.70f) reasons += "tool-call patterns are repeating"
        if (consecutiveNoAction >= 3) {
            reasons += "$consecutiveNoAction read-only iterations without action"
        }
        if (errorRatio >= 0.65f) reasons += "error ratio is ${(errorRatio * 100).toInt()}%"
        if (persistentFailureRatio >= 0.45f) {
            reasons += "persistent failures dominate recent results"
        }
        if (classificationLock && errorRatio >= 0.5f) {
            reasons += "same failure classification keeps recurring"
        }
        if (backendLock && persistentFailureRatio >= 0.35f) {
            reasons += "same backend remains blocked"
        }
        if (currentNovelty <= 0.25f && observations.size >= 3) {
            reasons += "new information gain is low"
        }

        val infrastructureDominance = (
            persistentFailureRatio * 0.46f +
                infrastructureRatio * 0.40f +
                (if (classificationLock && errorRatio >= 0.5f) 0.10f else 0f) +
                (if (backendLock && persistentFailureRatio >= 0.35f) 0.08f else 0f) -
                retryableRatio * 0.08f
            ).coerceIn(0f, 1f)

        val strategyScore = (
            resultRepetition * 0.23f +
                callRepetition * 0.20f +
                oscillation * 0.18f +
                errorRatio * 0.13f +
                readOnlyRatio * 0.10f +
                (1f - currentNovelty) * 0.10f +
                minOf(consecutiveNoAction / 4f, 1f) * 0.12f
            ).coerceIn(0f, 1f)

        val enoughHistory = observations.size >= minIterationsBeforeAbort
        val rawKind = when {
            enoughHistory && infrastructureDominance >= 0.62f -> Kind.INFRASTRUCTURE_BLOCK
            enoughHistory && strategyScore >= 0.60f -> Kind.STRATEGY_STAGNATION
            else -> Kind.HEALTHY
        }
        val score = when (rawKind) {
            Kind.INFRASTRUCTURE_BLOCK -> max(strategyScore, infrastructureDominance)
            else -> strategyScore
        }.coerceIn(0f, 1f)

        val shouldAbort = enoughHistory && when (rawKind) {
            Kind.INFRASTRUCTURE_BLOCK -> infrastructureDominance >= 0.62f
            Kind.STRATEGY_STAGNATION -> score >= abortThreshold
            Kind.HEALTHY -> false
        }
        val kind = if (rawKind == Kind.STRATEGY_STAGNATION && !shouldAbort) {
            Kind.HEALTHY
        } else {
            rawKind
        }

        return Snapshot(
            kind = kind,
            score = score,
            iterationsObserved = observations.size,
            novelty = currentNovelty,
            repeatedObservationRatio = currentRepeatedObservationRatio,
            errorRatio = errorRatio,
            persistentFailureRatio = persistentFailureRatio,
            readOnlyRatio = readOnlyRatio,
            oscillationScore = oscillation,
            noActionStreak = consecutiveNoAction,
            shouldAbort = shouldAbort,
            canBenefitFromDecomposition = kind == Kind.STRATEGY_STAGNATION && score >= 0.68f,
            reasons = reasons.distinct().take(6)
        )
    }

    private fun calculateCallRepetition(observations: List<IterationObservation>): Float {
        if (observations.size < 2) return 0f
        var comparisons = 0
        var repeated = 0
        for (i in 1 until observations.size) {
            comparisons++
            if (observations[i].callFingerprints == observations[i - 1].callFingerprints) repeated++
        }
        return if (comparisons == 0) 0f else repeated.toFloat() / comparisons.toFloat()
    }

    private fun calculateResultRepetition(observations: List<IterationObservation>): Float {
        if (observations.size < 2) return 0f
        var comparisons = 0
        var repeated = 0
        for (i in 1 until observations.size) {
            comparisons++
            val a = observations[i - 1].resultSignatures
            val b = observations[i].resultSignatures
            if (a.isNotEmpty() && a == b) repeated++
        }
        return if (comparisons == 0) 0f else repeated.toFloat() / comparisons.toFloat()
    }

    /** ABAB/ABCABC-like tool oscillation signal. */
    private fun calculateOscillation(sequence: List<String>): Float {
        if (sequence.size < 4) return 0f
        val recent = sequence.takeLast(8)
        var evidence = 0f

        if (recent.size >= 4) {
            val last4 = recent.takeLast(4)
            if (last4[0] == last4[2] && last4[1] == last4[3] && last4[0] != last4[1]) {
                evidence = max(evidence, 0.82f)
            }
        }
        if (recent.size >= 6) {
            val last6 = recent.takeLast(6)
            if (last6.take(3) == last6.drop(3)) evidence = max(evidence, 0.90f)
        }

        val unique = recent.distinct().size
        if (recent.size >= 5 && unique <= 2) evidence = max(evidence, 0.68f)
        return evidence
    }

    private fun isReadOnlyCall(call: ToolCall): Boolean {
        val name = call.name.lowercase()
        if (name == "semantic_ui") {
            val action = call.arguments["action"]?.lowercase()
            return action == null || action in READ_ONLY_UI_ACTIONS
        }
        if (name in EXPLICIT_ACTION_TOOLS) return false
        if (ACTION_NAME_HINTS.any(name::contains)) return false
        if (READ_ONLY_NAME_HINTS.any(name::contains)) return true

        // Unknown tools are treated as actions conservatively so newly added tools do not
        // trigger false-positive stagnation until explicitly classified.
        return false
    }

    private fun isInfrastructureFailure(result: ToolExecutionResult): Boolean {
        if (!result.isError) return false
        if (result.persistentFailure) return true

        val classification = result.classification?.uppercase().orEmpty()
        if (INFRA_CLASSIFICATIONS.any(classification::contains)) return true

        val lower = result.output.take(500).lowercase()
        return INFRA_TEXT_HINTS.any(lower::contains)
    }

    private fun fingerprintCall(call: ToolCall): String = buildString {
        append(call.name.lowercase())
        call.arguments.entries.sortedBy { it.key }.forEach { (key, value) ->
            append('|').append(key.lowercase()).append('=').append(normalizeVolatileText(value))
        }
    }.take(500)

    private fun signatureOfResult(result: ToolExecutionResult): String {
        val normalized = normalizeVolatileText(result.output.take(700))
        return buildString {
            append(result.isError)
            append('|').append(result.classification?.uppercase().orEmpty())
            append('|').append(result.backend?.lowercase().orEmpty())
            append('|').append(result.exitCode ?: "")
            append('|').append(normalized.hashCode())
        }
    }

    private fun normalizeVolatileText(value: String): String = value
        .lowercase()
        .replace(Regex("/[\\w./-]+"), "/path")
        .replace(Regex("\\b[0-9a-f]{8,}\\b"), "hex")
        .replace(Regex("\\b\\d+\\b"), "n")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(500)

    companion object {
        private val READ_ONLY_UI_ACTIONS = setOf("dump_tree", "get_node", "find_node", "list_nodes")

        private val READ_ONLY_NAME_HINTS = listOf(
            "read", "list", "search", "grep", "find", "inspect", "query", "get_", "status",
            "info", "dump", "scan", "analyze", "fetch", "web_search", "screenshot"
        )

        private val ACTION_NAME_HINTS = listOf(
            "write", "create", "patch", "delete", "remove", "install", "execute", "terminal",
            "shell", "command", "click", "type", "tap", "scroll", "press", "set_", "update",
            "deploy", "commit", "push", "move", "rename", "mkdir", "grant", "revoke"
        )

        private val EXPLICIT_ACTION_TOOLS = setOf(
            "advanced_terminal", "direct_terminal", "execute_terminal_command", "shizuku_command",
            "root_shell_tool", "advanced_root_shell", "write_file", "create_file", "patch_file",
            "delete_file", "mkdir", "git_manager", "package_installer"
        )

        private val INFRA_CLASSIFICATIONS = listOf(
            "AUTH", "UNAUTHORIZED", "FORBIDDEN", "RATE_LIMIT", "QUOTA", "NETWORK", "DNS",
            "BACKEND_UNAVAILABLE", "SERVICE_UNAVAILABLE", "PERMISSION_DENIED", "SHIZUKU_UNAVAILABLE",
            "ROOT_UNAVAILABLE", "CONNECTION"
        )

        private val INFRA_TEXT_HINTS = listOf(
            "rate limit", "http 429", "unauthorized", "forbidden", "invalid api key", "quota",
            "network unreachable", "connection refused", "dns", "service unavailable",
            "permission denied", "shizuku is not available", "root is not available"
        )
    }
}
