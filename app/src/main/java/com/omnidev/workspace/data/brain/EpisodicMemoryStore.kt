package com.omnidev.workspace.data.brain

import android.util.Log
import com.omnidev.workspace.data.db.dao.EpisodicMemoryDao
import com.omnidev.workspace.data.db.entities.EpisodicMemoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * EpisodicMemoryStore — ذاكرة المهام الكاملة (Agent Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * بعكس [ToolExecutionJournal] الذي يسجّل كل أداة على حدة، هذا الـ Store يحفظ
 * **مهمة كاملة** كحلقة (episode) واحدة:
 *
 *   "User asked X → Agent ran tools [A, B, C] → Result: Y"
 *
 * عند مهمة جديدة مشابهة، نسترجع 1-2 episode متشابهين دلالياً ونحقنهم في الـ
 * system prompt كـ "memory shots". هذا يقلل trial-and-error ويرفع نسبة النجاح.
 *
 * **Mobile-first** (يعمل على 2-4 GB RAM):
 * - HashEmbedder (لا نموذج، 0 RAM دائم)
 * - candidates ≤ 80 ثم cosine في JVM
 * - حد أقصى 2000 حلقة (~2-3 MB)
 * - Eviction للأقدم تلقائياً
 */
class EpisodicMemoryStore(
    private val dao: EpisodicMemoryDao,
    private val maxEpisodes: Int = 2000,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    companion object {
        private const val TAG = "EpisodicMemoryStore"
        private const val MIN_SIMILARITY = 0.20f
        private const val DB_CANDIDATE_LIMIT = 80
        private const val MAX_SUMMARY_LENGTH = 500
        private const val MAX_TOOLS_STORED = 10
    }

    /** يحفظ episode جديد بشكل غير متزامن (لا يبطئ AgentPipeline). */
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
                    summary, userIntent, finalOutcome, toolsUsed,
                    iterationsCount, totalTimeMs, sessionId
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

        // embedding من intent + summary لاسترجاع دقيق
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
        enforceQuota()
        id
    }

    /**
     * استرجاع episodes مشابهة:
     *   1) candidates من DB (ناجحين حديثين أساساً + بعض الفاشلين للحذر)
     *   2) cosine ranking في JVM
     *   3) فلترة بـ minSimilarity → topK = 2
     */
    suspend fun retrieveSimilar(
        query: String,
        topK: Int = 2,
        preferSuccess: Boolean = true
    ): List<EpisodicMemoryEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val queryVec = HashEmbedder.embed(query)

        // candidates = ناجحين حديثين + بعض الفاشلين (للحذر)
        val candidates = mutableListOf<EpisodicMemoryEntry>()
        if (preferSuccess) {
            candidates += dao.getByOutcome(EpisodeOutcome.SUCCESS.name, limit = 60)
            if (candidates.size < DB_CANDIDATE_LIMIT) {
                candidates += dao.getByOutcome(EpisodeOutcome.FAILURE.name, limit = 20)
            }
        } else {
            candidates += dao.getRecent(limit = DB_CANDIDATE_LIMIT)
        }

        if (candidates.isEmpty()) return@withContext emptyList()

        candidates
            .map { entry ->
                val sim = HashEmbedder.cosine(queryVec, HashEmbedder.fromBytes(entry.embedding))
                entry to sim
            }
            .filter { it.second >= MIN_SIMILARITY }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    /** يبني نص الحقن للـ system prompt من episodes متشابهة. */
    suspend fun buildPromptInjection(
        query: String,
        topK: Int = 2,
        maxChars: Int = 800
    ): String = withContext(Dispatchers.IO) {
        val episodes = retrieveSimilar(query, topK)
        if (episodes.isEmpty()) return@withContext ""

        buildString {
            appendLine("\n📚 ذاكرة مهام سابقة مشابهة (Episodic Memory):")
            for (ep in episodes) {
                val icon = when (ep.finalOutcome) {
                    "SUCCESS" -> "✅"
                    "FAILURE" -> "❌"
                    else -> "⚠️"
                }
                val tools = ep.toolsUsedCsv.split(',').take(5).joinToString(" → ")
                val line = "$icon ${ep.summary.take(180)}"
                val toolLine = if (tools.isNotBlank()) "   🔧 المسار: $tools" else ""
                if (length + line.length + toolLine.length + 2 > maxChars) break
                appendLine(line)
                if (toolLine.isNotBlank()) appendLine(toolLine)
            }
        }
    }

    private suspend fun enforceQuota() {
        try {
            val cnt = dao.count()
            if (cnt > maxEpisodes) {
                val toEvict = (cnt - maxEpisodes).coerceAtLeast(50)
                dao.evictOldest(toEvict)
                Log.d(TAG, "🧹 evicted $toEvict old episodes (cnt=$cnt)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "enforceQuota failed: ${t.message}")
        }
    }
}

/** نتيجة المهمة المسجَّلة. */
enum class EpisodeOutcome { SUCCESS, FAILURE, ABANDONED }
