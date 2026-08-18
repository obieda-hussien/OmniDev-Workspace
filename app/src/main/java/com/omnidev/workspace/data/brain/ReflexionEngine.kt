package com.omnidev.workspace.data.brain

import android.util.Log
import com.omnidev.workspace.data.db.dao.ReflexionDao
import com.omnidev.workspace.data.db.entities.ReflexionLessonEntry
import com.omnidev.workspace.data.tools.ToolExecutionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * ReflexionEngine — [Localized] [Localized] [Localized] [Localized] (Agent Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * [Localized] [Localized] Reflexion paper (NeurIPS 2023): [Localized] Agent [Localized] "[Localized]" [Localized]
 * [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized] [Localized]) [Localized] [Localized] [Localized] [Localized] [Localized]. [Localized]
 * [Localized] [Localized] [Localized] [Localized] k [Localized] [Localized] [Localized] [Localized] [Localized] system prompt.
 *
 * [Localized] [Localized] [Localized] **mobile-first** ([Localized] [Localized] [Localized] 2-4 GB RAM):
 *
 *   1) **[Localized] LLM [Localized] [Localized]**: [Localized] [Localized] rule-based heuristic [Localized] [Localized]
 *      [Localized] + [Localized] [Localized] + [Localized]. [Localized] [Localized] [Localized] [Localized] offline.
 *
 *   2) **Embeddings hash-based**: [Localized] [Localized] [Localized] [Localized]. [Localized] embedding 1 KB.
 *
 *   3) **Two-stage retrieval**: SQL pre-filter ([Localized] 10 ms) → JVM cosine
 *      ranking (≤ 100 candidates[Localized] 5 ms). [Localized] < 20 ms [Localized] [Localized] 5000 [Localized].
 *
 *   4) **Bounded growth**: [Localized] [Localized] 2000 [Localized] LRU eviction [Localized] [Localized].
 *
 *   5) **Quality feedback loop**: [Localized] [Localized] [Localized] [Localized] [Localized] → [Localized] [Localized].
 *      [Localized] [Localized] → [Localized]. [Localized] "[Localized]" [Localized] [Localized].
 */
class ReflexionEngine(
    private val dao: ReflexionDao,
    private val maxLessons: Int = 2000,
    private val topKForInjection: Int = 3,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    companion object {
        private const val TAG = "ReflexionEngine"

        /** [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] "[Localized]" [Localized] [Localized]. */
        private const val NOTABLE_THRESHOLD_MS = 800L

        /** [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]. */
        private const val MIN_SIMILARITY = 0.18f

        /** [Localized] [Localized] [Localized] [Localized] [Localized] DB [Localized] [Localized] JVM ranking. */
        private const val DB_CANDIDATE_LIMIT = 80

        /** [Localized] [Localized] [Localized] [Localized] [Localized]-[Localized] [Localized] prompt [Localized] [Localized]. */
        private const val MAX_LESSON_LENGTH = 280
    }

    /** [Localized] [Localized] [Localized] [Localized] prompt [Localized] ([Localized] [Localized] [Localized] [Localized]). */
    private val activeLessonIds = mutableListOf<Long>()

    // ──────────────────────────────────────────────────────────────────
    // 1. [Localized] [Localized] [Localized] ([Localized] [Localized] SmartLearningBridge)
    // ──────────────────────────────────────────────────────────────────

    /**
     * [Localized] [Localized] rule-based [Localized] [Localized] [Localized] "[Localized]".
     * [Localized] [Localized] background — [Localized] [Localized] AgentPipeline.
     */
    fun recordExperienceAsync(
        toolName: String,
        parameters: Map<String, Any?>,
        result: ToolExecutionResult,
        executionTimeMs: Long,
        userIntent: String = ""
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                recordExperience(toolName, parameters, result, executionTimeMs, userIntent)
            } catch (t: Throwable) {
                Log.w(TAG, "recordExperience failed: ${t.message}")
            }
        }
    }

    /** [Localized] [Localized] ([Localized] + [Localized] [Localized]). */
    suspend fun recordExperience(
        toolName: String,
        parameters: Map<String, Any?>,
        result: ToolExecutionResult,
        executionTimeMs: Long,
        userIntent: String = ""
    ) = withContext(Dispatchers.IO) {
        val isNotable = when {
            result.isError -> true
            executionTimeMs >= NOTABLE_THRESHOLD_MS -> true
            result.output.length > 4000 -> true
            else -> false
        }
        if (!isNotable) return@withContext

        val lesson = synthesizeLesson(toolName, parameters, result, executionTimeMs, userIntent)
            ?: return@withContext

        // Duplicate detection: [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
        if (lesson.errorSignature.isNotBlank()) {
            val existing = dao.getBySignature(lesson.errorSignature, limit = 1).firstOrNull()
            if (existing != null) {
                dao.recordUsage(existing.id, System.currentTimeMillis(), qualityDelta = 0.02f)
                return@withContext
            }
        }

        dao.insert(lesson)
        enforceQuota()
    }

    // ──────────────────────────────────────────────────────────────────
    // 2. [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized] system prompt)
    // ──────────────────────────────────────────────────────────────────

    suspend fun retrieveRelevantLessons(
        contextQuery: String,
        currentToolName: String? = null,
        topK: Int = topKForInjection
    ): List<ReflexionLessonEntry> = withContext(Dispatchers.IO) {
        if (contextQuery.isBlank() && currentToolName.isNullOrBlank()) return@withContext emptyList()

        val queryVec = HashEmbedder.embed("$contextQuery ${currentToolName.orEmpty()}")

        // [Localized] 1: SQL pre-filter — [Localized] candidates [Localized]
        val candidates = mutableListOf<ReflexionLessonEntry>()

        if (!currentToolName.isNullOrBlank()) {
            candidates += dao.getByTool(currentToolName, limit = 30)
        }
        // [Localized] [Localized] [Localized] Top-Quality [Localized]
        if (candidates.size < DB_CANDIDATE_LIMIT) {
            val remaining = DB_CANDIDATE_LIMIT - candidates.size
            val seenIds = candidates.mapTo(HashSet()) { it.id }
            for (entry in dao.getTopCandidates(remaining * 2)) {
                if (entry.id in seenIds) continue
                candidates += entry
                if (candidates.size >= DB_CANDIDATE_LIMIT) break
            }
        }

        if (candidates.isEmpty()) return@withContext emptyList()

        // [Localized] 2: cosine ranking [Localized] JVM ([Localized])
        val ranked = candidates
            .map { entry ->
                val sim = HashEmbedder.cosine(queryVec, HashEmbedder.fromBytes(entry.embedding))
                entry to sim
            }
            .filter { it.second >= MIN_SIMILARITY }
            .sortedByDescending { pair ->
                // similarity * 0.7 + quality * 0.3
                pair.second * 0.7f + pair.first.quality * 0.3f
            }
            .take(topK)
            .map { it.first }

        // [Localized] ids [Localized] [Localized] [Localized] [Localized] [Localized]
        synchronized(activeLessonIds) {
            activeLessonIds.clear()
            activeLessonIds += ranked.map { it.id }
        }

        // [Localized] useCount + lastUsedAt [Localized] [Localized]
        val now = System.currentTimeMillis()
        for (lesson in ranked) {
            try {
                dao.recordUsage(lesson.id, now, qualityDelta = 0.01f)
            } catch (_: Throwable) { /* ignore */ }
        }

        ranked
    }

    /** [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] system prompt. */
    suspend fun buildPromptInjection(
        contextQuery: String,
        currentToolName: String? = null,
        maxChars: Int = 600
    ): String = withContext(Dispatchers.IO) {
        val lessons = retrieveRelevantLessons(contextQuery, currentToolName)
        if (lessons.isEmpty()) return@withContext ""

        buildString {
            appendLine("\n💡 [Localized] [Localized] [Localized] [Localized] [Localized] (Reflexion):")
            for (l in lessons) {
                val icon = if (l.successContext) "✅" else "⚠️"
                val toolHint = if (l.toolName.isNotBlank()) "[${l.toolName}] " else ""
                val line = "$icon $toolHint${l.lesson.take(MAX_LESSON_LENGTH)}"
                if (length + line.length + 1 > maxChars) break
                appendLine(line)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 3. [Localized] feedback loop ([Localized] [Localized] [Localized] [Localized] [Localized])
    // ──────────────────────────────────────────────────────────────────

    suspend fun reportTaskOutcome(success: Boolean) = withContext(Dispatchers.IO) {
        val ids = synchronized(activeLessonIds) { activeLessonIds.toList() }
        if (ids.isEmpty()) return@withContext

        for (id in ids) {
            try {
                if (success) {
                    dao.recordUsage(id, System.currentTimeMillis(), qualityDelta = 0.04f)
                } else {
                    dao.penalize(id, penalty = 0.06f)
                }
            } catch (_: Throwable) { /* ignore */ }
        }

        synchronized(activeLessonIds) { activeLessonIds.clear() }
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. Helpers — [Localized] [Localized] [Localized] LLM
    // ──────────────────────────────────────────────────────────────────

    /**
     * [Localized] [Localized] rule-based. [Localized]:
     * - [Localized] → "[Localized] [Localized] X[Localized] [Localized] [Localized] Z. [Localized]: ..."
     * - [Localized] [Localized] → "X [Localized] [Localized] [Localized] — [Localized] [Localized]"
     * - [Localized] [Localized] → "X [Localized] [Localized] [Localized] — [Localized] limit"
     */
    private fun synthesizeLesson(
        toolName: String,
        parameters: Map<String, Any?>,
        result: ToolExecutionResult,
        executionTimeMs: Long,
        userIntent: String
    ): ReflexionLessonEntry? {
        val paramsAbbrev = abbreviateParams(parameters)
        val (lessonText, success) = when {
            result.isError -> {
                val errSnippet = result.output.take(150).replace('\n', ' ')
                "[Localized] [Localized] $toolName$paramsAbbrev[Localized] [Localized]: $errSnippet. " +
                        "[Localized] [Localized] [Localized]/[Localized]/[Localized] [Localized] [Localized] [Localized]." to false
            }
            executionTimeMs >= NOTABLE_THRESHOLD_MS -> {
                "$toolName$paramsAbbrev [Localized] (${executionTimeMs}ms). " +
                        "[Localized] [Localized] [Localized] [Localized] limit [Localized]." to true
            }
            result.output.length > 4000 -> {
                "$toolName$paramsAbbrev [Localized] ${result.output.length} [Localized]. " +
                        "[Localized] top_k/limit [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]." to true
            }
            else -> return null
        }

        val truncated = lessonText.take(MAX_LESSON_LENGTH)
        val signature = if (result.isError) signatureOf(result.output) else ""
        val embedding = HashEmbedder.embed("$toolName $userIntent $truncated")

        return ReflexionLessonEntry(
            toolName = toolName,
            lesson = truncated,
            errorSignature = signature,
            embedding = HashEmbedder.toBytes(embedding),
            successContext = success,
            createdAt = System.currentTimeMillis(),
            lastUsedAt = System.currentTimeMillis(),
            quality = 0.5f
        )
    }

    private fun abbreviateParams(params: Map<String, Any?>): String {
        if (params.isEmpty()) return ""
        val pretty = params.entries.take(3).joinToString(", ") { (k, v) ->
            val sv = v?.toString()?.take(40) ?: "null"
            "$k=$sv"
        }
        return " ($pretty)"
    }

    /** [Localized] (MD5 16 hex) [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]. */
    private fun signatureOf(text: String): String {
        val normalized = text.take(200)
            .replace(Regex("/[\\w./-]+"), "/PATH")
            .replace(Regex("\\d+"), "N")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return ""
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(normalized.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private suspend fun enforceQuota() {
        try {
            val cnt = dao.count()
            if (cnt > maxLessons) {
                val toEvict = (cnt - maxLessons).coerceAtLeast(50)
                dao.evictLowestQuality(toEvict)
                Log.d(TAG, "🧹 evicted $toEvict low-quality lessons (cnt=$cnt)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "enforceQuota failed: ${t.message}")
        }
    }
}
