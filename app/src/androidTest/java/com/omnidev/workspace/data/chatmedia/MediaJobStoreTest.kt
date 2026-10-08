package com.omnidev.workspace.data.chatmedia

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaJobStoreTest {
    @Test fun manualStartLaunchesDirectExecutionBeforeAndAfterQueueExpiry() = kotlinx.coroutines.runBlocking {
        for (expired in listOf(false, true)) {
            val context = preferencesContext(); val store = MediaJobStore(context)
            val queued = store.create("music", "gemini", "lyria-3-clip-preview", "song", "")
            if (expired) store.compareAndUpdate(queued,
                MediaCardStatus.reconcile(queued, "ENQUEUED", true, queued.created + 3_600_000))
            var starts = 0
            MediaGenerationService.startNow(context, queued.id) { _, id -> starts++; assertEquals(queued.id, id) }
            assertEquals(1, starts)
            val current = store.get(queued.id)!!
            assertEquals("queued", current.state); assertEquals("starting", current.phase)
            assertEquals("song", current.prompt); assertNull(current.errorCode)
        }
    }

    @Test fun directStartFailureIsVisibleAndRetainsTheUnsubmittedRequest() = kotlinx.coroutines.runBlocking {
        val context = preferencesContext(); val store = MediaJobStore(context)
        val queued = store.create("image", "gemini", "image", "prompt", "1:1")
        MediaGenerationService.startNow(context, queued.id) { _, _ -> error("Service rejected") }
        val failed = store.get(queued.id)!!
        assertEquals("START_FAILED", failed.errorCode); assertEquals("prompt", failed.prompt)
        assertTrue(MediaQueuePolicy.canStart(failed))
    }

    @Test fun manualStartCannotReviveCancellationOrReplayAnAlreadyClaimedRequest() = kotlinx.coroutines.runBlocking {
        for (state in listOf("cancelled", "processing")) {
            val context = preferencesContext(); val store = MediaJobStore(context)
            val queued = store.create("video", "gemini", "veo", "prompt", "16:9")
            store.update(queued.copy(state = state))
            MediaGenerationService.startNow(context, queued.id) { _, _ -> fail("Submitted an ineligible request") }
            assertEquals(state, store.get(queued.id)!!.state)
        }
    }

    @Test fun staleQueueMonitorCannotFailARequestClaimedByTheWorker() {
        val context = preferencesContext(); val store = MediaJobStore(context)
        val queued = store.create("music", "gemini", "lyria-3-clip-preview", "song", "")
        val claimed = queued.copy(state = "processing", phase = "requesting")
        assertTrue(store.compareAndUpdate(queued, claimed))
        val expired = MediaCardStatus.reconcile(queued, "ENQUEUED", true, queued.created + 3_600_000)
        assertFalse(store.compareAndUpdate(queued, expired))
        assertEquals("processing", MediaJobStore(context).get(queued.id)!!.state)
        assertFalse(store.compareAndUpdate(queued, claimed))
    }

    @Test fun restartedQueueDeadlineSurvivesReopeningTheApp() {
        val context = preferencesContext(); val store = MediaJobStore(context)
        val queued = store.create("music", "gemini", "lyria-3-clip-preview", "song", "")
        val failed = MediaCardStatus.reconcile(queued, "ENQUEUED", true, queued.created + 3_600_000)
        assertTrue(store.compareAndUpdate(queued, failed))
        val restarted = MediaQueuePolicy.restart(failed, queued.created + 3_600_100)
        assertTrue(store.compareAndUpdate(failed, restarted))
        assertEquals(restarted, MediaJobStore(context).get(queued.id))
    }

    @Test fun recreatedStoreRetainsOperationAndCompletedFileForOldConversations() {
        val context = preferencesContext()
        val first = MediaJobStore(context)
        val job = first.create("video", "gemini", "veo", "description", "16:9")
        first.update(job.copy(state = "processing", operation = "operations/existing", prompt = ""))
        val recreated = MediaJobStore(context)
        assertEquals("operations/existing", recreated.get(job.id)?.operation)
        recreated.update(recreated.get(job.id)!!.copy(state = "completed", path = "/files/chat-media/movie.mp4"))
        repeat(55) {
            val other = first.create("image", "gemini", "image", "description", "1:1")
            first.update(other.copy(state = "completed", prompt = ""))
        }
        assertEquals("/files/chat-media/movie.mp4", MediaJobStore(context).get(job.id)?.path)
    }

    @Test fun lateWorkerResultCannotUndoCancellation() {
        val store = MediaJobStore(preferencesContext())
        val job = store.create("video", "gemini", "veo", "description", "16:9")
        assertTrue(store.update(job.copy(state = "cancelled", prompt = "")))
        assertFalse(store.update(job.copy(state = "completed", path = "/file.mp4")))
        assertEquals("cancelled", store.get(job.id)?.state)
        assertEquals("", store.get(job.id)?.prompt)
    }

    @Test fun activeJobLimitDoesNotCountCompletedJobs() {
        val store = MediaJobStore(preferencesContext())
        val jobs = (1..50).map { store.create("image", "gemini", "image", "prompt", "1:1") }
        assertTrue(runCatching { store.create("image", "gemini", "image", "prompt", "1:1") }.isFailure)
        store.update(jobs.first().copy(state = "completed", prompt = ""))
        assertNotNull(store.create("image", "gemini", "image", "prompt", "1:1"))
    }

    @Test fun musicJobRetainsSettingsOriginLyricsAndDeliveryAcrossRestart() {
        val context = preferencesContext(); val store = MediaJobStore(context)
        val config = MediaConfig.defaults(MediaKind.MUSIC).copy(enabled = true, instrumental = true, autoSaveToGallery = true)
        val job = store.create("music", "gemini", config.model, "song", "", config, 42, true)
        store.update(job.copy(state = "completed", path = "/song.mp3", lyricsText = "Lyrics", delivered = true, galleryAttempted = true, galleryUri = "content://media/42"))
        val restored = MediaJobStore(context).get(job.id)!!
        assertEquals(config, restored.config); assertEquals(42L, restored.sessionId); assertTrue(restored.arabic)
        assertTrue(restored.delivered); assertTrue(restored.galleryAttempted); assertEquals("Lyrics", restored.lyricsText)
        assertEquals("content://media/42", restored.galleryUri)
    }
    @Test fun waitingFailureDiagnosticsSurviveRestartAndRemainAnActiveJob() {
        val context = preferencesContext(); val store = MediaJobStore(context)
        val job = store.create("video", "gemini", "veo", "prompt", "16:9")
        store.update(job.copy(state = "waiting", phase = "interrupted", operation = "operations/42",
            error = "Connection interrupted", errorCode = "NETWORK", failures = 3, failureAnnounced = true))
        val restored = MediaJobStore(context).get(job.id)!!
        assertEquals("waiting", restored.state); assertEquals("interrupted", restored.phase)
        assertEquals("NETWORK", restored.errorCode); assertEquals(3, restored.failures)
        assertTrue(restored.failureAnnounced); assertTrue(MediaGenerationFailure.canResume(restored.copy(state = "failed")))
        assertEquals(1, MediaJobStore(context).active().size)
    }
    @Test fun completionCannotBeAnnouncedBeforeFileIsReady() {
        val queued = MediaJob("id", "music", "gemini", "lyria-3-clip-preview", "song", "", arabic = true)
        assertTrue(runCatching { MediaCompletion.text(queued) }.isFailure)
        assertTrue(runCatching { MediaCompletion.text(queued.copy(state = "completed")) }.isFailure)
        assertTrue(MediaCompletion.text(queued.copy(state = "completed", path = "/song.mp3")).contains("عملتلك"))
    }

    @Test fun replacedTurnDeliveryIsDetachedAcrossRestartAndStaleWorkerUpdates() {
        val context = preferencesContext(); val store = MediaJobStore(context)
        val old = store.create("image", "gemini", "image", "prompt", "1:1", sessionId = 42, originMessageId = "old")
        val other = store.create("image", "gemini", "image", "prompt", "1:1", sessionId = 42, originMessageId = "earlier")
        assertEquals(listOf(old.id), store.detachTurn(42, "old", old.created))
        val detached = MediaJobStore(context).get(old.id)!!
        assertNull(detached.sessionId); assertTrue(detached.delivered); assertTrue(detached.failureAnnounced)
        assertEquals("old", detached.originMessageId); assertEquals(42L, store.get(other.id)!!.sessionId)
        assertEquals("cancelled", detached.state)
        assertFalse(store.update(old.copy(state = "completed", path = "/ready.png")))
        val completed = MediaJobStore(context).get(old.id)!!
        assertNull(completed.sessionId); assertTrue(completed.delivered); assertEquals("cancelled", completed.state)
    }

    @Test fun alreadyGeneratedFilesAreKeptButStaleDeliveryCannotReattachTheReplacedTurn() {
        val context = preferencesContext(); val store = MediaJobStore(context)
        val old = store.create("image", "gemini", "image", "prompt", "1:1", sessionId = 42, originMessageId = "old")
        val ready = old.copy(state = "completed", path = "/ready.png")
        store.update(ready)
        assertTrue(store.detachTurn(42, "old", old.created).isEmpty())
        assertTrue(store.update(ready.copy(delivered = true)))
        val restored = store.get(old.id)!!
        assertNull(restored.sessionId); assertEquals("/ready.png", restored.path); assertEquals("completed", restored.state)
        assertTrue(restored.delivered); assertTrue(restored.failureAnnounced)
    }

    // In-memory Android interface fixture: exercises actual serialized store state without a device.
    private fun preferencesContext(): Context {
        val values = mutableMapOf<String, String>()
        fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, String>()
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString" -> { changes[args!![0] as String] = args[1] as String; proxy }
                    "commit" -> { values.putAll(changes); true }
                    "apply" -> { values.putAll(changes); null }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
        val preferences = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "getString" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences
        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
    }
}
