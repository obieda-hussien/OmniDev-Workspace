package com.omnidev.workspace.data.background

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

/**
 * Small durable registry for in-flight in-app chat runs.
 *
 * The actual prompt and model output remain in Room; this store intentionally keeps only
 * restart metadata needed to recover a run after the process is killed. That avoids duplicating
 * potentially sensitive chat content in SharedPreferences.
 */
data class BackgroundChatRun(
    val token: String,
    val sessionId: Long,
    val userMessageId: String,
    val userTimestamp: Long,
    val mode: String,
    val scopePath: String,
    val state: String = STATE_RUNNING,
    val ownerUi: Boolean = true,
    val startedAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastStatus: String = "Starting…",
    val lastError: String? = null,
    val notificationId: Int = notificationIdFor(token)
) {
    val isTerminal: Boolean
        get() = state == STATE_COMPLETED || state == STATE_FAILED || state == STATE_CANCELLED

    companion object {
        const val STATE_RUNNING = "RUNNING"
        const val STATE_RECOVERING = "RECOVERING"
        const val STATE_WAITING_NETWORK = "WAITING_NETWORK"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_FAILED = "FAILED"
        const val STATE_CANCELLED = "CANCELLED"

        fun notificationIdFor(token: String): Int = 6_200 + abs(token.hashCode() % 1_700)
    }
}

object BackgroundChatTaskStore {
    private const val PREFS = "background_chat_runs_v1"
    private const val KEY_RUNS = "runs"
    private const val MAX_TERMINAL_HISTORY = 24
    private const val TERMINAL_RETENTION_MS = 24L * 60L * 60L * 1_000L
    private val lock = Any()

    fun active(context: Context): List<BackgroundChatRun> = synchronized(lock) {
        loadLocked(context).filterNot { it.isTerminal }.sortedBy { it.startedAtMs }
    }

    fun all(context: Context): List<BackgroundChatRun> = synchronized(lock) {
        loadLocked(context)
    }

    fun get(context: Context, token: String): BackgroundChatRun? = synchronized(lock) {
        loadLocked(context).firstOrNull { it.token == token }
    }

    fun latestActiveForSession(context: Context, sessionId: Long): BackgroundChatRun? = synchronized(lock) {
        loadLocked(context)
            .filter { !it.isTerminal && it.sessionId == sessionId }
            .maxByOrNull { it.startedAtMs }
    }

    fun arm(
        context: Context,
        sessionId: Long,
        userMessageId: String,
        userTimestamp: Long,
        mode: String,
        scopePath: String,
        ownerUi: Boolean = true
    ): BackgroundChatRun = synchronized(lock) {
        val runs = loadLocked(context).toMutableList()
        runs.firstOrNull {
            !it.isTerminal && it.sessionId == sessionId && it.userMessageId == userMessageId
        }?.let { existing ->
            val updated = existing.copy(
                mode = mode,
                scopePath = scopePath,
                ownerUi = ownerUi,
                updatedAtMs = System.currentTimeMillis(),
                lastStatus = if (existing.lastStatus.isBlank()) "Starting…" else existing.lastStatus
            )
            replaceLocked(context, runs, updated)
            return@synchronized updated
        }

        val token = "$sessionId:$userMessageId:${UUID.randomUUID()}"
        val run = BackgroundChatRun(
            token = token,
            sessionId = sessionId,
            userMessageId = userMessageId,
            userTimestamp = userTimestamp,
            mode = mode,
            scopePath = scopePath,
            ownerUi = ownerUi,
            notificationId = BackgroundChatRun.notificationIdFor(token)
        )
        runs += run
        persistLocked(context, runs)
        run
    }

    fun update(context: Context, run: BackgroundChatRun): BackgroundChatRun = synchronized(lock) {
        val runs = loadLocked(context).toMutableList()
        replaceLocked(context, runs, run)
        run
    }

    fun mutate(
        context: Context,
        token: String,
        transform: (BackgroundChatRun) -> BackgroundChatRun
    ): BackgroundChatRun? = synchronized(lock) {
        val runs = loadLocked(context).toMutableList()
        val current = runs.firstOrNull { it.token == token } ?: return@synchronized null
        val updated = transform(current).copy(updatedAtMs = System.currentTimeMillis())
        replaceLocked(context, runs, updated)
        updated
    }

    fun markTerminal(
        context: Context,
        token: String,
        state: String,
        status: String,
        error: String? = null
    ): BackgroundChatRun? = mutate(context, token) {
        it.copy(
            state = state,
            ownerUi = false,
            lastStatus = status.take(220),
            lastError = error?.take(500)
        )
    }

    fun remove(context: Context, token: String) = synchronized(lock) {
        persistLocked(context, loadLocked(context).filterNot { it.token == token })
    }

    fun prune(context: Context) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val all = loadLocked(context)
        val active = all.filterNot { it.isTerminal }
        val terminal = all.filter { it.isTerminal && now - it.updatedAtMs <= TERMINAL_RETENTION_MS }
            .sortedByDescending { it.updatedAtMs }
            .take(MAX_TERMINAL_HISTORY)
        persistLocked(context, active + terminal)
    }

    private fun replaceLocked(
        context: Context,
        runs: MutableList<BackgroundChatRun>,
        updated: BackgroundChatRun
    ) {
        val index = runs.indexOfFirst { it.token == updated.token }
        if (index >= 0) runs[index] = updated else runs += updated
        persistLocked(context, runs)
    }

    private fun loadLocked(context: Context): List<BackgroundChatRun> {
        val raw = prefs(context).getString(KEY_RUNS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    decode(obj)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistLocked(context: Context, runs: List<BackgroundChatRun>) {
        val arr = JSONArray()
        runs.forEach { arr.put(encode(it)) }
        // commit() is deliberate: arming/terminal transitions must survive an immediate process kill.
        prefs(context).edit().putString(KEY_RUNS, arr.toString()).commit()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun encode(run: BackgroundChatRun): JSONObject = JSONObject().apply {
        put("token", run.token)
        put("sessionId", run.sessionId)
        put("userMessageId", run.userMessageId)
        put("userTimestamp", run.userTimestamp)
        put("mode", run.mode)
        put("scopePath", run.scopePath)
        put("state", run.state)
        put("ownerUi", run.ownerUi)
        put("startedAtMs", run.startedAtMs)
        put("updatedAtMs", run.updatedAtMs)
        put("attempts", run.attempts)
        put("lastStatus", run.lastStatus)
        put("lastError", run.lastError ?: JSONObject.NULL)
        put("notificationId", run.notificationId)
    }

    private fun decode(obj: JSONObject): BackgroundChatRun? {
        val token = obj.optString("token")
        val sessionId = obj.optLong("sessionId", -1L)
        val userMessageId = obj.optString("userMessageId")
        if (token.isBlank() || sessionId <= 0L || userMessageId.isBlank()) return null
        return BackgroundChatRun(
            token = token,
            sessionId = sessionId,
            userMessageId = userMessageId,
            userTimestamp = obj.optLong("userTimestamp", 0L),
            mode = obj.optString("mode", "AGENT"),
            scopePath = obj.optString("scopePath", ""),
            state = obj.optString("state", BackgroundChatRun.STATE_RUNNING),
            ownerUi = obj.optBoolean("ownerUi", false),
            startedAtMs = obj.optLong("startedAtMs", System.currentTimeMillis()),
            updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis()),
            attempts = obj.optInt("attempts", 0),
            lastStatus = obj.optString("lastStatus", "Working…"),
            lastError = obj.optString("lastError").takeIf { it.isNotBlank() && it != "null" },
            notificationId = obj.optInt("notificationId", BackgroundChatRun.notificationIdFor(token))
        )
    }
}
