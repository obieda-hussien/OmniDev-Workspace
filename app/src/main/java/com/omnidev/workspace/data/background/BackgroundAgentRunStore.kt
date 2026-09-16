package com.omnidev.workspace.data.background

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Crash-safe ledger for user-initiated chat/agent runs that outlive an Activity/ViewModel.
 *
 * The ledger deliberately stores references to the canonical Room chat data instead of
 * duplicating prompts/history. A run can therefore be reconstructed after process death from
 * [sessionId] + [userMessageId] while keeping one source of truth for conversation content.
 *
 * SharedPreferences is used here instead of adding another Room entity/migration because the
 * payload is tiny, bounded, and replaced atomically. Chat messages/console output themselves
 * remain in Room and are checkpointed by the runtime.
 */
class BackgroundAgentRunStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    enum class Status { QUEUED, RUNNING, RECOVERING, COMPLETED, FAILED, CANCELLED }

    @Serializable
    data class Run(
        val id: String = UUID.randomUUID().toString(),
        val sessionId: Long,
        val userMessageId: String,
        val mode: String,
        val scopePath: String = "",
        val createdAtMs: Long = System.currentTimeMillis(),
        val startedAtMs: Long? = null,
        val updatedAtMs: Long = System.currentTimeMillis(),
        val completedAtMs: Long? = null,
        val status: Status = Status.QUEUED,
        val recoveryAttempt: Int = 0,
        val assistantRowId: Long = -1L,
        val assistantMessageId: String? = null,
        val progressText: String = "Queued",
        val partialOutput: String = "",
        val lastError: String? = null,
        /** Snapshot of chat-level tool gating for deterministic recovery. */
        val disabledToolNames: Set<String> = emptySet(),
        val toolAccessMode: String = "ON_DEMAND"
    ) {
        val terminal: Boolean get() = status in setOf(Status.COMPLETED, Status.FAILED, Status.CANCELLED)
    }

    @Synchronized
    fun create(
        sessionId: Long,
        userMessageId: String,
        mode: String,
        scopePath: String,
        disabledToolNames: Set<String>,
        toolAccessMode: String
    ): Run {
        val existing = all().firstOrNull {
            !it.terminal && it.sessionId == sessionId && it.userMessageId == userMessageId
        }
        if (existing != null) return existing
        val run = Run(
            sessionId = sessionId,
            userMessageId = userMessageId,
            mode = mode,
            scopePath = scopePath,
            disabledToolNames = disabledToolNames,
            toolAccessMode = toolAccessMode
        )
        write(all() + run)
        return run
    }

    @Synchronized
    fun get(id: String): Run? = all().firstOrNull { it.id == id }

    @Synchronized
    fun all(): List<Run> = decode(prefs.getString(KEY_RUNS, null))

    @Synchronized
    fun active(): List<Run> = all().filterNot { it.terminal }

    @Synchronized
    fun recoverable(): List<Run> = all().filter {
        it.status == Status.QUEUED || it.status == Status.RUNNING || it.status == Status.RECOVERING
    }

    @Synchronized
    fun update(id: String, transform: (Run) -> Run): Run? {
        val current = all().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return null
        val next = transform(current[index]).copy(updatedAtMs = System.currentTimeMillis())
        current[index] = next
        write(current)
        return next
    }

    @Synchronized
    fun markRecovering(id: String): Run? = update(id) {
        it.copy(
            status = Status.RECOVERING,
            recoveryAttempt = it.recoveryAttempt + 1,
            progressText = "Recovering from saved checkpoint…"
        )
    }

    @Synchronized
    fun pruneTerminal(olderThanMs: Long = DEFAULT_TERMINAL_RETENTION_MS) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        write(all().filter { run -> !run.terminal || (run.completedAtMs ?: run.updatedAtMs) >= cutoff })
    }

    private fun decode(raw: String?): List<Run> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(Run.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    private fun write(runs: List<Run>) {
        // Bound terminal history so a corrupted/abandoned device can never grow this file forever.
        val active = runs.filterNot { it.terminal }
        val terminal = runs.filter { it.terminal }.sortedByDescending { it.updatedAtMs }.take(MAX_TERMINAL_RUNS)
        val encoded = json.encodeToString(ListSerializer(Run.serializer()), active + terminal)
        check(prefs.edit().putString(KEY_RUNS, encoded).commit()) { "Could not persist background run ledger" }
    }

    companion object {
        private const val PREFS_NAME = "omnidev_background_agent_runs_v1"
        private const val KEY_RUNS = "runs"
        private const val MAX_TERMINAL_RUNS = 64
        private const val DEFAULT_TERMINAL_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
