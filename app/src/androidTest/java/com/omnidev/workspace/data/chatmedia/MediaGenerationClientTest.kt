package com.omnidev.workspace.data.chatmedia

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class MediaGenerationClientTest {
    private fun response(request: Request, value: String, status: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
        .body(value.toResponseBody("application/json".toMediaType())).build()
    private val job = MediaJob("fixture", "video", "gemini", "veo-3.1-fast-generate-preview", "Purple sky", "16:9")
    private suspend fun <T> inDirectory(block: suspend (Context, File) -> T): T {
        val dir = Files.createTempDirectory("omni-media-fixture").toFile()
        val context = object : ContextWrapper(null) { override fun getFilesDir() = dir }
        try { return block(context, dir) } finally { dir.deleteRecursively() }
    }
    @Test fun startsOneVeoOperationAndPersistsItsHandleWithoutClaimingCompletion() = runBlocking {
        inDirectory { context, _ ->
            var requests = 0
            val client = MediaGenerationClient(context, readKey = { "fixture-key" }, execute = {
                requests++
                assertEquals("POST", it.method)
                assertTrue(it.url.encodedPath.endsWith(":predictLongRunning"))
                assertEquals("fixture-key", it.header("x-goog-api-key"))
                assertNull(it.header("Authorization"))
                val buffer = Buffer(); it.body!!.writeTo(buffer)
                assertEquals("Purple sky", JSONObject(buffer.readUtf8()).getJSONArray("instances").getJSONObject(0).getString("prompt"))
                response(it, "{\"name\":\"models/veo-3.1-fast-generate-preview/operations/abc\",\"done\":false}")
            })
            val result = client.step(job)
            assertEquals(1, requests); assertEquals("processing", result.state)
            assertNull(result.path); assertEquals("", result.prompt)
            assertEquals("models/veo-3.1-fast-generate-preview/operations/abc", result.operation)
        }
    }
    @Test fun pollingAnExistingJobNeverResubmitsItsPrompt() = runBlocking {
        inDirectory { context, _ ->
            val client = MediaGenerationClient(context, readKey = { "fixture-key" }, execute = {
                assertEquals("GET", it.method); assertNull(it.body)
                assertTrue(it.url.encodedPath.endsWith("operations/abc"))
                response(it, "{\"done\":false}")
            })
            val result = client.step(job.copy(state = "processing", operation = "operations/abc", prompt = ""))
            assertEquals("processing", result.state); assertNull(result.path)
        }
    }
    @Test fun handlesProviderRefusalWithoutInventingAVideo() = runBlocking {
        inDirectory { context, _ ->
            val client = MediaGenerationClient(context, readKey = { "fixture-key" }, execute = {
                response(it, "{\"done\":true,\"error\":{\"code\":400,\"message\":\"refused\"}}")
            })
            val result = client.step(job.copy(operation = "operations/abc"))
            assertEquals("failed", result.state); assertNull(result.path)
        }
    }
    @Test fun rejectsUnexpectedAuthenticatedVideoDownloadHosts() = runBlocking {
        inDirectory { context, _ ->
            var downloaded = false
            val client = MediaGenerationClient(context, readKey = { "fixture-key" }, execute = {
                response(it, "{\"done\":true,\"response\":{\"generateVideoResponse\":{\"generatedSamples\":[{\"video\":{\"uri\":\"https://evil.example/video.mp4\"}}]}}}")
            }, download = { _, _ -> downloaded = true; error("Forbidden") })
            assertTrue(runCatching { client.step(job.copy(operation = "operations/abc")) }.isFailure)
            assertFalse(downloaded)
        }
    }
    @Test fun storesCompletedVideoBeforeReportingAPlayablePath() = runBlocking {
        inDirectory { context, _ ->
            val downloadUri = "https://generativelanguage.googleapis.com/v1beta/files/video1:download?alt=media"
            val client = MediaGenerationClient(context, readKey = { "fixture-key" }, execute = {
                response(it, "{\"done\":true,\"response\":{\"generateVideoResponse\":{\"generatedSamples\":[{\"video\":{\"uri\":\"$downloadUri\"}}]}}}")
            }, download = { uri, key ->
                assertEquals(downloadUri, uri); assertEquals("fixture-key", key)
                Response.Builder().request(Request.Builder().url(uri).build()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112).toResponseBody("video/mp4".toMediaType())).build()
            })
            val result = client.step(job.copy(operation = "operations/abc"))
            assertEquals("completed", result.state)
            val file = File(Uri.parse(result.path).path!!)
            assertTrue(file.exists()); assertEquals(8L, file.length())
            assertFalse(File(file.path + ".part").exists())
        }
    }
    @Test fun openAiImageUsesImagesEndpointAndNeverTheRetiredVideoEndpoint() = runBlocking {
        inDirectory { context, _ ->
            val client = MediaGenerationClient(context, readKey = { "fixture-key" }, execute = {
                assertEquals("/v1/images/generations", it.url.encodedPath)
                assertEquals("Bearer fixture-key", it.header("Authorization"))
                assertNull(it.header("x-goog-api-key"))
                response(it, "{\"data\":[{\"b64_json\":\"AQID\"}]}")
            })
            val result = client.step(job.copy(kind = "image", provider = "openai", model = "gpt-image-1.5", aspect = "1:1"))
            assertEquals("completed", result.state)
            assertTrue(result.path!!.endsWith(".png"))
        }
    }
    @Test fun emptySuccessfulProviderResponseDoesNotProduceAPhantomImage() = runBlocking {
        inDirectory { context, dir ->
            val client = MediaGenerationClient(context, readKey = { "fixture-key" }, execute = { response(it, "{}") })
            assertTrue(runCatching { client.step(job.copy(kind = "image", provider = "openai", model = "gpt-image-1.5")) }.isFailure)
            assertTrue(File(dir, "chat-media").listFiles().orEmpty().isEmpty())
        }
    }
}
