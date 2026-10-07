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
    @Test fun lyriaUsesInteractionsAndSavesPlayableAudioAndLyrics() = runBlocking {
        inDirectory { context, _ ->
            val config = MediaConfig.defaults(MediaKind.MUSIC).copy(enabled = true)
            val client = MediaGenerationClient(context, readKey = { "music-key" }, execute = {
                assertEquals("/v1beta/interactions", it.url.encodedPath)
                assertEquals("music-key", it.header("x-goog-api-key"))
                val buffer = Buffer(); it.body!!.writeTo(buffer)
                assertEquals("lyria-3-clip-preview", JSONObject(buffer.readUtf8()).getString("model"))
                response(it, """{"steps":[{"type":"model_output","content":[{"type":"text","text":"Lyrics"},{"type":"audio","mime_type":"audio/mpeg","data":"AQID"}]}]}""")
            })
            val result = client.step(job.copy(kind = "music", model = config.model, aspect = "", config = config))
            assertEquals("completed", result.state); assertTrue(result.path!!.endsWith(".mp3")); assertEquals("Lyrics", result.lyricsText)
            assertEquals(3L, File(Uri.parse(result.path).path!!).length())
        }
    }
    @Test fun miniMaxMusicUsesSeparateLyricsAndHandlesHttp200ProviderErrors() = runBlocking {
        inDirectory { context, _ ->
            val config = MediaConfig.defaults(MediaKind.MUSIC).copy(enabled = true, provider = com.omnidev.workspace.data.model.ModelProvider.MINIMAX, model = "music-3.0", durationSeconds = 120, lyrics = "Hello")
            var fail = false
            val client = MediaGenerationClient(context, readKey = { "music-key" }, execute = {
                assertEquals("/v1/music_generation", it.url.encodedPath); assertEquals("Bearer music-key", it.header("Authorization"))
                val buffer = Buffer(); it.body!!.writeTo(buffer); val body = JSONObject(buffer.readUtf8())
                assertEquals("Hello", body.getString("lyrics")); assertFalse(body.has("lyrics_optimizer"))
                response(it, if (fail) """{"base_resp":{"status_code":1008}}""" else """{"base_resp":{"status_code":0},"data":{"status":2,"audio":"010203"}}""")
            })
            val song = job.copy(kind = "music", provider = "minimax", model = config.model, aspect = "", config = config)
            assertTrue(client.step(song).path!!.endsWith(".mp3"))
            fail = true; assertTrue(runCatching { client.step(song) }.isFailure)
        }
    }
    @Test fun openRouterUsesDedicatedImageRouteAndSavedOutputSettings() = runBlocking {
        inDirectory { context, _ ->
            val config = MediaConfig().copy(enabled = true, provider = com.omnidev.workspace.data.model.ModelProvider.OPEN_ROUTER, model = "vendor/image", format = "webp", resolution = "2K", quality = "high")
            val client = MediaGenerationClient(context, readKey = { "key" }, execute = {
                assertEquals("openrouter.ai", it.url.host); assertEquals("/api/v1/images", it.url.encodedPath)
                val buffer = Buffer(); it.body!!.writeTo(buffer); val body = JSONObject(buffer.readUtf8())
                assertEquals("vendor/image", body.getString("model")); assertEquals("webp", body.getString("output_format")); assertEquals("2K", body.getString("resolution"))
                response(it, """{"data":[{"b64_json":"AQID","media_type":"image/webp"}]}""")
            })
            assertTrue(client.step(job.copy(kind = "image", provider = "open_router", model = config.model, aspect = config.aspect, config = config)).path!!.endsWith(".webp"))
        }
    }
    @Test fun xaiVideoSavesOperationThenPollsAndNeverForwardsApiKeyToVideoHost() = runBlocking {
        inDirectory { context, _ ->
            val config = MediaConfig.defaults(MediaKind.VIDEO).copy(enabled = true, provider = com.omnidev.workspace.data.model.ModelProvider.XAI, model = "grok-imagine-video-1.5", durationSeconds = 12, videoAudio = false)
            var create = true
            val client = MediaGenerationClient(context, readKey = { "key" }, execute = {
                assertEquals("Bearer key", it.header("Authorization")); assertNull(it.header("x-goog-api-key"))
                if (create) {
                    assertEquals("POST", it.method); val buffer = Buffer(); it.body!!.writeTo(buffer); val body = JSONObject(buffer.readUtf8())
                    assertEquals(12, body.getInt("duration")); assertFalse(body.getBoolean("generate_audio"))
                    response(it, """{"request_id":"uuid-test"}""")
                } else { assertEquals("GET", it.method); response(it, """{"status":"done","video":{"url":"https://video.example/result.mp4"}}""") }
            }, download = { uri, key -> assertNull(key); response(Request.Builder().url(uri).build(), "video bytes") })
            val pending = client.step(job.copy(provider = "xai", model = config.model, config = config))
            assertEquals("processing", pending.state); assertEquals("uuid-test", pending.operation); assertNull(pending.path)
            create = false; assertEquals("completed", client.step(pending).state)
        }
    }

}
