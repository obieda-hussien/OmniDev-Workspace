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
 * It stores notable failures/inefficiencies and successful recovery edges. Retrieval uses semantic
 * relevance + learned quality + MMR diversity so the tiny prompt budget is not filled by three
 * versions of the same lesson. All persisted lesson text is secret-redacted before storage.
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
        private const val MMR_LAMBDA = 0.80f

        private val SENSITIVE_KEY = Regex(
            "(?i)(api_?key|token|secret|password|passwd|otp|authorization|cookie|session|credential)"
        )
        private val SECRET_ASSIGNMENT = Regex(
            "(?i)(\\b(?:api_?key|token|secret|password|passwd|otp|authorization|cookie)\\b)" +
                "\\s*(?:=|:)\\s*(?:\\\"[^\\\"]+\\\"|[^\\s,;&]+)"
        )
        private val BEARER_SECRET = Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{8,}")
        private val LONG_SECRET_LIKE = Regex("\\b[A-Za-z0-9_-]{32,}\\b")
    }

    private data class RankedCandidate(
        val entry: ReflexionLessonEntry,
        val vector: FloatArray,
        val relevance: Float
    )

    /** Lessons used by this run only. */
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

        val duplicateReward = when {
            result.persistentFailure -> 0.04f
            result.isError -> 0.025f
            else -> 0.015f
        }
        upsertBySignature(lesson, duplicateReward)
        enforceQuota()
    }

    /** Learns a positive escape edge from a failed strategy to a successful alternative. */
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

        val safeFailure = redactSecrets(failedOutput)
        val failureSig = signatureOf(safeFailure)
        if (failureSig.isBlank()) return@withContext

        val failedParams = abbreviateParams(failedParameters)
        val recoveryParams = abbreviateParams(recoveryParameters)
        val failureHint = compactFailureHint(safeFailure)
        val lessonText = redactSecrets(
            buildString {
                append("Recovery learned: ")
                append(failedTool).append(failedParams)
                append(" failed (").append(failureHint).append("). ")
                append(recoveryTool).append(recoveryParams)
                append(" succeeded next. Prefer this recovery path when the same failure pattern appears.")
            }
        ).take(MAX_LESSON_LENGTH)

        val recoverySignature = signatureOf(
            "RECOVERY|$failureSig|$failedTool|$recoveryTool|$recoveryParams"
        )
        val lesson = ReflexionLessonEntry(
            toolName = recoveryTool,
            lesson = lessonText,
            errorSignature = recoverySignature,
            embedding = HashEmbedder.toBytes(
                HashEmbedder.embed(
                    "${redactSecrets(userIntent)} $failedTool $failureHint $recoveryTool $lessonText"
                )
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
        if (topK <= 0) return@withContext emptyList()
        if (contextQuery.isBlank() && currentToolName.isNullOrBlank()) {
            return@withContext emptyList()
        }

        val queryVec = HashEmbedder.embed(
            "${redactSecrets(contextQuery)} ${currentToolName.orEmpty()}"
        )
        val raw = mutableListOf<ReflexionLessonEntry>()

        if (!currentToolName.isNullOrBlank()) {
            raw += dao.getByTool(currentToolName, limit = 30)
        }
        if (raw.size < DB_CANDIDATE_LIMIT) {
            val remaining = DB_CANDIDATE_LIMIT - raw.size
            val seenIds = raw.mapTo(HashSet()) { it.id }
            for (entry in dao.getTopCandidates(remaining * 2)) {
                if (entry.id in seenIds) continue
                raw += entry
                seenIds += entry.id
                if (raw.size >= DB_CANDIDATE_LIMIT) break
            }
        }

        if (raw.isEmpty()) return@withContext emptyList()

        val candidates = raw.distinctBy { it.id }.mapNotNull { entry ->
            val vector = HashEmbedder.fromBytes(entry.embedding)
            val similarity = HashEmbedder.cosine(queryVec, vector)
            if (similarity < MIN_SIMILARITY) return@mapNotNull null

            // Quality is learned from downstream task outcomes. Similarity stays dominant.
            val successPrior = if (entry.successContext) 0.025f else 0f
            val relevance = (
                similarity * 0.70f +
                    entry.quality.coerceIn(0f, 1f) * 0.275f +
                    successPrior
                ).coerceIn(0f, 1f)
            RankedCandidate(entry, vector, relevance)
        }

        val ranked = selectWithMmr(candidates, topK).map { it.entry }

        synchronized(activeLessonIds) {
            activeLessonIds.clear()
            activeLessonIds += ranked.map { it.id }
        }

        val now = System.currentTimeMillis()
        for (lesson in ranked) {
            try {
                dao.recordUsage(lesson.id, now, qualityDelta = 0.01f)
            } catch (_: Throwable) {
                // Best effort; retrieval must not fail because usage accounting failed.
            }
        }

        ranked
    }

    private fun selectWithMmr(
        candidates: List<RankedCandidate>,
        topK: Int
    ): List<RankedCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val remaining = candidates.toMutableList()
        val selected = mutableListOf<RankedCandidate>()

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
                val line = "$icon $toolHint${redactSecrets(lesson.lesson).take(MAX_LESSON_LENGTH)}"
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
            } catch (_: Throwable) {
                // Best effort feedback.
            }
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
        val metadata = buildMetadataHint(result)
        val safeOutput = redactSecrets(result.output)

        val (lessonText, success, initialQuality) = when {
            result.isError -> {
                val errSnippet = compactFailureHint(safeOutput, 135)
                val strategy = when {
                    result.persistentFailure ->
                        "Do not retry the same backend blindly; pivot backend/capability or surface the blocker."
                    result.retryable ->
                        "A bounded retry may help, then pivot if the same classification repeats."
                    else ->
                        "Avoid the identical call; change strategy, parameters, or tool."
                }
                Triple(
                    "$toolName$paramsAbbrev failed$metadata: $errSnippet. $strategy",
                    false,
                    if (result.persistentFailure) 0.64f else 0.54f
                )
            }
            executionTimeMs >= NOTABLE_THRESHOLD_MS -> Triple(
                "$toolName$paramsAbbrev was slow (${executionTimeMs}ms)$metadata. " +
                    "Prefer batching, caching, narrower scope, or a cheaper equivalent probe.",
                true,
                0.50f
            )
            result.output.length > 4000 -> Triple(
                "$toolName$paramsAbbrev returned ${result.output.length} chars$metadata. " +
                    "Prefer top_k/limit/range parameters to keep context compact.",
                true,
                0.50f
            )
            else -> return null
        }

        val truncated = redactSecrets(lessonText).take(MAX_LESSON_LENGTH)
        val signatureMaterial = buildString {
            append(result.classification.orEmpty()).append('|')
            append(result.backend.orEmpty()).append('|')
            append(safeOutput)
        }
        val signature = if (result.isError) signatureOf(signatureMaterial) else ""
        val embedding = HashEmbedder.embed(
            "${redactSecrets(toolName)} ${redactSecrets(userIntent)} $truncated"
        )

        return ReflexionLessonEntry(
            toolName = toolName,
            lesson = truncated,
            errorSignature = signature,
            embedding = HashEmbedder.toBytes(embedding),
            successContext = success,
            createdAt = System.currentTimeMillis(),
            lastUsedAt = System.currentTimeMillis(),
            quality = initialQuality
        )
    }

    private fun buildMetadataHint(result: ToolExecutionResult): String {
        val pieces = mutableListOf<String>()
        result.classification?.takeIf { it.isNotBlank() }?.let { pieces += "class=${it.take(40)}" }
        result.backend?.takeIf { it.isNotBlank() }?.let { pieces += "backend=${it.take(32)}" }
        result.exitCode?.let { pieces += "exit=$it" }
        if (result.verification?.isNotBlank() == true) pieces += "verified"
        if (result.persistentFailure) pieces += "persistent"
        if (pieces.isEmpty()) return ""
        return " [${pieces.joinToString(", ")}]"
    }

    private fun compactFailureHint(text: String, maxChars: Int = 90): String = redactSecrets(text)
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(maxChars)

    private fun abbreviateParams(params: Map<String, Any?>): String {
        if (params.isEmpty()) return ""
        val pretty = params.entries.take(3).joinToString(", ") { (key, value) ->
            val safe = if (SENSITIVE_KEY.containsMatchIn(key)) {
                "[REDACTED]"
            } else {
                redactSecrets(value?.toString().orEmpty()).take(40).ifBlank { "null" }
            }
            "$key=$safe"
        }
        return " ($pretty)"
    }

    private fun redactSecrets(value: String): String = value
        .replace(SECRET_ASSIGNMENT) { match ->
            val key = match.groupValues.getOrNull(1).orEmpty().ifBlank { "secret" }
            "$key=[REDACTED]"
        }
        .replace(BEARER_SECRET, "Bearer [REDACTED]")
        .replace(LONG_SECRET_LIKE) { token ->
            val text = token.value
            if (text.all(Char::isDigit)) text else "[REDACTED]"
        }

    private fun signatureOf(text: String): String {
        val normalized = redactSecrets(text).take(300)
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
