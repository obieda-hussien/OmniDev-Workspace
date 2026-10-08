package com.omnidev.workspace.data.chatmedia

import android.content.Context
import org.json.JSONObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID

internal data class MediaJob(val id: String, val kind: String, val provider: String, val model: String,
    val prompt: String, val aspect: String, val state: String = "queued", val operation: String? = null,
    val path: String? = null, val error: String? = null, val created: Long = System.currentTimeMillis(),
    val config: MediaConfig? = null, val sessionId: Long? = null, val arabic: Boolean = false,
    val delivered: Boolean = false, val galleryAttempted: Boolean = false, val galleryUri: String? = null,
    val lyricsText: String? = null, val phase: String = "queued", val errorCode: String? = null,
    val failures: Int = 0, val failureAnnounced: Boolean = false, val originMessageId: String? = null,
    val queuedAt: Long = created)

/** Only non-credential job state. Completion cannot overwrite cancellation. */
internal class MediaJobStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("chat-media-jobs", Context.MODE_PRIVATE)
    fun active(): List<MediaJob> = synchronized(gate) { prefs.all.keys.mapNotNull(::get).filter { it.state in setOf("queued", "processing", "waiting") } }
    fun create(kind: String, provider: String, model: String, prompt: String, aspect: String,
        config: MediaConfig? = null, sessionId: Long? = null, arabic: Boolean = false, originMessageId: String? = null): MediaJob = synchronized(gate) {
        val all = prefs.all.keys.mapNotNull(::get)
        require(all.count { it.state in setOf("queued", "processing", "waiting") } < 50) {
            "Too many active media jobs. Cancel an older job first."
        }
        MediaJob(UUID.randomUUID().toString(), kind, provider, model, prompt, aspect,
            config = config, sessionId = sessionId?.takeIf { it > 0 }, arabic = arabic, originMessageId = originMessageId).also { check(save(it)) }
    }
    fun get(id: String): MediaJob? = synchronized(gate) {
        val value = prefs.getString(id, null) ?: return@synchronized null
        runCatching {
            val j = JSONObject(value)
            fun optional(key: String) = j.optString(key).takeUnless { it.isBlank() || it == "null" }
            MediaJob(id, j.getString("kind"), j.getString("provider"), j.getString("model"), j.optString("prompt"),
                j.optString("aspect", "16:9"), j.getString("state"), optional("operation"), optional("path"), optional("error"), j.getLong("created"),
                j.optJSONObject("config")?.let { MediaPreferencesCodec.config(MediaKind.fromAction(j.getString("kind")) ?: error("Unknown media kind"),
                    kotlinx.serialization.json.Json.parseToJsonElement(it.toString()).jsonObject) },
                j.optLong("session").takeIf { it > 0 }, j.optBoolean("arabic"), j.optBoolean("delivered"), j.optBoolean("gallery_attempted"), optional("gallery_uri"), optional("lyrics_text"), j.optString("phase", j.getString("state")), optional("error_code"), j.optInt("failures"), j.optBoolean("failure_announced"), optional("origin_message_id"), j.optLong("queued_at", j.getLong("created")))
        }.getOrNull()
    }
    fun update(job: MediaJob): Boolean = synchronized(gate) {
        val current = get(job.id) ?: return@synchronized false
        if (current.state == "cancelled" && job.state != "cancelled") return@synchronized false
        // Once detached, stale worker snapshots cannot reattach a superseded conversation turn.
        save(if (current.sessionId == null && current.delivered && current.failureAnnounced)
            job.copy(sessionId = null, delivered = true, failureAnnounced = true) else job)
    }
    /** A monitor or worker may only advance the snapshot it actually observed. */
    fun compareAndUpdate(expected: MediaJob, updated: MediaJob): Boolean = synchronized(gate) {
        require(expected.id == updated.id)
        if (get(expected.id) != expected) return@synchronized false
        update(updated)
    }
    /** Retire delivery and stop local work for unfinished jobs. Completed files remain accessible. */
    fun detachTurn(sessionId: Long, originMessageId: String, since: Long): List<String> = synchronized(gate) {
        val retired = prefs.all.keys.mapNotNull(::get).filter {
            it.sessionId == sessionId && (it.originMessageId == originMessageId ||
                (it.originMessageId == null && it.created >= since))
        }
        val active = retired.filter { it.state in setOf("queued", "processing", "waiting") }.map { it.id }.toSet()
        retired.forEach {
            val detached = it.copy(sessionId = null, delivered = true, failureAnnounced = true)
            check(save(if (it.id in active) detached.copy(state = "cancelled", phase = "cancelled", prompt = "",
                error = "Superseded by a newer conversation revision.") else detached))
        }
        active.toList()
    }

    private fun save(job: MediaJob) = prefs.edit().putString(job.id, JSONObject().apply {
        put("kind", job.kind); put("provider", job.provider); put("model", job.model); put("prompt", job.prompt)
        put("aspect", job.aspect); put("state", job.state); put("operation", job.operation); put("path", job.path)
        put("error", job.error); put("created", job.created); put("queued_at", job.queuedAt)
        put("config", job.config?.let { JSONObject(MediaPreferencesCodec.configJson(it).toString()) })
        put("session", job.sessionId); put("arabic", job.arabic); put("delivered", job.delivered)
        put("gallery_attempted", job.galleryAttempted); put("gallery_uri", job.galleryUri); put("lyrics_text", job.lyricsText)
        put("phase", job.phase); put("error_code", job.errorCode); put("failures", job.failures); put("failure_announced", job.failureAnnounced); put("origin_message_id", job.originMessageId)
    }.toString()).commit()
    companion object { private val gate = Any() }
}
