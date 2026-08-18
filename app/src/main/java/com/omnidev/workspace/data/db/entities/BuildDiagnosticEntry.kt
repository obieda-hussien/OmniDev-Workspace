package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * BuildDiagnosticEntry — [Localized] [Localized] [Localized] (Build Doctor Pro / Brain 2.0).
 *
 * [Localized] [Localized] [Localized]:
 *   - [Localized] [Localized] (fingerprint) [Localized] [Localized] [Localized] [Localized] [Localized]
 *   - [Localized] (compile / link / dependency / resource / runtime / config)
 *   - [Localized] [Localized] [Localized] ([Localized] Deflate) [Localized] [Localized]
 *   - [Localized] [Localized]/[Localized] [Localized] [Localized]
 *
 * Mobile-first:
 * - 500 [Localized] [Localized] [Localized] [Localized] LRU eviction
 * - solutionDiff [Localized] [Localized] Deflate (~70% [Localized])
 *
 * @property errorFingerprint MD5(message normalized) — [Localized] [Localized] [Localized] [Localized] [Localized]
 * @property occurrenceCount [Localized] [Localized] [Localized] [Localized] [Localized]
 * @property successfulFixCount [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
 */
@Entity(
    tableName = "build_diagnostics",
    indices = [
        Index(value = ["errorFingerprint"]),
        Index(value = ["category"]),
        Index(value = ["lastSeenAt"])
    ]
)
data class BuildDiagnosticEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val errorFingerprint: String,
    val category: String,
    val message: String,
    val buildCommand: String = "",
    /** Deflated solution diff/snippet (may be empty if no fix recorded yet). */
    val solutionDiff: ByteArray = ByteArray(0),
    val explanation: String = "",
    val occurrenceCount: Int = 1,
    val successfulFixCount: Int = 0,
    val failedFixCount: Int = 0,
    val lastSeenAt: Long = System.currentTimeMillis(),
    val lastFixedAt: Long = 0,
    /** comma-separated reported file paths (≤ 5). */
    val reportedFiles: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BuildDiagnosticEntry) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
