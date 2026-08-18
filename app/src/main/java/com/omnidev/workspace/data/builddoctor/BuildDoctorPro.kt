package com.omnidev.workspace.data.builddoctor

import android.util.Log
import com.omnidev.workspace.data.db.dao.BuildDiagnosticDao
import com.omnidev.workspace.data.db.entities.BuildDiagnosticEntry
import com.omnidev.workspace.data.rollback.DiffUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * BuildDoctorPro — [Localized] [Localized] [Localized] (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Mobile-first: [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]/[Localized] [Localized] [Localized]:
 *
 *   1) **Fingerprint-based dedup**: [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
 *      [Localized] [Localized].
 *
 *   2) **Solution memory**: [Localized] [Localized] [Localized] [Localized] ([Localized] Agent [Localized] [Localized] [Localized] [Localized])[Localized]
 *      [Localized] [Localized] diff [Localized] Deflate [Localized] [Localized] [Localized] [Localized] [Localized].
 *
 *   3) **Confidence ranking**: [Localized] [Localized] successfulFixCount > 0 [Localized] [Localized].
 *      [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
 *
 *   4) **500 [Localized] max + LRU eviction** ([Localized] < 5 MB [Localized]).
 *
 *   5) **No external deps**: [Localized] API calls[Localized] [Localized] LLM. [Localized] regex + SQL [Localized].
 */
class BuildDoctorPro(
    private val dao: BuildDiagnosticDao,
    private val maxEntries: Int = 500
) {

    companion object {
        private const val TAG = "BuildDoctorPro"
    }

    // ──────────────────────────────────────────────────────────────────
    // Diagnose — [Localized] + [Localized] [Localized] [Localized] [Localized]
    // ──────────────────────────────────────────────────────────────────

    data class Diagnosis(
        val errors: List<BuildErrorParser.ParsedError>,
        val knownSolutions: List<KnownSolution>,
        val newErrors: List<BuildErrorParser.ParsedError>,
        val totalSeenBefore: Int,
        val summary: String
    )

    data class KnownSolution(
        val fingerprint: String,
        val message: String,
        val category: String,
        val occurrenceCount: Int,
        val successfulFixCount: Int,
        val failedFixCount: Int,
        val solutionDiff: String,
        val explanation: String
    )

    /**
     * [Localized] output [Localized] build[Localized] [Localized]:
     *   - [Localized] [Localized]
     *   - [Localized] [Localized] ([Localized] [Localized] [Localized])
     *   - [Localized] [Localized] ([Localized] [Localized] [Localized] [Localized])
     */
    suspend fun diagnose(
        buildOutput: String,
        buildCommand: String = ""
    ): Diagnosis = withContext(Dispatchers.IO) {
        val errors = BuildErrorParser.parse(buildOutput)
        if (errors.isEmpty()) {
            return@withContext Diagnosis(
                errors = emptyList(),
                knownSolutions = emptyList(),
                newErrors = emptyList(),
                totalSeenBefore = 0,
                summary = "✅ no build errors detected"
            )
        }

        val known = ArrayList<KnownSolution>()
        val newOnes = ArrayList<BuildErrorParser.ParsedError>()
        var seen = 0

        for (err in errors) {
            val existing = dao.findByFingerprint(err.fingerprint)
            if (existing != null) {
                seen++
                dao.recordOccurrence(existing.id, System.currentTimeMillis())
                if (existing.successfulFixCount > 0 || existing.solutionDiff.isNotEmpty()) {
                    val diffText = if (existing.solutionDiff.isNotEmpty()) {
                        try {
                            DiffUtils.decompress(existing.solutionDiff).toString(Charsets.UTF_8)
                        } catch (_: Throwable) { "" }
                    } else ""
                    known += KnownSolution(
                        fingerprint = existing.errorFingerprint,
                        message = existing.message,
                        category = existing.category,
                        occurrenceCount = existing.occurrenceCount + 1,
                        successfulFixCount = existing.successfulFixCount,
                        failedFixCount = existing.failedFixCount,
                        solutionDiff = diffText,
                        explanation = existing.explanation
                    )
                }
            } else {
                // [Localized] [Localized] [Localized] [Localized] [Localized]
                val files = if (err.filePath.isNotBlank()) err.filePath.take(200) else ""
                val now = System.currentTimeMillis()
                val entry = BuildDiagnosticEntry(
                    errorFingerprint = err.fingerprint,
                    category = err.category,
                    message = err.message,
                    buildCommand = buildCommand.take(200),
                    solutionDiff = ByteArray(0),
                    explanation = "",
                    occurrenceCount = 1,
                    lastSeenAt = now,
                    reportedFiles = files,
                    createdAt = now
                )
                try {
                    dao.insert(entry)
                } catch (_: Throwable) { /* unique constraint or transient */ }
                newOnes += err
            }
        }

        enforceQuota()

        val summary = buildString {
            appendLine("🔧 Build Doctor: ${errors.size} error(s) detected")
            if (known.isNotEmpty()) appendLine("✅ ${known.size} known (with previous fix)")
            if (newOnes.isNotEmpty()) appendLine("🆕 ${newOnes.size} new")
            appendLine("📊 ${seen}/${errors.size} seen before in this project")
        }.trim()

        Diagnosis(
            errors = errors,
            knownSolutions = known.sortedByDescending { it.successfulFixCount - it.failedFixCount },
            newErrors = newOnes,
            totalSeenBefore = seen,
            summary = summary
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Solution recording ([Localized] [Localized] [Localized] [Localized])
    // ──────────────────────────────────────────────────────────────────

    /**
     * [Localized] [Localized] [Localized] [Localized]: [Localized] [Localized] diff [Localized] [Localized] [Localized].
     * @param fingerprint [Localized] [Localized]
     * @param solutionDiff [Localized] diff [Localized] [Localized] [Localized] Agent ([Localized])
     * @param explanation [Localized] [Localized] [Localized] (≤ 200 [Localized])
     */
    suspend fun recordSuccessfulFix(
        fingerprint: String,
        solutionDiff: String,
        explanation: String = ""
    ) = withContext(Dispatchers.IO) {
        try {
            val existing = dao.findByFingerprint(fingerprint) ?: return@withContext
            val compressed = if (solutionDiff.isNotBlank()) {
                DiffUtils.compress(solutionDiff.take(20_000).toByteArray())
            } else existing.solutionDiff

            dao.update(
                existing.copy(
                    solutionDiff = compressed,
                    explanation = if (explanation.isNotBlank()) explanation.take(200)
                                  else existing.explanation
                )
            )
            dao.recordSuccessfulFix(existing.id, System.currentTimeMillis())
            Log.d(TAG, "✅ recorded fix for $fingerprint")
        } catch (t: Throwable) {
            Log.w(TAG, "recordSuccessfulFix failed: ${t.message}")
        }
    }

    /** [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized] [Localized] [Localized] [Localized]). */
    suspend fun recordFailedFix(fingerprint: String) = withContext(Dispatchers.IO) {
        try {
            val existing = dao.findByFingerprint(fingerprint) ?: return@withContext
            dao.recordFailedFix(existing.id)
        } catch (t: Throwable) {
            Log.w(TAG, "recordFailedFix failed: ${t.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Listing
    // ──────────────────────────────────────────────────────────────────

    suspend fun topSolutions(limit: Int = 30): List<KnownSolution> =
        withContext(Dispatchers.IO) {
            dao.getKnownSolutions(limit).map { e ->
                val diff = if (e.solutionDiff.isNotEmpty()) {
                    try { DiffUtils.decompress(e.solutionDiff).toString(Charsets.UTF_8) }
                    catch (_: Throwable) { "" }
                } else ""
                KnownSolution(
                    fingerprint = e.errorFingerprint,
                    message = e.message,
                    category = e.category,
                    occurrenceCount = e.occurrenceCount,
                    successfulFixCount = e.successfulFixCount,
                    failedFixCount = e.failedFixCount,
                    solutionDiff = diff,
                    explanation = e.explanation
                )
            }
        }

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    // ──────────────────────────────────────────────────────────────────
    // Quota
    // ──────────────────────────────────────────────────────────────────

    private suspend fun enforceQuota() {
        try {
            val cnt = dao.count()
            if (cnt > maxEntries) {
                val toEvict = (cnt - maxEntries).coerceAtLeast(20)
                dao.evictWeakest(toEvict)
                Log.d(TAG, "🧹 evicted $toEvict weak diagnostics (cnt=$cnt)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "enforceQuota failed: ${t.message}")
        }
    }
}
