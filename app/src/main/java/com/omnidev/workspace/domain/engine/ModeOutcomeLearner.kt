package com.omnidev.workspace.domain.engine

import android.content.Context
import com.omnidev.workspace.OmniDevApp
import kotlin.math.exp
import kotlin.math.ln

/**
 * Evidence-Calibrated Mode Utility (ECMU).
 *
 * Learns which runtime mode works best for a coarse task shape without ever granting authority.
 * The learner is deliberately local, deterministic and bounded: one tiny posterior per
 * (TaskSignals.bucketKey x mode) is persisted in SharedPreferences.
 *
 * Signals:
 * - weighted success/failure posterior (Beta distribution)
 * - abandonment rate
 * - verified-success rate
 * - mean iterations / latency / token usage when callers can provide them
 * - sample maturity so a lucky first run cannot steer routing
 *
 * The router consumes at most +/-12 percentage points of advisory confidence. User-controlled
 * mode permissions remain completely separate.
 */
object ModeOutcomeLearner {

    enum class Outcome {
        SUCCESS,
        FAILURE,
        ABANDONED
    }

    data class Stats(
        val observations: Int,
        val successEvidence: Float,
        val failureEvidence: Float,
        val abandoned: Int,
        val verifiedSuccesses: Int,
        val totalIterations: Long,
        val totalDurationMs: Long,
        val tokenSamples: Int,
        val totalTokens: Long
    ) {
        val posteriorSuccess: Float
            get() = ((PRIOR_SUCCESS + successEvidence) /
                (PRIOR_SUCCESS + PRIOR_FAILURE + successEvidence + failureEvidence))
                .coerceIn(0f, 1f)

        val maturity: Float
            get() = if (observations <= 0) 0f
            else (1.0 - exp(-observations.toDouble() / MATURITY_SCALE)).toFloat().coerceIn(0f, 1f)

        val abandonmentRate: Float
            get() = if (observations == 0) 0f else abandoned.toFloat() / observations.toFloat()

        val verifiedRate: Float
            get() {
                val estimatedSuccesses = successEvidence.coerceAtLeast(0f)
                if (estimatedSuccesses <= 0f) return 0f
                return (verifiedSuccesses.toFloat() / estimatedSuccesses).coerceIn(0f, 1f)
            }

        val averageIterations: Float
            get() = if (observations == 0) 0f else totalIterations.toFloat() / observations.toFloat()

        val averageDurationMs: Long
            get() = if (observations == 0) 0L else totalDurationMs / observations

        val averageTokens: Long
            get() = if (tokenSamples == 0) 0L else totalTokens / tokenSamples
    }

    data class Signal(
        val adjustment: Float,
        val observations: Int,
        val posteriorSuccess: Float,
        val maturity: Float,
        val utility: Float,
        val bucket: String
    )

    private const val PREFS_NAME = "mode_outcome_learner_v1"
    private const val PRIOR_SUCCESS = 2.0f
    private const val PRIOR_FAILURE = 2.0f
    private const val MATURITY_SCALE = 6.0
    private const val MAX_ADJUSTMENT = 0.12f
    private const val MAX_BUCKETS = 96
    private const val INDEX_KEY = "bucket_index"
    private const val DECISION_LOG_KEY = "recent_decisions_v1"
    private const val MAX_DECISIONS = 96
    private const val FIELD_SEPARATOR = '|'

    private val lock = Any()

    fun recordOutcome(
        userRequest: String,
        mode: OmniMode,
        outcome: Outcome,
        iterations: Int = 0,
        durationMs: Long = 0L,
        tokens: Int? = null,
        verified: Boolean = false
    ) {
        if (mode == OmniMode.AUTO || userRequest.isBlank()) return
        val normalizedRequest = normalizeRequest(userRequest)
        val bucket = IntentClassifier.analyze(normalizedRequest).bucketKey()
        val prefs = prefs() ?: return

        synchronized(lock) {
            val old = readStats(prefs, bucket, mode)
            val successDelta = when (outcome) {
                Outcome.SUCCESS -> if (verified) 1.15f else 1.0f
                Outcome.FAILURE -> 0f
                Outcome.ABANDONED -> 0.10f
            }
            val failureDelta = when (outcome) {
                Outcome.SUCCESS -> 0f
                Outcome.FAILURE -> 1.0f
                Outcome.ABANDONED -> 0.80f
            }
            val boundedTokens = tokens?.takeIf { it > 0 }?.coerceAtMost(10_000_000)
            val next = old.copy(
                observations = old.observations + 1,
                successEvidence = (old.successEvidence + successDelta).coerceAtMost(100_000f),
                failureEvidence = (old.failureEvidence + failureDelta).coerceAtMost(100_000f),
                abandoned = old.abandoned + if (outcome == Outcome.ABANDONED) 1 else 0,
                verifiedSuccesses = old.verifiedSuccesses + if (outcome == Outcome.SUCCESS && verified) 1 else 0,
                totalIterations = old.totalIterations + iterations.coerceIn(0, 10_000),
                totalDurationMs = old.totalDurationMs + durationMs.coerceIn(0L, 24L * 60L * 60L * 1000L),
                tokenSamples = old.tokenSamples + if (boundedTokens != null) 1 else 0,
                totalTokens = old.totalTokens + (boundedTokens ?: 0)
            )
            writeStats(prefs, bucket, mode, next)
            touchBucket(prefs, bucket)
            if (outcome != Outcome.ABANDONED) {
                // A reported success without verification is too weak to teach the predictor.
                if (verified || outcome == Outcome.FAILURE) {
                    val key = "model_${mode.name.lowercase()}"
                    val features = ModeDecisionModel.features(IntentClassifier.analyze(normalizedRequest))
                    val weights = ModeDecisionModel.decode(prefs.getString(key, null))
                    val updated = ModeDecisionModel.updated(
                        weights, features, outcome == Outcome.SUCCESS,
                        if (verified) 1f else 0.35f
                    )
                    prefs.edit().putString(key, ModeDecisionModel.encode(updated)).apply()
                }
            }
            appendDecision(prefs, "$bucket|${mode.name}|${outcome.name}|${if (verified) 1 else 0}|${boundedTokens ?: 0}|${durationMs.coerceAtLeast(0)}|${iterations.coerceAtLeast(0)}")
        }
    }

    /** No raw prompt, source, tool argument, or credential is stored in this bounded audit trail. */
    fun recordAutoDecision(userRequest: String, baseline: OmniMode, selected: OmniMode) {
        val prefs = prefs() ?: return
        val bucket = IntentClassifier.analyze(normalizeRequest(userRequest)).bucketKey()
        synchronized(lock) { appendDecision(prefs, "$bucket|AUTO|${baseline.name}>${selected.name}|0|0") }
    }

    fun recentDecisionLog(limit: Int = 30): List<String> {
        val prefs = prefs() ?: return emptyList()
        return synchronized(lock) {
            prefs.getString(DECISION_LOG_KEY, "").orEmpty().lineSequence()
                .filter(String::isNotBlank).toList().takeLast(limit.coerceIn(1, MAX_DECISIONS))
        }
    }

    fun clearDecisionLearning() {
        val prefs = prefs() ?: return
        synchronized(lock) {
            prefs.edit().remove(DECISION_LOG_KEY).remove("model_agent").remove("model_swarm")
                .remove("model_chat").apply()
        }
    }

    private fun appendDecision(prefs: android.content.SharedPreferences, entry: String) {
        val previous = prefs.getString(DECISION_LOG_KEY, "").orEmpty().lineSequence()
            .filter(String::isNotBlank).toList().takeLast(MAX_DECISIONS - 1)
        val timestamp = System.currentTimeMillis() / 60_000L
        prefs.edit().putString(DECISION_LOG_KEY, (previous + "$timestamp|$entry").joinToString("\n")).apply()
    }

    /**
     * Returns a small advisory confidence delta. It intentionally needs several observations
     * before becoming meaningful and compares the target against modes seen for the same bucket.
     */
    fun signal(userRequest: String, target: OmniMode): Signal {
        if (target == OmniMode.AUTO || userRequest.isBlank()) {
            return Signal(0f, 0, 0.5f, 0f, 0.5f, "")
        }
        val normalizedRequest = normalizeRequest(userRequest)
        val bucket = IntentClassifier.analyze(normalizedRequest).bucketKey()
        val prefs = prefs() ?: return Signal(0f, 0, 0.5f, 0f, 0.5f, bucket)

        synchronized(lock) {
            val targetStats = readStats(prefs, bucket, target)
            if (targetStats.observations < 3) {
                return Signal(
                    adjustment = 0f,
                    observations = targetStats.observations,
                    posteriorSuccess = targetStats.posteriorSuccess,
                    maturity = targetStats.maturity,
                    utility = utility(targetStats, 0L, 0L),
                    bucket = bucket
                )
            }

            val known = listOf(OmniMode.CHAT, OmniMode.AGENT, OmniMode.SWARM)
                .associateWith { readStats(prefs, bucket, it) }
                .filterValues { it.observations > 0 }
            val reference = known.values.ifEmpty { listOf(targetStats) }
            val cheapestTokens = reference.map { it.averageTokens }.filter { it > 0L }.minOrNull() ?: 0L
            val fastestMs = reference.map { it.averageDurationMs }.filter { it > 0L }.minOrNull() ?: 0L
            val targetUtility = utility(targetStats, cheapestTokens, fastestMs)
            val meanUtility = known.values
                .map { utility(it, cheapestTokens, fastestMs) }
                .average()
                .takeIf { !it.isNaN() }
                ?.toFloat()
                ?: targetUtility

            val relative = (targetUtility - meanUtility) * 0.42f
            val sampleGate = targetStats.maturity *
                (targetStats.observations.toFloat() / (targetStats.observations.toFloat() + 5f))
            val adjustment = (relative * sampleGate).coerceIn(-MAX_ADJUSTMENT, MAX_ADJUSTMENT)

            return Signal(
                adjustment = adjustment,
                observations = targetStats.observations,
                posteriorSuccess = targetStats.posteriorSuccess,
                maturity = targetStats.maturity,
                utility = targetUtility,
                bucket = bucket
            )
        }
    }

    fun confidenceAdjustment(userRequest: String, target: OmniMode): Float =
        signal(userRequest, target).adjustment

    /** Predict the cheaper reliable execution mode only after both alternatives have evidence. */
    fun recommendExecutionMode(userRequest: String, baseline: OmniMode): OmniMode {
        if (baseline != OmniMode.AGENT && baseline != OmniMode.SWARM) return baseline
        val signals = IntentClassifier.analyze(userRequest)
        if (!IntentClassifier.hasIndependentWork(userRequest, signals)) return OmniMode.AGENT
        val agent = statsFor(userRequest, OmniMode.AGENT)
        val team = statsFor(userRequest, OmniMode.SWARM)
        if (agent.observations < 6 || team.observations < 6) return baseline
        // Cost may choose between comparable successes; it must not buy savings by failing.
        val agentCost = agent.averageTokens.takeIf { it > 0 } ?: return baseline
        val teamCost = team.averageTokens.takeIf { it > 0 } ?: return baseline
        val prefs = prefs()
        val features = ModeDecisionModel.features(signals)
        val learnedAgent = prefs?.getString("model_agent", null)?.let {
            ModeDecisionModel.probability(ModeDecisionModel.decode(it), features)
        }
        val learnedTeam = prefs?.getString("model_swarm", null)?.let {
            ModeDecisionModel.probability(ModeDecisionModel.decode(it), features)
        }
        // The predictor is advisory and only compares modes with verified wins on this bucket.
        if (agent.verifiedSuccesses >= 2 && team.verifiedSuccesses >= 2 &&
            learnedAgent != null && learnedTeam != null) {
            if (learnedAgent > learnedTeam + 0.08f &&
                agent.posteriorSuccess >= team.posteriorSuccess - 0.06f &&
                agentCost.toDouble() <= teamCost.toDouble() * 1.20) return OmniMode.AGENT
            if (learnedTeam > learnedAgent + 0.08f &&
                team.posteriorSuccess >= agent.posteriorSuccess - 0.06f &&
                teamCost.toDouble() <= agentCost.toDouble() * 1.20) return OmniMode.SWARM
        }
        if (agent.posteriorSuccess >= team.posteriorSuccess - 0.06f &&
            agentCost.toDouble() * 1.20 < teamCost.toDouble()) return OmniMode.AGENT
        if (team.posteriorSuccess >= agent.posteriorSuccess + 0.12f &&
            teamCost.toDouble() <= agentCost.toDouble() * 1.50) return OmniMode.SWARM
        return baseline
    }

    fun statsFor(userRequest: String, mode: OmniMode): Stats {
        if (userRequest.isBlank() || mode == OmniMode.AUTO) return emptyStats()
        val bucket = IntentClassifier.analyze(normalizeRequest(userRequest)).bucketKey()
        val prefs = prefs() ?: return emptyStats()
        return synchronized(lock) { readStats(prefs, bucket, mode) }
    }

    private fun utility(stats: Stats, cheapestTokens: Long, fastestMs: Long): Float {
        val tokenEfficiency = when {
            stats.averageTokens <= 0L || cheapestTokens <= 0L -> 0.65f
            else -> (cheapestTokens.toFloat() / stats.averageTokens.toFloat()).coerceIn(0.15f, 1f)
        }
        val latencyEfficiency = when {
            stats.averageDurationMs <= 0L || fastestMs <= 0L -> 0.65f
            else -> (fastestMs.toFloat() / stats.averageDurationMs.toFloat()).coerceIn(0.15f, 1f)
        }
        val iterationEfficiency = if (stats.averageIterations <= 0f) {
            0.65f
        } else {
            val logCost = ln(1.0 + stats.averageIterations.toDouble()).toFloat()
            (1f / (1f + logCost / 3f)).coerceIn(0.20f, 1f)
        }

        return (
            stats.posteriorSuccess * 0.62f +
                stats.verifiedRate * 0.08f +
                tokenEfficiency * 0.10f +
                latencyEfficiency * 0.07f +
                iterationEfficiency * 0.06f +
                (1f - stats.abandonmentRate) * 0.07f
            ).coerceIn(0f, 1f)
    }

    private fun normalizeRequest(text: String): String {
        // Team workers receive a contract around the original objective. Learn against the
        // objective when present rather than the orchestration boilerplate.
        val marker = "## Original objective"
        val index = text.indexOf(marker, ignoreCase = true)
        val candidate = if (index >= 0) text.substring(index + marker.length) else text
        return candidate
            .replace(Regex("(?i)(key|token|secret|password|otp)[=:\\s]+\\S+"), "$1=[REDACTED]")
            .trim()
            .take(2_000)
    }

    private fun readStats(
        prefs: android.content.SharedPreferences,
        bucket: String,
        mode: OmniMode
    ): Stats {
        val prefix = keyPrefix(bucket, mode)
        return Stats(
            observations = prefs.getInt("${prefix}n", 0).coerceAtLeast(0),
            successEvidence = prefs.getFloat("${prefix}s", 0f).coerceAtLeast(0f),
            failureEvidence = prefs.getFloat("${prefix}f", 0f).coerceAtLeast(0f),
            abandoned = prefs.getInt("${prefix}a", 0).coerceAtLeast(0),
            verifiedSuccesses = prefs.getInt("${prefix}v", 0).coerceAtLeast(0),
            totalIterations = prefs.getLong("${prefix}i", 0L).coerceAtLeast(0L),
            totalDurationMs = prefs.getLong("${prefix}d", 0L).coerceAtLeast(0L),
            tokenSamples = prefs.getInt("${prefix}tn", 0).coerceAtLeast(0),
            totalTokens = prefs.getLong("${prefix}t", 0L).coerceAtLeast(0L)
        )
    }

    private fun writeStats(
        prefs: android.content.SharedPreferences,
        bucket: String,
        mode: OmniMode,
        stats: Stats
    ) {
        val prefix = keyPrefix(bucket, mode)
        prefs.edit()
            .putInt("${prefix}n", stats.observations)
            .putFloat("${prefix}s", stats.successEvidence)
            .putFloat("${prefix}f", stats.failureEvidence)
            .putInt("${prefix}a", stats.abandoned)
            .putInt("${prefix}v", stats.verifiedSuccesses)
            .putLong("${prefix}i", stats.totalIterations)
            .putLong("${prefix}d", stats.totalDurationMs)
            .putInt("${prefix}tn", stats.tokenSamples)
            .putLong("${prefix}t", stats.totalTokens)
            .apply()
    }

    private fun touchBucket(prefs: android.content.SharedPreferences, bucket: String) {
        val current = prefs.getString(INDEX_KEY, "")
            .orEmpty()
            .split(FIELD_SEPARATOR)
            .filter(String::isNotBlank)
            .filterNot { it == bucket }
            .toMutableList()
        current += bucket

        while (current.size > MAX_BUCKETS) {
            val evicted = current.removeAt(0)
            val editor = prefs.edit()
            listOf(OmniMode.CHAT, OmniMode.AGENT, OmniMode.SWARM).forEach { mode ->
                val prefix = keyPrefix(evicted, mode)
                listOf("n", "s", "f", "a", "v", "i", "d", "tn", "t")
                    .forEach { suffix -> editor.remove("$prefix$suffix") }
            }
            editor.apply()
        }
        prefs.edit().putString(INDEX_KEY, current.joinToString(FIELD_SEPARATOR.toString())).apply()
    }

    private fun keyPrefix(bucket: String, mode: OmniMode): String =
        "${bucket}_${mode.name.lowercase()}_"

    private fun emptyStats() = Stats(
        observations = 0,
        successEvidence = 0f,
        failureEvidence = 0f,
        abandoned = 0,
        verifiedSuccesses = 0,
        totalIterations = 0L,
        totalDurationMs = 0L,
        tokenSamples = 0,
        totalTokens = 0L
    )

    private fun prefs(): android.content.SharedPreferences? = try {
        OmniDevApp.instance.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Throwable) {
        null
    }
}
