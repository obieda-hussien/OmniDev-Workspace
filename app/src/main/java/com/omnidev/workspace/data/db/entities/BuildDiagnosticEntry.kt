package com.omnidev.workspace.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * BuildDiagnosticEntry — Context note Context note Context note (Build Doctor Pro / Brain 2.0).
 *
 * Context note Context note Context note:
 *   - Context note Context note (fingerprint) Context note Context note Context note Context note Context note
 *   - Context note (compile / link / dependency / resource / runtime / config)
 *   - Context note Context note Context note (Context note Deflate) Context note Context note
 *   - Context note Context note/Context note Context note Context note
 *
 * Mobile-first:
 * - 500 Context note Context note Context note Context note LRU eviction
 * - solutionDiff Context note Context note Deflate (~70% Context note)
 *
 * @property errorFingerprint MD5(message normalized) — Context note Context note Context note Context note Context note
 * @property occurrenceCount Context note Context note Context note Context note Context note
 * @property successfulFixCount Context note Context note Context note Context note Context note Context note Context note
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
