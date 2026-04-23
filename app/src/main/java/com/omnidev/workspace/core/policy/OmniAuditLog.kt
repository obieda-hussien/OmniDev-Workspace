package com.omnidev.workspace.core.policy

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Forensic audit log for privileged actions.
 *
 * The OEM flavor auto-approves every `ConfirmationGate.request()` for zero-click
 * execution. To keep the system auditable anyway, every auto-approval — and,
 * optionally, every explicit user approval — writes an immutable record here.
 *
 * The log is:
 *   * **Process-local** (in-memory ring buffer, capped at [MAX_ENTRIES]).
 *   * **Observable** via [events] so UI / remote telemetry can tail it.
 *   * **Durable-capable**: callers may hook a [com.omnidev.workspace.data.repository.AnalyticsRepository]
 *     subscriber in the Application class to persist the flow to Room.
 *
 * This type is intentionally simple and has no database / coroutine-scope
 * dependencies so it is safe to import from any layer, including Lite.
 */
object OmniAuditLog {

    /** Maximum number of recent entries retained in memory. */
    const val MAX_ENTRIES: Int = 1_000

    private const val TAG = "OmniAuditLog"

    /**
     * A single audit record written when a privileged action is approved
     * (either automatically by OEM or explicitly by the user).
     */
    data class Entry(
        val id: Long,
        val timestampMs: Long,
        val tier: String,
        val autoApproved: Boolean,
        val kind: ConfirmationKind,
        val preview: String,
        val diffContent: String?
    )

    private val idSeq = AtomicLong(0)
    private val buffer = ConcurrentLinkedDeque<Entry>()

    private val _events = MutableSharedFlow<Entry>(
        replay = 0,
        extraBufferCapacity = 64
    )

    /** Observable flow of audit events. Subscribers receive only NEW entries. */
    val events: SharedFlow<Entry> = _events.asSharedFlow()

    /**
     * Write a new audit entry for the given action.
     *
     * @param tier         Tier name (typically `TierPolicy.tier`).
     * @param autoApproved `true` if approval was automatic (OEM zero-click),
     *                     `false` if the user explicitly approved the dialog.
     * @param kind         The kind of confirmation.
     * @param preview      Human-readable preview shown to the user (or that
     *                     WOULD have been shown in a non-auto tier).
     * @param diffContent  Unified-diff text, when the action was a file patch.
     */
    fun record(
        tier: String,
        autoApproved: Boolean,
        kind: ConfirmationKind,
        preview: String,
        diffContent: String? = null
    ): Entry {
        val entry = Entry(
            id = idSeq.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            tier = tier,
            autoApproved = autoApproved,
            kind = kind,
            preview = preview,
            diffContent = diffContent
        )

        buffer.addLast(entry)
        // Trim oldest entries once we exceed the cap.
        while (buffer.size > MAX_ENTRIES) {
            buffer.pollFirst()
        }

        // Best-effort emission — ignore buffer-overflow (SharedFlow drops oldest).
        _events.tryEmit(entry)

        Log.i(
            TAG,
            "[tier=$tier auto=$autoApproved kind=$kind] " +
                preview.take(200) + if (preview.length > 200) "…" else ""
        )

        return entry
    }

    /** Snapshot of all current entries (oldest first). */
    fun snapshot(): List<Entry> = buffer.toList()

    /** Clear the in-memory buffer. Primarily for tests. */
    internal fun clearForTest() {
        buffer.clear()
        idSeq.set(0)
    }
}
