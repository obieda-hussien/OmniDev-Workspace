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
 * RollbackManager — [Localized] "[Localized] [Localized]" (Action Insurance / Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * **[Localized]**: [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] (write/patch/delete)[Localized] [Localized] [Localized]
 * Manager snapshot [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]. [Localized] snapshots [Localized]
 * [Localized] "action groups" [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized]) [Localized].
 *
 * **Mobile-first**:
 * - [Localized] [Localized] root ([Localized]/[Localized] [Localized] [Localized] [Localized] File API [Localized])
 * - [Localized] ≤ 4 KB → [Localized] [Localized] [Localized] [Localized] Deflate
 * - [Localized] > 4 KB → unified diff [Localized] ([Localized] ~70%)
 * - 200 snapshot/group max + 50 MB max storage [Localized] + LRU eviction
 * - [Localized] SHA-256 [Localized] [Localized] [Localized] [Localized]
 *
 * **API**:
 *   - newGroup() → [Localized] actionGroupId [Localized] [Localized] lapsohots [Localized]
 *   - captureBeforeWrite(...) → [Localized] [Localized] [Localized] [Localized] write/patch
 *   - rollbackGroup(id) → [Localized] [Localized] [Localized] [Localized] group
 *   - rollbackById(id) → [Localized] snapshot [Localized]
 *   - listRecent / listGroups / pin / unpin
 */
class RollbackManager(
    private val dao: RollbackDao,
    /** [Localized] [Localized] [Localized] [Localized] snapshots [Localized] [Localized] (50 MB [Localized]). */
    private val maxBytesEvictable: Long = 50L * 1024 * 1024,
    /** [Localized] [Localized] [Localized]/group [Localized] bevaluation. */
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
    // Capture ([Localized] [Localized] [Localized])
    // ──────────────────────────────────────────────────────────────────

    /**
     * [Localized] snapshot [Localized] [Localized]/[Localized] [Localized]. [Localized] [Localized] [Localized] [Localized] FileToolManager
     * [Localized] write_file / patch_file / delete_file.
     *
     * @param actionGroupId [Localized] [Localized] ([Localized] newGroup())
     * @param toolName [Localized] [Localized] [Localized] [Localized] ([Localized])
     * @param filePath [Localized] [Localized]
     * @param reason [Localized] [Localized] ([Localized] [Localized])
     * @return [Localized] [Localized] snapshot [Localized] -1 [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized] [Localized])
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
                // [Localized] [Localized] [Localized] [Localized] → snapshot "[Localized]" [Localized] [Localized] [Localized] [Localized] rollback
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

            // [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]-[Localized] [Localized] → [Localized] [Localized] [Localized].
            // [Localized] storedAsDiff = true [Localized] [Localized] [Localized] [Localized] [Localized] diff [Localized] [Localized] [Localized] tool.
            // [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
            // [Localized] [Localized] diff ([Localized]) [Localized] finalizeAfterWrite().
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
     * [Localized] [Localized] [Localized] [Localized] snapshot [Localized] "[Localized] [Localized]" [Localized] "diff" [Localized] [Localized].
     * [Localized] [Localized] [Localized] FileToolManager [Localized] write/patch.
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
                    // [Localized] diff [Localized] [Localized] [Localized] → [Localized] [Localized] [Localized]
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
     * [Localized] [Localized] [Localized] [Localized] snapshot [Localized].
     * @return true [Localized] [Localized]
     */
    suspend fun rollbackById(id: Long): Boolean = withContext(Dispatchers.IO) {
        val snap = dao.getById(id) ?: return@withContext false
        applySnapshot(snap)
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] group [Localized] (best-effort).
     * @return [Localized] [Localized] [Localized] [Localized] [Localized]
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
                // [Localized] [Localized] [Localized] [Localized] → [Localized] [Localized] [Localized] [Localized]
                if (file.exists()) file.delete()
            } else {
                file.parentFile?.mkdirs()
                if (snap.storedAsDiff) {
                    // diff → [Localized] [Localized] [Localized] + [Localized] diff [Localized] [Localized]
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
                // [Localized] 20% [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized] I/O)
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
