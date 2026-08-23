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
 * ReflexionEngine —     (Agent Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 *   Reflexion paper (NeurIPS 2023):  Agent  "English Text"
 *     (   )     .
 *     k      system prompt.
 *
 *    **mobile-first** (   2-4 GB RAM):
 *
 *   1) ** LLM  **:   rule-based heuristic
 *       +   + .     offline.
 *
 *   2) **Embeddings hash-based**:    .  embedding 1 KB.
 *
 *   3) **Two-stage retrieval**: SQL pre-filter ( 10 ms) → JVM cosine
 *      ranking (≤ 100 candidates 5 ms). Total < 20 ms   5000 .
 *
 *   4) **Bounded growth**:   2000  LRU eviction  .
 *
 *   5) **Quality feedback loop**:      →  .
 *        → .  "English Text"  .
 */
class ReflexionEngine(
    private val dao: ReflexionDao,
    private val maxLessons: Int = 2000,
    private val topKForInjection: Int = 3,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    companion object {
        private const val TAG = "ReflexionEngine"

        /**       "English Text"  . */
        private const val NOTABLE_THRESHOLD_MS = 800L

        /**      . */
        private const val MIN_SIMILARITY = 0.18f

        /**      DB   JVM ranking. */
        private const val DB_CANDIDATE_LIMIT = 80

        /**     -  prompt  . */
        private const val MAX_LESSON_LENGTH = 280
    }

    /**     prompt  (   ). */
    private val activeLessonIds = mutableListOf<Long>()

    // ──────────────────────────────────────────────────────────────────
    // 1.    (  SmartLearningBridge)
    // ──────────────────────────────────────────────────────────────────

    /**
     *   rule-based    "English Text".
     *   background —   AgentPipeline.
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

    /**   ( +  ). */
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

        // Duplicate detection:
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
    // 2.     (  system prompt)
    // ──────────────────────────────────────────────────────────────────

    suspend fun retrieveRelevantLessons(
        contextQuery: String,
        currentToolName: String? = null,
        topK: Int = topKForInjection
    ): List<ReflexionLessonEntry> = withContext(Dispatchers.IO) {
        if (contextQuery.isBlank() && currentToolName.isNullOrBlank()) return@withContext emptyList()

        val queryVec = HashEmbedder.embed("$contextQuery ${currentToolName.orEmpty()}")

        //  1: SQL pre-filter —  candidates
        val candidates = mutableListOf<ReflexionLessonEntry>()

        if (!currentToolName.isNullOrBlank()) {
            candidates += dao.getByTool(currentToolName, limit = 30)
        }
        //    Top-Quality
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

        //  2: cosine ranking  JVM ()
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

        //  ids
        synchronized(activeLessonIds) {
            activeLessonIds.clear()
            activeLessonIds += ranked.map { it.id }
        }

        //  useCount + lastUsedAt
        val now = System.currentTimeMillis()
        for (lesson in ranked) {
            try {
                dao.recordUsage(lesson.id, now, qualityDelta = 0.01f)
            } catch (_: Throwable) { /* ignore */ }
        }

        ranked
    }

    /**       system prompt. */
    suspend fun buildPromptInjection(
        contextQuery: String,
        currentToolName: String? = null,
        maxChars: Int = 600
    ): String = withContext(Dispatchers.IO) {
        val lessons = retrieveRelevantLessons(contextQuery, currentToolName)
        if (lessons.isEmpty()) return@withContext ""

        buildString {
            appendLine("\n💡      (Reflexion):")
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
    // 3.  feedback loop (    )
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
    // 4. Helpers —    LLM
    // ──────────────────────────────────────────────────────────────────

    /**
     *   rule-based. :
     * -  → "  X   Z. : ..."
     * -   → "X    —  "
     * -   → "X    —  limit"
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
                "  $toolName$paramsAbbrev : $errSnippet. " +
                        "  //   ." to false
            }
            executionTimeMs >= NOTABLE_THRESHOLD_MS -> {
                "$toolName$paramsAbbrev  (${executionTimeMs}ms). " +
                        "    limit ." to true
            }
            result.output.length > 4000 -> {
                "$toolName$paramsAbbrev  ${result.output.length} . " +
                        " top_k/limit      ." to true
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

    /**  (MD5 16 hex)      . */
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
