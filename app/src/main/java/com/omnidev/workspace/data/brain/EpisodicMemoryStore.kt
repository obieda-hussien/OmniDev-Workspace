package com.omnidev.workspace.data.brain

import android.util.Log
import com.omnidev.workspace.data.db.dao.EpisodicMemoryDao
import com.omnidev.workspace.data.db.entities.EpisodicMemoryEntry
import com.omnidev.workspace.domain.engine.ModeOutcomeLearner
import com.omnidev.workspace.domain.engine.OmniMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Episodic task memory for the local Agent Brain.
 *
 * Retrieval is deliberately more than nearest-neighbour search: successful, failed and abandoned
 * episodes are sampled separately, scored by semantic relevance + recency + execution efficiency,
 * then selected with Maximal Marginal Relevance (MMR). That prevents the prompt from wasting its
 * tiny memory budget on near-duplicate past runs while still retaining cautionary failures.
 *
 * Completed standalone Agent episodes also feed the local execution-mode outcome learner. Team
 * worker episodes remain useful episodic memory, but Team mode is scored once at the orchestrator
 * level so a six-worker run does not count as six independent Team successes.
 */
class EpisodicMemoryStore(
    private val dao: EpisodicMemoryDao,
    private val maxEpisodes: Int = 2000,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    companion object {
        private const val TAG = "EpisodicMemoryStore"
        private const val MIN_SIMILARITY = 0.18f
        private const val DB_CANDIDATE_LIMIT = 80
        private const val MAX_SUMMARY_LENGTH = 500
        private const val MAX_TOOLS_STORED = 10
        private const val MMR_LAMBDA = 0.78f
        private const val TEAM_TASK_MARKER = "## Assigned Team Task"
    }

    private data class Candidate(
        val entry: EpisodicMemoryEntry,
        val vector: FloatArray,
        val semanticSimilarity: Float,
        val relevance: Float
    )

    fun recordEpisodeAsync(
        summary: String,
        userIntent: String,
        finalOutcome: EpisodeOutcome,
        toolsUsed: List<String>,
        iterationsCount: Int,
        totalTimeMs: Long,
        sessionId: String
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                recordEpisode(
                    summary = summary,
                    userIntent = userIntent,
                    finalOutcome = finalOutcome,
                    toolsUsed = toolsUsed,
                    iterationsCount = iterationsCount,
                    totalTimeMs = totalTimeMs,
                    sessionId = sessionId
                )
            } catch (t: Throwable) {
                Log.w(TAG, "recordEpisode failed: ${t.message}")
            }
        }
    }

    suspend fun recordEpisode(
        summary: String,
        userIntent: String,
        finalOutcome: EpisodeOutcome,
        toolsUsed: List<String>,
        iterationsCount: Int,
        totalTimeMs: Long,
        sessionId: String
    ): Long = withContext(Dispatchers.IO) {
        if (summary.isBlank()) return@withContext -1L

        val truncatedSummary = summary.take(MAX_SUMMARY_LENGTH)
        val truncatedIntent = userIntent.take(200)
        val toolsCsv = toolsUsed.takeLast(MAX_TOOLS_STORED).joinToString(",")
        val embedding = HashEmbedder.embed("$truncatedIntent $truncatedSummary")

        val entry = EpisodicMemoryEntry(
            summary = truncatedSummary,
            userIntent = truncatedIntent,
            finalOutcome = finalOutcome.name,
            toolsUsedCsv = toolsCsv,
            embedding = HashEmbedder.toBytes(embedding),
            iterationsCount = iterationsCount,
            totalTimeMs = totalTimeMs,
            sessionId = sessionId,
            createdAt = System.currentTimeMillis()
        )

        val id = dao.insert(entry)

        // Standalone Agent outcomes calibrate Agent routing here. Team workers are intentionally
        // excluded because SwarmOrchestrator records one aggregate Team outcome for the run.
        if (!userIntent.contains(TEAM_TASK_MARKER, ignoreCase = true)) {
            try {
                val modeOutcome = when (finalOutcome) {
                    EpisodeOutcome.SUCCESS -> ModeOutcomeLearner.Outcome.SUCCESS
                    EpisodeOutcome.FAILURE -> ModeOutcomeLearner.Outcome.FAILURE
                    EpisodeOutcome.ABANDONED -> ModeOutcomeLearner.Outcome.ABANDONED
                }
                ModeOutcomeLearner.recordOutcome(
                    userRequest = userIntent,
                    mode = OmniMode.AGENT,
                    outcome = modeOutcome,
                    iterations = iterationsCount,
                    durationMs = totalTimeMs,
                    verified = false
                )
            } catch (t: Throwable) {
                // Outcome learning must never make primary episodic persistence fail.
                Log.w(TAG, "mode outcome learning failed: ${t.message}")
            }
        }

        enforceQuota()
        id
    }

    /**
     * Two-stage retrieval:
     * 1. bounded SQL outcome sampling
     * 2. hash-embedding similarity
     * 3. relevance calibration (recency + efficiency + outcome prior)
     * 4. MMR diversity selection
     */
    suspend fun retrieveSimilar(
        query: String,
        topK: Int = 2,
        preferSuccess: Boolean = true
    ): List<EpisodicMemoryEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank() || topK <= 0) return@withContext emptyList()

        val queryVec = HashEmbedder.embed(query)
        val rawCandidates = if (preferSuccess) {
            buildList {
                addAll(dao.getByOutcome(EpisodeOutcome.SUCCESS.name, limit = 42))
                addAll(dao.getByOutcome(EpisodeOutcome.FAILURE.name, limit = 22))
                addAll(dao.getByOutcome(EpisodeOutcome.ABANDONED.name, limit = 16))
            }
        } else {
            dao.getRecent(limit = DB_CANDIDATE_LIMIT)
        }

        if (rawCandidates.isEmpty()) return@withContext emptyList()

        val now = System.currentTimeMillis()
        val candidates = rawCandidates
            .distinctBy { it.id }
            .take(DB_CANDIDATE_LIMIT)
            .mapNotNull { entry ->
                val vector = HashEmbedder.fromBytes(entry.embedding)
                val similarity = HashEmbedder.cosine(queryVec, vector)
                if (similarity < MIN_SIMILARITY) return@mapNotNull null

                val recency = recencyScore(now, entry.createdAt)
                val efficiency = efficiencyScore(entry.iterationsCount, entry.totalTimeMs)
                val outcomePrior = when (entry.finalOutcome) {
                    EpisodeOutcome.SUCCESS.name -> 0.08f
                    EpisodeOutcome.FAILURE.name -> 0.045f
                    EpisodeOutcome.ABANDONED.name -> 0.025f
                    else -> 0f
                }
                val relevance = (
                    similarity * 0.79f +
                        recency * 0.07f +
                        efficiency * 0.06f +
                        outcomePrior
                    ).coerceIn(0f, 1f)

                Candidate(
                    entry = entry,
                    vector = vector,
                    semanticSimilarity = similarity,
                    relevance = relevance
                )
            }

        selectWithMmr(candidates, topK).map { it.entry }
    }

    private fun selectWithMmr(
        candidates: List<Candidate>,
        topK: Int
    ): List<Candidate> {
        if (candidates.isEmpty()) return emptyList()

        val remaining = candidates.toMutableList()
        val selected = mutableListOf<Candidate>()

        while (remaining.isNotEmpty() && selected.size < topK) {
            val best = remaining.maxByOrNull { candidate ->
                if (selected.isEmpty()) {
                    candidate.relevance
                } else {
                    val redundancy = selected.maxOf { chosen ->
                        HashEmbedder.cosine(candidate.vector, chosen.vector)
                    }.coerceIn(0f, 1f)
                    MMR_LAMBDA * candidate.relevance -
                        (1f - MMR_LAMBDA) * redundancy
                }
            } ?: break

            selected += best
            remaining.remove(best)
        }

        return selected
    }

    private fun recencyScore(now: Long, createdAt: Long): Float {
        val ageMs = (now - createdAt).coerceAtLeast(0L)
        val ageDays = ageMs.toFloat() / 86_400_000f
        return (1f / (1f + ageDays / 30f)).coerceIn(0f, 1f)
    }

    /**
     * Mild efficiency prior only: relevance still dominates. A 50-iteration success should not
     * outrank a much more similar 10-iteration solution merely because it was faster.
     */
    private fun efficiencyScore(iterations: Int, totalTimeMs: Long): Float {
        val iterationCost = iterations.coerceAtLeast(0) / 20f
        val minuteCost = totalTimeMs.coerceAtLeast(0L).toFloat() / 600_000f
        return (1f / (1f + iterationCost + minuteCost)).coerceIn(0f, 1f)
    }

    suspend fun buildPromptInjection(
        query: String,
        topK: Int = 2,
        maxChars: Int = 800
    ): String = withContext(Dispatchers.IO) {
        val episodes = retrieveSimilar(query, topK)
        if (episodes.isEmpty()) return@withContext ""

        buildString {
            appendLine("\nRelevant past task episodes:")
            for (episode in episodes) {
                val outcomeLabel = when (episode.finalOutcome) {
                    EpisodeOutcome.SUCCESS.name -> "WORKED"
                    EpisodeOutcome.FAILURE.name -> "FAILED"
                    EpisodeOutcome.ABANDONED.name -> "STALLED"
                    else -> episode.finalOutcome
                }
                val tools = episode.toolsUsedCsv
                    .split(',')
                    .filter(String::isNotBlank)
                    .take(5)
                    .joinToString(" → ")
                val line = "[$outcomeLabel] ${episode.summary.take(190)}"
                val toolLine = if (tools.isNotBlank()) "Tools: $tools" else ""

                if (length + line.length + toolLine.length + 3 > maxChars) break
                appendLine(line)
                if (toolLine.isNotBlank()) appendLine(toolLine)
            }
        }
    }

    private suspend fun enforceQuota() {
        try {
            val count = dao.count()
            if (count > maxEpisodes) {
                val toEvict = (count - maxEpisodes).coerceAtLeast(50)
                dao.evictOldest(toEvict)
                Log.d(TAG, "Evicted $toEvict old episodes (count=$count)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "enforceQuota failed: ${t.message}")
        }
    }
}

enum class EpisodeOutcome { SUCCESS, FAILURE, ABANDONED }
