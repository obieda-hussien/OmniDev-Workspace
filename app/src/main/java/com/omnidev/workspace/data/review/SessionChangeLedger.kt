package com.omnidev.workspace.data.review

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import com.omnidev.workspace.core.policy.ConfirmationKind
import java.util.UUID

enum class ChangeKind { MODIFIED, CREATED, DELETED }
enum class ChangeStatus { PENDING, KEPT, REVERTED }

/**
 * One file touched by the agent during a session.
 *
 * [baseline] is the file content the FIRST time the agent touched it in this session
 * (null when the file did not exist). Everything the agent does afterwards is diffed
 * against it, so the review shows the net result of the whole session — not each edit.
 */
data class TrackedChange(
    val id: String = UUID.randomUUID().toString(),
    val sessionKey: Long,
    val path: String,
    val kind: ChangeKind,
    val status: ChangeStatus = ChangeStatus.PENDING,
    val baseline: String?,
    val baselineKnown: Boolean,
    val editCount: Int = 1,
    val firstTouchAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null
) {
    val fileName: String get() = path.substringAfterLast('/').ifEmpty { path }
    val parentDir: String get() = path.substringBeforeLast('/', "").substringAfterLast('/')
}

enum class DiffLineType { CONTEXT, ADDED, REMOVED, COLLAPSED }

data class DiffLine(val type: DiffLineType, val oldNo: Int?, val newNo: Int?, val text: String)

data class FileDiff(val lines: List<DiffLine>, val added: Int, val removed: Int, val currentAvailable: Boolean)

/** A privileged non-file action (shell command, intent, connected-app call...) that already ran. */
data class ActionRecord(
    val id: String = UUID.randomUUID().toString(),
    val sessionKey: Long,
    val kind: ConfirmationKind,
    val preview: String,
    val diff: String? = null,
    val at: Long = System.currentTimeMillis()
)

data class ResolveResult(val succeeded: Int, val errors: List<String>)

/**
 * Session-scoped change ledger. File tools apply edits immediately (so builds/tests see real
 * files) while this ledger remembers the pre-session state of each file. The user then reviews
 * the net diff and keeps or reverts any subset of files — no per-edit dialog interrupts the agent.
 */
class SessionChangeLedger {

    private val _changes = MutableStateFlow<List<TrackedChange>>(emptyList())
    val changes: StateFlow<List<TrackedChange>> = _changes.asStateFlow()

    private val _actions = MutableStateFlow<List<ActionRecord>>(emptyList())
    val actions: StateFlow<List<ActionRecord>> = _actions.asStateFlow()

    fun recordAction(sessionKey: Long, kind: ConfirmationKind, preview: String, diff: String? = null) {
        _actions.update { (it + ActionRecord(sessionKey = sessionKey, kind = kind, preview = preview.take(MAX_PREVIEW), diff = diff?.take(MAX_PREVIEW))).takeLast(MAX_ACTIONS) }
    }

    fun clearActions(sessionKey: Long) { _actions.update { l -> l.filterNot { it.sessionKey == sessionKey } } }

    /** Called by the file tools right BEFORE they write, so the disk still holds the original. */
    suspend fun recordBeforeWrite(sessionKey: Long, path: String, kind: ChangeKind) {
        val baseline = withContext(Dispatchers.IO) { readBaseline(path, kind) }
        _changes.update { list ->
            val idx = list.indexOfFirst { it.sessionKey == sessionKey && it.path == path && it.status == ChangeStatus.PENDING }
            if (idx < 0) {
                list + TrackedChange(
                    sessionKey = sessionKey, path = path, kind = kind,
                    baseline = baseline.first, baselineKnown = baseline.second
                )
            } else {
                val old = list[idx]
                val merged = when {
                    // Created in this session then deleted again: net effect is nothing.
                    old.kind == ChangeKind.CREATED && kind == ChangeKind.DELETED -> null
                    // Existing file deleted then recreated: it is a modification of the original.
                    old.kind == ChangeKind.DELETED && kind != ChangeKind.DELETED -> old.copy(kind = ChangeKind.MODIFIED, editCount = old.editCount + 1)
                    kind == ChangeKind.DELETED -> old.copy(kind = ChangeKind.DELETED, editCount = old.editCount + 1)
                    else -> old.copy(editCount = old.editCount + 1)
                }
                val out = list.toMutableList()
                if (merged == null) out.removeAt(idx) else out[idx] = merged
                out
            }
        }
    }

    private fun readBaseline(path: String, kind: ChangeKind): Pair<String?, Boolean> {
        val f = File(path)
        // A file the app cannot see (protected path) is not proof it is absent, so only a
        // CREATED change may assume "no original"; anything else must not be auto-deleted on revert.
        if (!f.exists()) return null to (kind == ChangeKind.CREATED)
        if (kind == ChangeKind.CREATED) return null to true
        return try {
            if (f.length() > MAX_BASELINE_BYTES) null to false else f.readText() to true
        } catch (_: Exception) { null to false }
    }

    fun forSession(sessionKey: Long): List<TrackedChange> = _changes.value.filter { it.sessionKey == sessionKey }

    suspend fun keep(ids: Set<String>): ResolveResult {
        mark(ids, ChangeStatus.KEPT)
        return ResolveResult(ids.size, emptyList())
    }

    /** Restore the pre-session version of each file (or delete files the agent created). */
    suspend fun revert(ids: Set<String>): ResolveResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val done = mutableSetOf<String>()
        _changes.value.filter { it.id in ids && it.status != ChangeStatus.REVERTED }.forEach { c ->
            try {
                when {
                    c.status == ChangeStatus.KEPT && _changes.value.any { it.path == c.path && it.sessionKey == c.sessionKey && it.status == ChangeStatus.PENDING } ->
                        errors += "${c.fileName}: edited again since it was kept — review the newer change instead"
                    !c.baselineKnown -> errors += "${c.fileName}: original not captured, cannot revert"
                    c.baseline == null -> { File(c.path).takeIf { it.exists() }?.delete(); done += c.id }
                    else -> { File(c.path).also { it.parentFile?.mkdirs() }.writeText(c.baseline); done += c.id }
                }
            } catch (e: Exception) { errors += "${c.fileName}: ${e.message}" }
        }
        mark(done, ChangeStatus.REVERTED)
        ResolveResult(done.size, errors)
    }

    private fun mark(ids: Set<String>, status: ChangeStatus) {
        val now = System.currentTimeMillis()
        _changes.update { list -> list.map { if (it.id in ids && it.status != status && it.status != ChangeStatus.REVERTED) it.copy(status = status, resolvedAt = now) else it } }
    }

    fun clearResolved(sessionKey: Long) {
        _changes.update { list -> list.filterNot { it.sessionKey == sessionKey && it.status != ChangeStatus.PENDING } }
    }

    /** Net diff: original (baseline) vs what is on disk now. Heavy — call off the main thread. */
    suspend fun diffFor(change: TrackedChange): FileDiff = withContext(Dispatchers.Default) {
        val current: String? = try {
            File(change.path).takeIf { it.exists() && it.isFile && it.length() <= MAX_BASELINE_BYTES }?.readText()
        } catch (_: Exception) { null }
        val oldText = change.baseline ?: ""
        val newText = if (change.kind == ChangeKind.DELETED) "" else current ?: ""
        val available = change.kind == ChangeKind.DELETED || current != null
        buildDiff(oldText, newText, available)
    }

    companion object {
        private const val MAX_PREVIEW = 4_000
        private const val MAX_ACTIONS = 300
        private const val MAX_BASELINE_BYTES = 2L * 1024 * 1024
        private const val MAX_LCS_CELLS = 4_000_000L
        private const val CONTEXT = 3

        internal fun buildDiff(oldText: String, newText: String, available: Boolean = true): FileDiff {
            val a = if (oldText.isEmpty()) emptyList() else oldText.split('\n')
            val b = if (newText.isEmpty()) emptyList() else newText.split('\n')
            var start = 0
            while (start < a.size && start < b.size && a[start] == b[start]) start++
            var endA = a.size; var endB = b.size
            while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }
            val ma = a.subList(start, endA); val mb = b.subList(start, endB)

            val ops = ArrayList<DiffLine>()
            for (i in 0 until start) ops += DiffLine(DiffLineType.CONTEXT, i + 1, i + 1, a[i])
            if (ma.size.toLong() * mb.size <= MAX_LCS_CELLS) {
                val w = mb.size + 1
                val dp = IntArray((ma.size + 1) * w)
                for (i in ma.size - 1 downTo 0) for (j in mb.size - 1 downTo 0)
                    dp[i * w + j] = if (ma[i] == mb[j]) dp[(i + 1) * w + j + 1] + 1 else maxOf(dp[(i + 1) * w + j], dp[i * w + j + 1])
                var i = 0; var j = 0
                while (i < ma.size && j < mb.size) {
                    when {
                        ma[i] == mb[j] -> { ops += DiffLine(DiffLineType.CONTEXT, start + i + 1, start + j + 1, ma[i]); i++; j++ }
                        dp[(i + 1) * w + j] >= dp[i * w + j + 1] -> { ops += DiffLine(DiffLineType.REMOVED, start + i + 1, null, ma[i]); i++ }
                        else -> { ops += DiffLine(DiffLineType.ADDED, null, start + j + 1, mb[j]); j++ }
                    }
                }
                while (i < ma.size) { ops += DiffLine(DiffLineType.REMOVED, start + i + 1, null, ma[i]); i++ }
                while (j < mb.size) { ops += DiffLine(DiffLineType.ADDED, null, start + j + 1, mb[j]); j++ }
            } else {
                ma.forEachIndexed { i, l -> ops += DiffLine(DiffLineType.REMOVED, start + i + 1, null, l) }
                mb.forEachIndexed { j, l -> ops += DiffLine(DiffLineType.ADDED, null, start + j + 1, l) }
            }
            for (k in 0 until a.size - endA) ops += DiffLine(DiffLineType.CONTEXT, endA + k + 1, endB + k + 1, a[endA + k])

            val added = ops.count { it.type == DiffLineType.ADDED }
            val removed = ops.count { it.type == DiffLineType.REMOVED }
            return FileDiff(collapse(ops), added, removed, available)
        }

        /** Keep CONTEXT lines only within [CONTEXT] lines of a change; fold the rest. */
        private fun collapse(ops: List<DiffLine>): List<DiffLine> {
            if (ops.none { it.type != DiffLineType.CONTEXT }) return ops
            val keep = BooleanArray(ops.size)
            ops.forEachIndexed { i, l ->
                if (l.type != DiffLineType.CONTEXT) for (k in maxOf(0, i - CONTEXT)..minOf(ops.lastIndex, i + CONTEXT)) keep[k] = true
            }
            val out = ArrayList<DiffLine>()
            var i = 0
            while (i < ops.size) {
                if (keep[i]) { out += ops[i]; i++ } else {
                    var j = i
                    while (j < ops.size && !keep[j]) j++
                    out += DiffLine(DiffLineType.COLLAPSED, null, null, "${j - i} unchanged lines")
                    i = j
                }
            }
            return out
        }
    }
}
