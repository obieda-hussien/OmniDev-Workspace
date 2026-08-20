package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * BuildDiagnosticEntry — System awareness note System awareness note System awareness note (Build Doctor Pro / Brain 2.0).
 *
 * System awareness note System awareness note System awareness note:
 *   - System awareness note System awareness note (fingerprint) System awareness note System awareness note System awareness note System awareness note System awareness note
 *   - System awareness note (compile / link / dependency / resource / runtime / config)
 *   - System awareness note System awareness note System awareness note (System awareness note Deflate) System awareness note System awareness note
 *   - System awareness note System awareness note/System awareness note System awareness note System awareness note
 *
 * Mobile-first:
 * - 500 System awareness note System awareness note System awareness note System awareness note LRU eviction
 * - solutionDiff System awareness note System awareness note Deflate (~70% System awareness note)
 *
 * @property errorFingerprint MD5(message normalized) — System awareness note System awareness note System awareness note System awareness note System awareness note
 * @property occurrenceCount System awareness note System awareness note System awareness note System awareness note System awareness note
 * @property successfulFixCount System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
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
