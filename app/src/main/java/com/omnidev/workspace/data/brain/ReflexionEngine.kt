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
 * ReflexionEngine — local, mobile-first execution lesson memory.
 *
 * Besides remembering failures/slow calls, it learns recovery transitions: when one tool/strategy
 * fails and a later alternative succeeds, the successful escape path is stored as a higher-value
 * positive lesson. Persistent lessons are shared; active attribution is run-local.
 */
class ReflexionEngine(
    private val dao: ReflexionDao,
    private val maxLessons: Int = 2000,
    private val topKForInjection: Int = 3,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    companion object {
        private const val TAG = "ReflexionEngine"
        private const val NOTABLE_THRESHOLD_MS = 800L
        private const val MIN_SIMILARITY = 0.18f
        private const val DB_CANDIDATE_LIMIT = 80
        private const val MAX_LESSON_LENGTH = 280
        private const val RECOVERY_INITIAL_QUALITY = 0.72f
    }

    private val activeLessonIds = mutableListOf<Long>()

    fun forkForRun(): ReflexionEngine = ReflexionEngine(
        dao = dao,
        maxLessons = maxLessons,
        topKForInjection = topKForInjection,
        scope = scope
    )

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

    suspend fun recordExperience(
        toolName: String,
        parameters: Map<String, Any?>,
        result: ToolExecutionResult,
        executionTimeMs: Long,
        userIntent: String = ""
    ) = withContext(Dispatchers.IO) {
        val isNotable = result.isError ||
            executionTimeMs >= NOTABLE_THRESHOLD_MS ||
            result.output.length > 4000
        if (!isNotable) return@withContext

        val lesson = synthesizeLesson(toolName, parameters, result, executionTimeMs, userIntent)
            ?: return@withContext

        upsertBySignature(lesson, duplicateReward = 0.02f)
        enforceQuota()
    }

    /**
     * Learns a positive "escape edge" from a failed strategy to a successful recovery.
     * Example: advanced_terminal(permission denied) -> shizuku_command succeeded.
     */
    fun recordRecoveryAsync(
        failedTool: String,
        failedParameters: Map<String, Any?>,
        failedOutput: String,
        recoveryTool: String,
        recoveryParameters: Map<String, Any?>,
        userIntent: String = ""
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                recordRecovery(
                    failedTool = failedTool,
                    failedParameters = failedParameters,
                    failedOutput = failedOutput,
                    recoveryTool = recoveryTool,
                    recoveryParameters = recoveryParameters,
                    userIntent = userIntent
                )
            } catch (t: Throwable) {
                Log.w(TAG, "recordRecovery failed: ${t.message}")
            }
        }
    }

    suspend fun recordRecovery(
        failedTool: String,
        failedParameters: Map<String, Any?>,
        failedOutput: String,
        recoveryTool: String,
        recoveryParameters: Map<String, Any?>,
        userIntent: String = ""
    ) = withContext(Dispatchers.IO) {
        if (failedTool.isBlank() || recoveryTool.isBlank() || failedOutput.isBlank()) {
            return@withContext
        }

        val failureSig = signatureOf(failedOutput)
        if (failureSig.isBlank()) return@withContext

        val failedParams = abbreviateParams(failedParameters)
        val recoveryParams = abbreviateParams(recoveryParameters)
        val failureHint = compactFailureHint(failedOutput)
        val lessonText = buildString {
            append("Recovery learned: ")
            append(failedTool).append(failedParams)
            append(" failed (").append(failureHint).append("). ")
            append(recoveryTool).append(recoveryParams)
            append(" succeeded next. Prefer this recovery path when the same failure pattern appears.")
        }.take(MAX_LESSON_LENGTH)

        val recoverySignature = signatureOf("RECOVERY|$failureSig|$failedTool|$recoveryTool|$recoveryParams")
        val lesson = ReflexionLessonEntry(
            toolName = recoveryTool,
            lesson = lessonText,
            errorSignature = recoverySignature,
            embedding = HashEmbedder.toBytes(
                HashEmbedder.embed("$userIntent $failedTool $failureHint $recoveryTool $lessonText")
            ),
            successContext = true,
            createdAt = System.currentTimeMillis(),
            lastUsedAt = System.currentTimeMillis(),
            quality = RECOVERY_INITIAL_QUALITY
        )

        upsertBySignature(lesson, duplicateReward = 0.06f)
        enforceQuota()
    }

    suspend fun retrieveRelevantLessons(
        contextQuery: String,
        currentToolName: String? = null,
        topK: Int = topKForInjection
    ): List<ReflexionLessonEntry> = withContext(Dispatchers.IO) {
        if (contextQuery.isBlank() && currentToolName.isNullOrBlank()) return@withContext emptyList()

        val queryVec = HashEmbedder.embed("$contextQuery ${currentToolName.orEmpty()}")
        val candidates = mutableListOf<ReflexionLessonEntry>()

        if (!currentToolName.isNullOrBlank()) {
            candidates += dao.getByTool(currentToolName, limit = 30)
        }
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

        val ranked = candidates
            .map { entry ->
                val sim = HashEmbedder.cosine(queryVec, HashEmbedder.fromBytes(entry.embedding))
                entry to sim
            }
            .filter { it.second >= MIN_SIMILARITY }
            .sortedByDescending { pair ->
                // Positive recovery lessons get their advantage through quality, not hard-coded type.
                pair.second * 0.68f + pair.first.quality * 0.32f
            }
            .take(topK)
            .map { it.first }

        synchronized(activeLessonIds) {
            activeLessonIds.clear()
            activeLessonIds += ranked.map { it.id }
        }

        val now = System.currentTimeMillis()
        for (lesson in ranked) {
            try {
                dao.recordUsage(lesson.id, now, qualityDelta = 0.01f)
            } catch (_: Throwable) { /* best effort */ }
        }

        ranked
    }

    suspend fun buildPromptInjection(
        contextQuery: String,
        currentToolName: String? = null,
        maxChars: Int = 600
    ): String = withContext(Dispatchers.IO) {
        val lessons = retrieveRelevantLessons(contextQuery, currentToolName)
        if (lessons.isEmpty()) return@withContext ""

        buildString {
            appendLine("\nLearned execution lessons (Reflexion):")
            for (lesson in lessons) {
                val icon = if (lesson.successContext) "✅" else "⚠️"
                val toolHint = if (lesson.toolName.isNotBlank()) "[${lesson.toolName}] " else ""
                val line = "$icon $toolHint${lesson.lesson.take(MAX_LESSON_LENGTH)}"
                if (length + line.length + 1 > maxChars) break
                appendLine(line)
            }
        }
    }

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
            } catch (_: Throwable) { /* best effort */ }
        }

        synchronized(activeLessonIds) { activeLessonIds.clear() }
    }

    private suspend fun upsertBySignature(
        lesson: ReflexionLessonEntry,
        duplicateReward: Float
    ) {
        if (lesson.errorSignature.isNotBlank()) {
            val existing = dao.getBySignature(lesson.errorSignature, limit = 1).firstOrNull()
            if (existing != null) {
                dao.recordUsage(
                    existing.id,
                    System.currentTimeMillis(),
                    qualityDelta = duplicateReward
                )
                return
            }
        }
        dao.insert(lesson)
    }

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
                val errSnippet = compactFailureHint(result.output, 150)
                "$toolName$paramsAbbrev failed: $errSnippet. Avoid repeating the identical call; change strategy or parameters." to false
            }
            executionTimeMs >= NOTABLE_THRESHOLD_MS -> {
                "$toolName$paramsAbbrev was slow (${executionTimeMs}ms). Prefer batching, caching, narrower scope, or a cheaper probe when equivalent." to true
            }
            result.output.length > 4000 -> {
                "$toolName$paramsAbbrev returned ${result.output.length} chars. Prefer top_k/limit/range parameters to keep context compact." to true
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

    private fun compactFailureHint(text: String, maxChars: Int = 90): String = text
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(maxChars)

    private fun abbreviateParams(params: Map<String, Any?>): String {
        if (params.isEmpty()) return ""
        val pretty = params.entries.take(3).joinToString(", ") { (k, v) ->
            val sv = v?.toString()?.take(40) ?: "null"
            "$k=$sv"
        }
        return " ($pretty)"
    }

    private fun signatureOf(text: String): String {
        val normalized = text.take(240)
            .replace(Regex("/[\\w./-]+"), "/PATH")
            .replace(Regex("\\b[0-9a-f]{8,}\\b", RegexOption.IGNORE_CASE), "HEX")
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
            val count = dao.count()
            if (count > maxLessons) {
                val toEvict = (count - maxLessons).coerceAtLeast(50)
                dao.evictLowestQuality(toEvict)
                Log.d(TAG, "Evicted $toEvict low-quality lessons (count=$count)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "enforceQuota failed: ${t.message}")
        }
    }
}
