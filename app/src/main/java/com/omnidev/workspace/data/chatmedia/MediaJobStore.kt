package com.omnidev.workspace.data.chatmedia

import android.content.Context
import org.json.JSONObject
import java.util.UUID

internal data class MediaJob(val id: String, val kind: String, val provider: String, val model: String,
    val prompt: String, val aspect: String, val state: String = "queued", val operation: String? = null,
    val path: String? = null, val error: String? = null, val created: Long = System.currentTimeMillis())

/** Only non-credential job state. Completion cannot overwrite cancellation. */
internal class MediaJobStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("chat-media-jobs", Context.MODE_PRIVATE)
    fun create(kind: String, provider: String, model: String, prompt: String, aspect: String): MediaJob = synchronized(gate) {
        val all = prefs.all.keys.mapNotNull(::get)
        require(all.count { it.state in setOf("queued", "processing") } < 50) {
            "Too many active media jobs. Cancel an older job first."
        }
        MediaJob(UUID.randomUUID().toString(), kind, provider, model, prompt, aspect).also { check(save(it)) }
    }
    fun get(id: String): MediaJob? = synchronized(gate) {
        val value = prefs.getString(id, null) ?: return@synchronized null
        runCatching {
            val j = JSONObject(value)
            fun optional(key: String) = j.optString(key).takeUnless { it.isBlank() || it == "null" }
            MediaJob(id, j.getString("kind"), j.getString("provider"), j.getString("model"), j.optString("prompt"),
                j.optString("aspect", "16:9"), j.getString("state"), optional("operation"), optional("path"), optional("error"), j.getLong("created"))
        }.getOrNull()
    }
    fun update(job: MediaJob): Boolean = synchronized(gate) {
        val current = get(job.id) ?: return@synchronized false
        if (current.state == "cancelled" && job.state != "cancelled") return@synchronized false
        save(job)
    }
    private fun save(job: MediaJob) = prefs.edit().putString(job.id, JSONObject().apply {
        put("kind", job.kind); put("provider", job.provider); put("model", job.model); put("prompt", job.prompt)
        put("aspect", job.aspect); put("state", job.state); put("operation", job.operation); put("path", job.path)
        put("error", job.error); put("created", job.created)
    }.toString()).commit()
    companion object { private val gate = Any() }
}
