package com.omnidev.workspace.data.rollback

import android.util.Log
import com.omnidev.workspace.data.db.dao.RollbackDao
import com.omnidev.workspace.data.db.entities.RollbackSnapshotEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * RollbackManager — System awareness note "System awareness note System awareness note" (Action Insurance / Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * **System awareness note**: System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note (write/patch/delete)System awareness note System awareness note System awareness note
 * Manager snapshot System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note. System awareness note snapshots System awareness note
 * System awareness note "action groups" System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note (System awareness note System awareness note) System awareness note.
 *
 * **Mobile-first**:
 * - System awareness note System awareness note root (System awareness note/System awareness note System awareness note System awareness note System awareness note File API System awareness note)
 * - System awareness note ≤ 4 KB → System awareness note System awareness note System awareness note System awareness note Deflate
 * - System awareness note > 4 KB → unified diff System awareness note (System awareness note ~70%)
 * - 200 snapshot/group max + 50 MB max storage System awareness note + LRU eviction
 * - System awareness note SHA-256 System awareness note System awareness note System awareness note System awareness note
 *
 * **API**:
 *   - newGroup() → System awareness note actionGroupId System awareness note System awareness note lapsohots System awareness note
 *   - captureBeforeWrite(...) → System awareness note System awareness note System awareness note System awareness note write/patch
 *   - rollbackGroup(id) → System awareness note System awareness note System awareness note System awareness note group
 *   - rollbackById(id) → System awareness note snapshot System awareness note
 *   - listRecent / listGroups / pin / unpin
 */
class RollbackManager(
    private val dao: RollbackDao,
    /** System awareness note System awareness note System awareness note System awareness note snapshots System awareness note System awareness note (50 MB System awareness note). */
    private val maxBytesEvictable: Long = 50L * 1024 * 1024,
    /** System awareness note System awareness note System awareness note/group System awareness note bevaluation. */
    private val maxSnapshotsPerGroup: Int = 200
) {

    companion object {
        private const val TAG = "RollbackManager"
    }

    // ──────────────────────────────────────────────────────────────────
    // Group lifecycle
    // ──────────────────────────────────────────────────────────────────

    fun newGroup(reason: String = ""): String {
        val id = "grp_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        Log.d(TAG, "🔒 new action group: $id (reason=$reason)")
        return id
    }

    // ──────────────────────────────────────────────────────────────────
    // Capture (System awareness note System awareness note System awareness note)
    // ──────────────────────────────────────────────────────────────────

    /**
     * System awareness note snapshot System awareness note System awareness note/System awareness note System awareness note. System awareness note System awareness note System awareness note System awareness note FileToolManager
     * System awareness note write_file / patch_file / delete_file.
     *
     * @param actionGroupId System awareness note System awareness note (System awareness note newGroup())
     * @param toolName System awareness note System awareness note System awareness note System awareness note (System awareness note)
     * @param filePath System awareness note System awareness note
     * @param reason System awareness note System awareness note (System awareness note System awareness note)
     * @return System awareness note System awareness note snapshot System awareness note -1 System awareness note System awareness note System awareness note (System awareness note System awareness note System awareness note System awareness note)
     */
    suspend fun captureBeforeWrite(
        actionGroupId: String,
        toolName: String,
        filePath: String,
        reason: String = ""
    ): Long = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            val existed = file.exists()

            if (!existed) {
                // System awareness note System awareness note System awareness note System awareness note → snapshot "System awareness note" System awareness note System awareness note System awareness note System awareness note rollback
                val entry = RollbackSnapshotEntry(
                    actionGroupId = actionGroupId,
                    toolName = toolName,
                    filePath = filePath,
                    existedBefore = false,
                    contentBlob = ByteArray(0),
                    storedAsDiff = false,
                    originalSizeBytes = 0,
                    originalHash = "",
                    reason = reason
                )
                val id = dao.insert(entry)
                enforceQuota()
                return@withContext id
            }

            if (file.length() > DiffUtils.MAX_FILE_SIZE_BYTES) {
                Log.w(TAG, "skip snapshot — file too large: $filePath (${file.length()})")
                return@withContext -1
            }

            val original = file.readBytes()
            val hash = DiffUtils.sha256(original)
            val storedAsDiff = original.size > DiffUtils.FULL_CONTENT_THRESHOLD_BYTES

            // System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note-System awareness note System awareness note → System awareness note System awareness note System awareness note.
            // System awareness note storedAsDiff = true System awareness note System awareness note System awareness note System awareness note System awareness note diff System awareness note System awareness note System awareness note tool.
            // System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note
            // System awareness note System awareness note diff (System awareness note) System awareness note finalizeAfterWrite().
            val compressed = DiffUtils.compress(original)

            val entry = RollbackSnapshotEntry(
                actionGroupId = actionGroupId,
                toolName = toolName,
                filePath = filePath,
                existedBefore = true,
                contentBlob = compressed,
                storedAsDiff = false,
                originalSizeBytes = original.size.toLong(),
                originalHash = hash,
                reason = reason
            )
            val id = dao.insert(entry)
            enforceQuota()
            id
        } catch (t: Throwable) {
            Log.w(TAG, "captureBeforeWrite failed: ${t.message}")
            -1
        }
    }

    /**
     * System awareness note System awareness note System awareness note System awareness note snapshot System awareness note "System awareness note System awareness note" System awareness note "diff" System awareness note System awareness note.
     * System awareness note System awareness note System awareness note FileToolManager System awareness note write/patch.
     */
    suspend fun finalizeAfterWrite(snapshotId: Long, filePath: String) =
        withContext(Dispatchers.IO) {
            if (snapshotId < 0) return@withContext
            try {
                val snap = dao.getById(snapshotId) ?: return@withContext
                if (!snap.existedBefore || snap.storedAsDiff) return@withContext
                if (snap.originalSizeBytes <= DiffUtils.FULL_CONTENT_THRESHOLD_BYTES) return@withContext

                val file = File(filePath)
                if (!file.exists()) return@withContext

                val originalBytes = DiffUtils.decompress(snap.contentBlob)
                val original = originalBytes.toString(Charsets.UTF_8)
                val current = file.readText(Charsets.UTF_8)
                if (original == current) return@withContext

                val diff = DiffUtils.buildDiff(before = original, after = current)
                if (diff.length >= original.length) {
                    // System awareness note diff System awareness note System awareness note System awareness note → System awareness note System awareness note System awareness note
                    return@withContext
                }
                val diffCompressed = DiffUtils.compress(diff.toByteArray())
                val postHash = DiffUtils.sha256(current)

                dao.insert(snap.copy(
                    contentBlob = diffCompressed,
                    storedAsDiff = true,
                    postEditHash = postHash
                ))
            } catch (t: Throwable) {
                Log.w(TAG, "finalizeAfterWrite failed: ${t.message}")
            }
        }

    // ──────────────────────────────────────────────────────────────────
    // Rollback
    // ──────────────────────────────────────────────────────────────────

    /**
     * System awareness note System awareness note System awareness note System awareness note snapshot System awareness note.
     * @return true System awareness note System awareness note
     */
    suspend fun rollbackById(id: Long): Boolean = withContext(Dispatchers.IO) {
        val snap = dao.getById(id) ?: return@withContext false
        applySnapshot(snap)
    }

    /**
     * System awareness note System awareness note System awareness note System awareness note group System awareness note (best-effort).
     * @return System awareness note System awareness note System awareness note System awareness note System awareness note
     */
    suspend fun rollbackGroup(groupId: String): RollbackResult = withContext(Dispatchers.IO) {
        val snaps = dao.getByGroup(groupId, limit = maxSnapshotsPerGroup)
        if (snaps.isEmpty()) return@withContext RollbackResult(0, 0, listOf("no snapshots in group"))

        var ok = 0
        val errors = mutableListOf<String>()
        for (snap in snaps) {
            try {
                if (applySnapshot(snap)) ok++
                else errors += "fail: ${snap.filePath}"
            } catch (t: Throwable) {
                errors += "${snap.filePath}: ${t.message}"
            }
        }
        RollbackResult(ok, snaps.size, errors)
    }

    private suspend fun applySnapshot(snap: RollbackSnapshotEntry): Boolean {
        val file = File(snap.filePath)
        return try {
            if (!snap.existedBefore) {
                // System awareness note System awareness note System awareness note System awareness note → System awareness note System awareness note System awareness note System awareness note
                if (file.exists()) file.delete()
            } else {
                file.parentFile?.mkdirs()
                if (snap.storedAsDiff) {
                    // diff → System awareness note System awareness note System awareness note + System awareness note diff System awareness note System awareness note
                    val diff = DiffUtils.decompress(snap.contentBlob).toString(Charsets.UTF_8)
                    val current = if (file.exists()) file.readText(Charsets.UTF_8) else ""
                    val original = DiffUtils.applyReverseDiff(current, diff)
                        ?: return false
                    file.writeText(original, Charsets.UTF_8)
                } else {
                    val original = DiffUtils.decompress(snap.contentBlob)
                    file.writeBytes(original)
                }
            }
            dao.markRolledBack(snap.id)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "applySnapshot failed for ${snap.filePath}: ${t.message}")
            false
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Listing / Management
    // ──────────────────────────────────────────────────────────────────

    suspend fun listRecent(limit: Int = 30) =
        withContext(Dispatchers.IO) { dao.getRecent(limit) }

    suspend fun listGroups(limit: Int = 30) =
        withContext(Dispatchers.IO) { dao.getGroupSummaries(limit) }

    suspend fun pin(id: Long) = withContext(Dispatchers.IO) { dao.setPinned(id, true) }
    suspend fun unpin(id: Long) = withContext(Dispatchers.IO) { dao.setPinned(id, false) }

    // ──────────────────────────────────────────────────────────────────
    // Quota Enforcement
    // ──────────────────────────────────────────────────────────────────

    private suspend fun enforceQuota() {
        try {
            val used = dao.totalEvictableBytes()
            if (used > maxBytesEvictable) {
                // System awareness note 20% System awareness note System awareness note System awareness note (System awareness note System awareness note System awareness note I/O)
                val cnt = dao.countEvictable()
                val toEvict = (cnt / 5).coerceAtLeast(20)
                dao.evictOldestUnpinned(toEvict)
                Log.d(TAG, "🧹 evicted $toEvict snapshots (used=${used / 1024} KB)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "enforceQuota failed: ${t.message}")
        }
    }

    data class RollbackResult(
        val restored: Int,
        val attempted: Int,
        val errors: List<String>
    )
}
