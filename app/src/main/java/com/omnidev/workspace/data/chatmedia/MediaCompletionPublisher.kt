package com.omnidev.workspace.data.chatmedia

import android.content.Context
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.db.entities.ChatMessageEntity
import kotlinx.coroutines.CancellationException

internal object MediaCompletion {
    const val MESSAGE_PREFIX = "media-result:"
    fun text(job: MediaJob): String {
        require(job.state == "completed" && !job.path.isNullOrBlank()) { "Media is not ready." }
        val name = when(job.kind) { "image" -> "الصورة"; "video" -> "الفيديو"; else -> "الموسيقى" }
        val ready = if (job.arabic) "عملتلك $name أهو. تقدر تفتحها أو تشغّلها من الكارت وتحفظها على الموبايل."
            else "Your ${job.kind} is ready. Open or play the chat card and save it to your device."
        val saveError = if (job.galleryAttempted && job.galleryUri == null) {
            if (job.arabic) "\nالحفظ التلقائي ما اكتملش؛ تقدر تستخدم زر الحفظ في الكارت." else "\nAutomatic saving did not finish; use Save on the card."
        } else ""
        return ready + saveError + job.lyricsText?.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
    }
}

/** Delivery is idempotent, belongs to the originating chat, and never recreates a deleted chat. */
internal object MediaCompletionPublisher {
    suspend fun failure(context: Context, original: MediaJob) {
        val store = MediaJobStore(context)
        val job = store.get(original.id) ?: return
        if (job.state != "failed" || job.failureAnnounced || job.sessionId == null) return
        val text = if (job.arabic) "توليد الوسائط وقف بسبب خطأ. افتح تفاصيل الكارت للمراجعة.\n${job.error.orEmpty()}" else "${job.kind.replaceFirstChar { it.uppercase() }} generation failed.\n${job.error.orEmpty()}"
        OmniDevDatabase.getInstance(context).chatMessageDao().insertMediaResult(ChatMessageEntity(
            sessionId = job.sessionId, role = "ASSISTANT", content = text, messageId = MediaCompletion.MESSAGE_PREFIX + job.id + ":failed", replyToMessageId = job.originMessageId), job.originMessageId, job.created)
        store.compareAndUpdate(job, job.copy(failureAnnounced = true))
    }

    suspend fun deliver(context: Context, original: MediaJob) {
        val store = MediaJobStore(context)
        var job = store.get(original.id) ?: return
        if (job.state != "completed" || job.path == null || job.delivered) return
        if (job.config?.autoSaveToGallery == true && !job.galleryAttempted) {
            job = job.copy(galleryAttempted = true)
            if (!store.update(job)) return
            val saved = try {
                val meta = ChatMediaStore.metadata(context, job.path!!) ?: error("Generated file unavailable")
                ChatMediaStore.save(context, meta).toString()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            job = job.copy(galleryUri = saved)
            if (!store.update(job)) return
        }
        if (job.config?.announceCompletion != false && job.sessionId != null) {
            OmniDevDatabase.getInstance(context).chatMessageDao().insertMediaResult(ChatMessageEntity(
                sessionId = job.sessionId!!, role = "ASSISTANT", content = MediaCompletion.text(job),
                messageId = MediaCompletion.MESSAGE_PREFIX + job.id, replyToMessageId = job.originMessageId), job.originMessageId, job.created)
        }
        store.update(job.copy(delivered = true))
    }
}
