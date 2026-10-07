package com.omnidev.workspace.data.chatmedia

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.omnidev.workspace.data.repository.ApiKeyRepository
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Requests use the saved media selection, independently of the conversation's text model. */
internal class MediaGenerationClient(
    private val context: Context,
    private val readKey: suspend (String) -> String? = { provider ->
        ApiKeyRepository(context).getApiKey(MediaModelCatalog.provider(provider))
    },
    private val execute: suspend (Request) -> Response = MediaHttp::execute,
    private val download: suspend (String, String?) -> Response = MediaHttp::downloadResponse
) {
    private val json = "application/json".toMediaType()
    private suspend fun key(provider: String) = readKey(provider)?.takeIf { it.isNotBlank() }
        ?: error("Connect the selected $provider account in Providers. Media generation requires model access and quota.")
    private suspend fun request(url: String, provider: String, body: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url(MediaHttp.url(url))
        if (provider == "gemini") builder.header("x-goog-api-key", key(provider))
        else builder.header("Authorization", "Bearer ${key(provider)}")
        if (body != null) builder.post(body.toString().toRequestBody(json))
        return execute(builder.build()).use { response ->
            require(response.isSuccessful) { "Provider returned HTTP ${response.code}. Check model access, quota and media settings." }
            val stream = response.body?.byteStream() ?: error("Empty provider response.")
            val data = stream.use { source ->
                val target = java.io.ByteArrayOutputStream(); val buffer = ByteArray(64 * 1024)
                while (true) { currentCoroutineContext().ensureActive(); val read = source.read(buffer); if (read < 0) break
                    require(target.size() + read <= 40 * 1024 * 1024) { "Provider response exceeded 40 MB." }; target.write(buffer, 0, read) }
                target.toByteArray()
            }
            JSONObject(data.toString(Charsets.UTF_8))
        }
    }
    private fun config(job: MediaJob) = job.config ?: MediaConfig.defaults(MediaKind.fromAction(job.kind) ?: error("Unknown media kind"))
        .copy(enabled = true, provider = MediaModelCatalog.provider(job.provider), model = job.model, aspect = job.aspect)
    private suspend fun save(job: MediaJob, bytes: ByteArray, mime: String): String {
        require(bytes.isNotEmpty() && bytes.size <= 30 * 1024 * 1024) { "Empty or oversized generated media." }
        val extension = when(mime) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "audio/mpeg", "audio/mp3" -> "mp3"; "audio/wav", "audio/x-wav" -> "wav"; else -> error("Unsupported generated format: $mime") }
        val file = File(ChatMediaStore.directory(context), "omni-${job.id}.$extension"); val temp = File(file.path + ".part")
        try { temp.writeBytes(bytes); currentCoroutineContext().ensureActive(); check(temp.renameTo(file)); return Uri.fromFile(file).toString() }
        finally { temp.delete() }
    }
    private suspend fun image(job: MediaJob): String {
        val c = config(job); var mime = "image/${c.format}"
        val encoded = if (job.provider == "gemini") {
            val imageConfig = JSONObject().put("aspectRatio", c.aspect)
            if (!job.model.startsWith("gemini-2.5")) imageConfig.put("imageSize", c.resolution)
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", job.prompt)))))
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")).put("imageConfig", imageConfig))
            val response = request("https://generativelanguage.googleapis.com/v1beta/models/${job.model}:generateContent", job.provider, body)
            val parts = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val inline = (0 until (parts?.length() ?: 0)).firstNotNullOfOrNull { parts?.optJSONObject(it)?.optJSONObject("inlineData") }
                ?: error("Provider returned no image. Check image output support and prompt policy.")
            mime = inline.optString("mimeType", "image/png"); inline.optString("data")
        } else {
            val body = JSONObject().put("model", job.model).put("prompt", job.prompt).put("n", 1)
            val endpoint = when(job.provider) {
                "openai" -> {
                    body.put("size", when(c.aspect) { "9:16" -> "1024x1536"; "16:9" -> "1536x1024"; else -> "1024x1024" })
                    "https://api.openai.com/v1/images/generations"
                }
                "open_router" -> { body.put("resolution", c.resolution).put("aspect_ratio", c.aspect); "https://openrouter.ai/api/v1/images" }
                "xai" -> { body.put("response_format", "b64_json").put("aspect_ratio", c.aspect).put("resolution", c.resolution.lowercase()); mime = "image/jpeg"; "https://api.x.ai/v1/images/generations" }
                else -> error("Unsupported image provider")
            }
            if (job.provider != "xai") {
                body.put("output_format", c.format).put("quality", c.quality).put("background", c.background)
                if (c.format != "png") body.put("output_compression", c.compression)
            }
            val item = request(endpoint, job.provider, body).optJSONArray("data")?.optJSONObject(0) ?: error("Provider returned no image.")
            mime = item.optString("media_type").takeIf { it.isNotBlank() } ?: mime
            item.optString("b64_json")
        }
        require(encoded.isNotBlank() && encoded.length <= 40 * 1024 * 1024) { "Provider returned no supported image." }
        return save(job, Base64.decode(encoded, Base64.DEFAULT), mime)
    }
    private suspend fun music(job: MediaJob): MediaJob {
        val c = config(job)
        var lyrics: String? = null
        val bytes: ByteArray; val mime: String
        if (job.provider == "minimax") {
            require(job.prompt.length <= 2000) { "MiniMax music direction must be under 2000 characters; use the separate lyrics field." }
            val body = JSONObject().put("model", job.model).put("prompt", job.prompt).put("stream", false).put("output_format", "hex")
                .put("is_instrumental", c.instrumental).put("audio_setting", JSONObject().put("sample_rate", 44100).put("bitrate", 256000).put("format", "mp3"))
            if (!c.instrumental) { if (c.lyrics.isBlank()) body.put("lyrics_optimizer", true) else body.put("lyrics", c.lyrics) }
            val response = request("https://api.minimax.io/v1/music_generation", job.provider, body)
            require(response.optJSONObject("base_resp")?.optInt("status_code", -1) == 0) { "MiniMax rejected generation. Check existing paid music access, quota and prompt." }
            require(response.optJSONObject("data")?.optInt("status", -1) == 2) { "MiniMax returned no completed music." }
            val hex = response.optJSONObject("data")?.optString("audio").orEmpty()
            require(hex.isNotEmpty() && hex.length % 2 == 0 && hex.length <= 40 * 1024 * 1024 && hex.all { it.digitToIntOrNull(16) != null }) { "Invalid music response." }
            bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }; mime = "audio/mpeg"
        } else {
            val body = JSONObject().put("model", job.model).put("input", job.prompt)
            if (c.format == "wav") body.put("response_format", JSONObject().put("type", "audio"))
            val response = request("https://generativelanguage.googleapis.com/v1beta/interactions", job.provider, body)
            val steps = response.optJSONArray("steps"); var audio: JSONObject? = null; val texts = mutableListOf<String>()
            for (i in 0 until (steps?.length() ?: 0)) {
                val step = steps?.optJSONObject(i) ?: continue
                if (step.optString("type") != "model_output") continue
                val content = step.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) { val block = content.optJSONObject(j) ?: continue
                    when(block.optString("type")) { "audio" -> if (audio == null) audio = block; "text" -> texts += block.optString("text") } }
            }
            val encoded = audio?.optString("data").orEmpty()
            require(encoded.isNotBlank() && encoded.length <= 40 * 1024 * 1024) { "Provider returned no completed music. Check model access and prompt." }
            bytes = Base64.decode(encoded, Base64.DEFAULT); mime = audio?.optString("mime_type")?.takeIf { it.isNotBlank() } ?: "audio/mpeg"
            lyrics = texts.joinToString("\n").take(8000).takeIf { it.isNotBlank() }
        }
        return job.copy(state = "completed", path = save(job, bytes, mime), prompt = "", lyricsText = lyrics, error = null)
    }
    suspend fun step(job: MediaJob): MediaJob = withContext(Dispatchers.IO) {
        val c = config(job); MediaRequestPolicy.validate(MediaKind.fromAction(job.kind) ?: error("Unknown kind"), c)
        if (job.kind == "image") return@withContext job.copy(state = "completed", path = image(job), prompt = "", error = null)
        if (job.kind == "music") return@withContext music(job)
        if (job.operation == null) {
            val created = if (job.provider == "xai") request("https://api.x.ai/v1/videos/generations", job.provider,
                JSONObject().put("model", job.model).put("prompt", job.prompt).put("duration", c.durationSeconds).put("aspect_ratio", c.aspect).put("resolution", c.resolution).put("generate_audio", c.videoAudio))
            else request("https://generativelanguage.googleapis.com/v1beta/models/${job.model}:predictLongRunning", job.provider,
                JSONObject().put("instances", JSONArray().put(JSONObject().put("prompt", job.prompt)))
                    .put("parameters", JSONObject().put("aspectRatio", c.aspect).put("durationSeconds", c.durationSeconds).put("resolution", c.resolution)
                        .apply { if (c.negativePrompt.isNotBlank()) put("negativePrompt", c.negativePrompt) }))
            val operation = created.optString(if (job.provider == "xai") "request_id" else "name")
            require(if (job.provider == "xai") validRequestId(operation) else validOperation(operation)) { "Invalid provider operation identifier." }
            return@withContext job.copy(operation = operation, state = "processing", prompt = "", error = null)
        }
        val value: String
        if (job.provider == "xai") {
            require(validRequestId(job.operation))
            val response = request("https://api.x.ai/v1/videos/${job.operation}", job.provider)
            when(response.optString("status")) {
                "pending", "processing" -> return@withContext job.copy(state = "processing", error = null)
                "done" -> value = response.optJSONObject("video")?.optString("url").orEmpty()
                else -> return@withContext job.copy(state = "failed", error = "Video provider failed or expired this generation.")
            }
        } else {
            require(validOperation(job.operation))
            val response = request("https://generativelanguage.googleapis.com/v1beta/${job.operation}", job.provider)
            if (!response.optBoolean("done")) return@withContext job.copy(state = "processing", error = null)
            if (response.has("error")) return@withContext job.copy(state = "failed", error = "Video provider rejected or failed this generation.")
            value = response.optJSONObject("response")?.optJSONObject("generateVideoResponse")?.optJSONArray("generatedSamples")?.optJSONObject(0)?.optJSONObject("video")?.optString("uri").orEmpty()
            val url = MediaHttp.url(value)
            require(url.host == "generativelanguage.googleapis.com" && url.encodedPath.startsWith("/v1beta/files/")) { "Unexpected provider video location." }
        }
        val file = File(ChatMediaStore.directory(context), "omni-${job.id}.mp4"); val temp = File(file.path + ".part")
        try {
            download(value, if (job.provider == "gemini") key("gemini") else null).use { downloaded ->
                require(downloaded.isSuccessful) { "Video download failed (${downloaded.code})." }
                val body = downloaded.body ?: error("Empty video response."); require(body.contentLength() <= ChatMediaStore.MAX_BYTES)
                body.byteStream().use { source -> temp.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) { currentCoroutineContext().ensureActive(); val count = source.read(buffer); if (count < 0) break
                        total += count; require(total <= ChatMediaStore.MAX_BYTES); target.write(buffer, 0, count) }; require(total > 0)
                } }
            }
            currentCoroutineContext().ensureActive(); check(temp.renameTo(file))
            job.copy(state = "completed", path = Uri.fromFile(file).toString(), error = null)
        } finally { temp.delete() }
    }
    companion object {
        fun validOperation(value: String) = value.matches(Regex("(?:models/[A-Za-z0-9._-]+/)?operations/[A-Za-z0-9._-]+"))
        fun validRequestId(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,200}"))
    }
}
