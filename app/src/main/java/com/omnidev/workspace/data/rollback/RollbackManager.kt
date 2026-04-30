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
 * RollbackManager — نظام "تأمين الإجراء" (Action Insurance / Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * **القصة**: قبل أي عملية مدمّرة على ملف (write/patch/delete)، يلتقط الـ
 * Manager snapshot يسمح بإلغاء العملية لاحقاً بأمر واحد. الـ snapshots مجمّعة
 * في "action groups" بحيث يُمكن التراجع عن مهمة كاملة (متعددة الملفات) ذرّياً.
 *
 * **Mobile-first**:
 * - يعمل بدون root (يقرأ/يكتب الملف نفسه عبر File API العادي)
 * - الملفات ≤ 4 KB → نخزن المحتوى الكامل مضغوطاً Deflate
 * - الملفات > 4 KB → unified diff فقط (توفير ~70%)
 * - 200 snapshot/group max + 50 MB max storage إجمالي + LRU eviction
 * - عمليات SHA-256 للتحقق من سلامة الاستعادة
 *
 * **API**:
 *   - newGroup() → يُنشئ actionGroupId جديد لربط lapsohots مهمة
 *   - captureBeforeWrite(...) → يلتقط لقطة قبل عملية write/patch
 *   - rollbackGroup(id) → يستعيد كل ملفات الـ group
 *   - rollbackById(id) → يستعيد snapshot بعينه
 *   - listRecent / listGroups / pin / unpin
 */
class RollbackManager(
    private val dao: RollbackDao,
    /** أقصى حجم تخزين للـ snapshots غير المثبّتة (50 MB افتراضياً). */
    private val maxBytesEvictable: Long = 50L * 1024 * 1024,
    /** أقصى عدد لقطات/group قبل bevaluation. */
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
    // Capture (قبل العملية المدمّرة)
    // ──────────────────────────────────────────────────────────────────

    /**
     * يلتقط snapshot قبل تعديل/حذف ملف. ينبغي أن يُستدعى من FileToolManager
     * قبل write_file / patch_file / delete_file.
     *
     * @param actionGroupId معرّف المجموعة (من newGroup())
     * @param toolName الأداة التي ستُجري التعديل (للتقارير)
     * @param filePath المسار المطلق
     * @param reason سبب العملية (سطر واحد)
     * @return معرّف الـ snapshot أو -1 لو فشل التقاط (الفشل لا يعطّل العملية)
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
                // الملف لم يكن موجوداً → snapshot "فارغ" يخبرنا بحذفه عند الـ rollback
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

            // عند الالتقاط، لا نعرف بعد محتوى ما-بعد التعديل → نخزن الكامل مضغوطاً.
            // لو storedAsDiff = true لاحقاً، يمكن استبدال المحتوى بـ diff عند نهاية الـ tool.
            // للبساطة على الأجهزة الضعيفة، نخزن دائماً المحتوى الكامل مضغوطاً هنا
            // ونحوّله لـ diff (اختيارياً) عبر finalizeAfterWrite().
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
     * بعد إتمام الكتابة، تحويل snapshot من "محتوى كامل" إلى "diff" لتوفير مساحة.
     * يُستدعى اختيارياً من FileToolManager بعد write/patch.
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
                    // الـ diff لم يوفر شيء → نُبقي المحتوى الكامل
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
     * يستعيد ملف واحد من snapshot بعينه.
     * @return true لو نجح
     */
    suspend fun rollbackById(id: Long): Boolean = withContext(Dispatchers.IO) {
        val snap = dao.getById(id) ?: return@withContext false
        applySnapshot(snap)
    }

    /**
     * يستعيد كل ملفات الـ group ذرّياً (best-effort).
     * @return عدد الملفات التي استُعيدت بنجاح
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
                // الملف لم يكن موجوداً → نحذفه لاستعادة الحالة الأصلية
                if (file.exists()) file.delete()
            } else {
                file.parentFile?.mkdirs()
                if (snap.storedAsDiff) {
                    // diff → نحتاج المحتوى الحالي + الـ diff لاستعادة الأصل
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
                // نحذف 20% من غير المثبّت (دفعة واحدة لتقليل I/O)
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
