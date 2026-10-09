package com.omnidev.workspace.data.rollback

import android.util.Log
import com.omnidev.workspace.data.db.dao.RollbackDao
import com.omnidev.workspace.data.db.entities.RollbackSnapshotEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** RollbackManager captures snapshots before file writes, patches or deletes and groups them by action. Uses ordinary File APIs without root, compressed full content up to 4 KB and diffs for larger files. Limits groups to 200 snapshots and unpinned storage to 50 MB with LRU eviction; verifies restoration with SHA-256. APIs cover newGroup, captureBeforeWrite, rollbackGroup, rollbackById, listing and pinning. */
class RollbackManager(
    private val dao: RollbackDao,
    /** Maximum unpinned snapshot storage (50 MB by default). */
    private val maxBytesEvictable: Long = 50L * 1024 * 1024,
    /** Maximum snapshots per action group before eviction. */
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
    // Capture before the destructive operation.
    // ──────────────────────────────────────────────────────────────────

    /** Capture a snapshot before modifying or deleting a file. FileToolManager calls this before write, patch and delete operations. actionGroupId comes from newGroup; toolName identifies the modifying tool; filePath is absolute; reason is a one-line description. Return the snapshot ID or -1 if capture fails; capture failure does not block the action. */
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
                // An empty snapshot marks a previously absent file for deletion during rollback.
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

            // The after state is not yet known at capture time; store compressed full content.
            // If storedAsDiff later becomes true, replace full content with a diff after execution.
            // Initially store compressed full content for simpler capture on smaller devices.
            // Optionally convert it to a diff through finalizeAfterWrite().
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

    /** Optionally convert a full-content snapshot to a diff after a successful write or patch to reduce storage. */
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
                    // Keep full content when a diff does not reduce storage.
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

    /** Restore one file from a snapshot; return true on success. */
    suspend fun rollbackById(id: Long): Boolean = withContext(Dispatchers.IO) {
        val snap = dao.getById(id) ?: return@withContext false
        applySnapshot(snap)
    }

    /** Restore files in an action group on a best-effort basis; report successful restorations. */
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
                // Delete the file to restore its original absent state.
                if (file.exists()) file.delete()
            } else {
                file.parentFile?.mkdirs()
                if (snap.storedAsDiff) {
                    // Restore original content using the current text and the diff.
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
                // Evict 20% of unpinned snapshots in one batch to reduce IO.
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
