package com.omnidev.workspace.ui.settings

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omnidev.workspace.data.chatmedia.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class ProfileReferencesTest {
    private val names = listOf("face-12345678-1234-1234-1234-123456789abc.jpg", "body-12345678-1234-1234-1234-123456789abc.jpg")
    private val job = MediaJob("profile-fixture", "image", "gemini", "gemini-2.5-flash-image", "Portrait of me", "1:1", profileReferences = names)
    private suspend fun fixture(block: suspend (Context) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory("profile-reference-test").toFile()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
        }
        try { block(context) } finally { directory.deleteRecursively() }
    }
    private suspend fun photo(context: Context, block: suspend (Uri) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("profile-input-", ".png", File(base.cacheDir, "chat-media").apply { mkdirs() })
        try {
            val bitmap = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
            block(FileProvider.getUriForFile(base, "${base.packageName}.chatmedia", file))
        } finally { file.delete() }
    }
    @Test fun importsSurviveRecreationAndRemovalOrRevocationBlocksQueuedUse() = runBlocking {
        fixture { context -> photo(context) { uri ->
            val store = ProfileReferenceStore(context)
            store.import(ProfilePhotoSlot.FACE, uri)
            store.import(ProfilePhotoSlot.BODY, uri)
            assertFalse(store.get().allowed)
            val selected = store.get().files()
            assertEquals(2, selected.size)
            store.allow(true)
            val recreated = ProfileReferenceStore(context)
            assertEquals(store.get(), recreated.get())
            val bytes = recreated.read(selected)
            assertEquals(2, bytes.size)
            assertTrue(bytes.all { it.size > 0 && it[0] == 0xff.toByte() && it[1] == 0xd8.toByte() })
            store.allow(false)
            assertTrue(runCatching { recreated.read(selected) }.isFailure)
            store.allow(true)
            store.remove(ProfilePhotoSlot.FACE)
            assertTrue(runCatching { recreated.read(selected) }.isFailure)
            store.remove(ProfilePhotoSlot.BODY)
            assertFalse(recreated.get().allowed)
            assertTrue(recreated.get().files().isEmpty())
        } }
    }
    @Test fun replacingAReferenceInvalidatesOldJobsAndFailedImportsPreserveThePhoto() = runBlocking {
        fixture { context -> photo(context) { uri ->
            val store = ProfileReferenceStore(context)
            store.import(ProfilePhotoSlot.FACE, uri); store.allow(true)
            val old = store.get().files()
            assertTrue(runCatching { store.import(ProfilePhotoSlot.FACE, Uri.parse("file:///missing.jpg")) }.isFailure)
            assertEquals(old, store.get().files())
            store.import(ProfilePhotoSlot.FACE, uri)
            assertNotEquals(old, store.get().files())
            assertTrue(runCatching { store.read(old) }.isFailure)
            assertEquals(1, store.read(store.get().files()).size)
        } }
    }
    @Test fun geminiRequestContainsBothReferencesWithoutPathsAndPlainGenerationReadsNone() = runBlocking {
        fixture { context ->
            var reads = 0
            val client = MediaGenerationClient(context, readKey = { "fixture" }, execute = { request ->
                val buffer = Buffer(); request.body!!.writeTo(buffer)
                val text = buffer.readUtf8()
                val parts = JSONObject(text).getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
                assertEquals(if (reads == 0) 1 else 3, parts.length())
                if (reads > 0) for (i in 1..2) assertEquals("image/jpeg", parts.getJSONObject(i).getJSONObject("inlineData").getString("mimeType"))
                assertFalse(text.contains(names[0])); assertFalse(text.contains(context.noBackupFilesDir.path))
                response(request, """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"AQID"}}]}}]}""")
            }, readReferences = { selected -> reads++; assertEquals(names, selected); listOf(byteArrayOf(1), byteArrayOf(2)) })
            client.step(job.copy(profileReferences = emptyList()))
            assertEquals(0, reads)
            client.step(job)
            assertEquals(1, reads)
        }
    }
    @Test fun openAiUsesMultipartEditsWithTwoImagesAndHighFidelity() = runBlocking {
        fixture { context ->
            val client = MediaGenerationClient(context, readKey = { "fixture" }, execute = { request ->
                assertEquals("/v1/images/edits", request.url.encodedPath)
                val body = request.body as MultipartBody
                assertEquals(2, body.parts.count { it.headers?.get("Content-Disposition")?.contains("name=\"image[]\"") == true })
                val buffer = Buffer(); body.writeTo(buffer); val text = buffer.readUtf8()
                assertTrue(text.contains("name=\"input_fidelity\"\r\n")); assertTrue(text.contains("\r\n\r\nhigh\r\n"))
                assertFalse(text.contains(names[0]))
                response(request, """{"data":[{"b64_json":"AQID"}]}""")
            }, readReferences = { listOf(byteArrayOf(1), byteArrayOf(2)) })
            assertEquals("completed", client.step(job.copy(provider = "openai", model = "gpt-image-1.5")).state)
        }
    }
    @Test fun referenceReadFailureCannotSendAPhantomUnreferencedRequest() = runBlocking {
        fixture { context ->
            var requests = 0
            val client = MediaGenerationClient(context, readKey = { "fixture" }, execute = { requests++; error("Must not submit") },
                readReferences = { error("Reference was removed") })
            assertTrue(runCatching { client.step(job) }.isFailure)
            assertEquals(0, requests)
        }
    }
    private fun response(request: Request, json: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(200).message("fixture").body(json.toResponseBody("application/json".toMediaType())).build()
}
