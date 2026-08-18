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
 * RollbackManager — Context note "Context note Context note" (Action Insurance / Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * **Context note**: Context note Context note Context note Context note Context note Context note (write/patch/delete)Context note Context note Context note
 * Manager snapshot Context note Context note Context note Context note Context note Context note. Context note snapshots Context note
 * Context note "action groups" Context note Context note Context note Context note Context note Context note (Context note Context note) Context note.
 *
 * **Mobile-first**:
 * - Context note Context note root (Context note/Context note Context note Context note Context note File API Context note)
 * - Context note ≤ 4 KB → Context note Context note Context note Context note Deflate
 * - Context note > 4 KB → unified diff Context note (Context note ~70%)
 * - 200 snapshot/group max + 50 MB max storage Context note + LRU eviction
 * - Context note SHA-256 Context note Context note Context note Context note
 *
 * **API**:
 *   - newGroup() → Context note actionGroupId Context note Context note lapsohots Context note
 *   - captureBeforeWrite(...) → Context note Context note Context note Context note write/patch
 *   - rollbackGroup(id) → Context note Context note Context note Context note group
 *   - rollbackById(id) → Context note snapshot Context note
 *   - listRecent / listGroups / pin / unpin
 */
class RollbackManager(
    private val dao: RollbackDao,
    /** Context note Context note Context note Context note snapshots Context note Context note (50 MB Context note). */
    private val maxBytesEvictable: Long = 50L * 1024 * 1024,
    /** Context note Context note Context note/group Context note bevaluation. */
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
    // Capture (Context note Context note Context note)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Context note snapshot Context note Context note/Context note Context note. Context note Context note Context note Context note FileToolManager
     * Context note write_file / patch_file / delete_file.
     *
     * @param actionGroupId Context note Context note (Context note newGroup())
     * @param toolName Context note Context note Context note Context note (Context note)
     * @param filePath Context note Context note
     * @param reason Context note Context note (Context note Context note)
     * @return Context note Context note snapshot Context note -1 Context note Context note Context note (Context note Context note Context note Context note)
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
                // Context note Context note Context note Context note → snapshot "Context note" Context note Context note Context note Context note rollback
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

            // Context note Context note Context note Context note Context note Context note Context note-Context note Context note → Context note Context note Context note.
            // Context note storedAsDiff = true Context note Context note Context note Context note Context note diff Context note Context note Context note tool.
            // Context note Context note Context note Context note Context note Context note Context note Context note Context note Context note
            // Context note Context note diff (Context note) Context note finalizeAfterWrite().
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
     * Context note Context note Context note Context note snapshot Context note "Context note Context note" Context note "diff" Context note Context note.
     * Context note Context note Context note FileToolManager Context note write/patch.
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
                    // Context note diff Context note Context note Context note → Context note Context note Context note
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
     * Context note Context note Context note Context note snapshot Context note.
     * @return true Context note Context note
     */
    suspend fun rollbackById(id: Long): Boolean = withContext(Dispatchers.IO) {
        val snap = dao.getById(id) ?: return@withContext false
        applySnapshot(snap)
    }

    /**
     * Context note Context note Context note Context note group Context note (best-effort).
     * @return Context note Context note Context note Context note Context note
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
                // Context note Context note Context note Context note → Context note Context note Context note Context note
                if (file.exists()) file.delete()
            } else {
                file.parentFile?.mkdirs()
                if (snap.storedAsDiff) {
                    // diff → Context note Context note Context note + Context note diff Context note Context note
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
                // Context note 20% Context note Context note Context note (Context note Context note Context note I/O)
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
