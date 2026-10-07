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
    @Test fun completionCannotBeAnnouncedBeforeFileIsReady() {
        val queued = MediaJob("id", "music", "gemini", "lyria-3-clip-preview", "song", "", arabic = true)
        assertTrue(runCatching { MediaCompletion.text(queued) }.isFailure)
        assertTrue(runCatching { MediaCompletion.text(queued.copy(state = "completed")) }.isFailure)
        assertTrue(MediaCompletion.text(queued.copy(state = "completed", path = "/song.mp3")).contains("عملتلك"))
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
