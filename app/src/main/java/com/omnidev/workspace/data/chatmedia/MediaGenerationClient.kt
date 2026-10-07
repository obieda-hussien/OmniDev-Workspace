package com.omnidev.workspace.data.chatmedia

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.repository.ApiKeyRepository
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Real provider requests; image/video capability is independent of the conversation's text model. */
internal class MediaGenerationClient(
    private val context: Context,
    private val readKey: suspend (String) -> String? = { provider ->
        ApiKeyRepository(context).getApiKey(if (provider == "openai") ModelProvider.OPENAI else ModelProvider.GEMINI)
    },
    private val execute: suspend (Request) -> Response = MediaHttp::execute,
    private val download: suspend (String, String?) -> Response = MediaHttp::downloadResponse
) {
    private val json = "application/json".toMediaType()
    private suspend fun key(provider: String) = readKey(provider)
        ?.takeIf { it.isNotBlank() } ?: error("Configure the $provider API key in Providers. Media generation requires provider access and quota.")
    private suspend fun request(url: String, provider: String, body: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url(MediaHttp.url(url))
        if (provider == "openai") builder.header("Authorization", "Bearer ${key(provider)}")
        else builder.header("x-goog-api-key", key(provider))
        if (body != null) builder.post(body.toString().toRequestBody(json))
        return execute(builder.build()).use { response ->
            require(response.isSuccessful) { "Provider returned HTTP ${response.code}. Check media access, quota and the selected model in Providers." }
            val stream = response.body?.byteStream() ?: error("Empty provider response.")
            val data = stream.use { source ->
                val target = java.io.ByteArrayOutputStream(); val buffer = ByteArray(64 * 1024)
                while (true) { currentCoroutineContext().ensureActive(); val read = source.read(buffer); if (read < 0) break
                    require(target.size() + read <= 40 * 1024 * 1024) { "Provider response exceeded 40 MB." }; target.write(buffer, 0, read) }
                target.toByteArray()
            }
            require(data.size <= 40 * 1024 * 1024) { "Provider media response exceeded 40 MB." }
            JSONObject(data.toString(Charsets.UTF_8))
        }
    }
    private suspend fun image(job: MediaJob): String {
        val encoded: String
        var mime = "image/png"
        if (job.provider == "openai") {
            val body = JSONObject().put("model", job.model).put("prompt", job.prompt).put("n", 1)
                .put("size", when(job.aspect) { "9:16" -> "1024x1536"; "16:9" -> "1536x1024"; else -> "1024x1024" })
                .put("output_format", "png")
            val response = request("https://api.openai.com/v1/images/generations", job.provider, body)
            encoded = response.optJSONArray("data")?.optJSONObject(0)?.optString("b64_json").orEmpty()
        } else {
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", job.prompt)))))
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
                    .put("imageConfig", JSONObject().put("aspectRatio", job.aspect)))
            val response = request("https://generativelanguage.googleapis.com/v1beta/models/${job.model}:generateContent", job.provider, body)
            val parts = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val inline = (0 until (parts?.length() ?: 0)).firstNotNullOfOrNull { i -> parts?.optJSONObject(i)?.optJSONObject("inlineData") }
                ?: error("Provider returned no image. The prompt may have been refused or this model may lack image output.")
            mime = inline.optString("mimeType", "image/png"); encoded = inline.optString("data")
        }
        require(encoded.isNotBlank() && encoded.length <= 40 * 1024 * 1024) { "Provider returned no supported image." }
        require(mime in setOf("image/png", "image/jpeg", "image/webp")) { "Unsupported generated image format." }
        val extension = when(mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "png" }
        val file = File(ChatMediaStore.directory(context), "omni-${job.id}.$extension")
        val temp = File(file.path + ".part")
        try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            require(bytes.isNotEmpty() && bytes.size <= 30 * 1024 * 1024)
            temp.writeBytes(bytes)
            currentCoroutineContext().ensureActive()
            check(temp.renameTo(file)); return Uri.fromFile(file).toString()
        } finally { temp.delete() }
    }
    suspend fun step(job: MediaJob): MediaJob = withContext(Dispatchers.IO) {
        if (job.kind == "image") return@withContext job.copy(state = "completed", path = image(job), prompt = "", error = null)
        if (job.operation == null) {
            val body = JSONObject().put("instances", JSONArray().put(JSONObject().put("prompt", job.prompt)))
                .put("parameters", JSONObject().put("aspectRatio", job.aspect).put("durationSeconds", 8).put("resolution", "720p"))
            val created = request("https://generativelanguage.googleapis.com/v1beta/models/${job.model}:predictLongRunning", "gemini", body)
            val operation = created.optString("name")
            require(validOperation(operation)) { "Invalid provider operation identifier." }
            return@withContext job.copy(operation = operation, state = "processing", prompt = "", error = null)
        }
        require(validOperation(job.operation))
        val response = request("https://generativelanguage.googleapis.com/v1beta/${job.operation}", "gemini")
        if (!response.optBoolean("done")) return@withContext job.copy(state = "processing", error = null)
        if (response.has("error")) return@withContext job.copy(state = "failed", error = "Video provider rejected or failed this generation. Check model access, quota and prompt policy.")
        val value = response.optJSONObject("response")?.optJSONObject("generateVideoResponse")?.optJSONArray("generatedSamples")
            ?.optJSONObject(0)?.optJSONObject("video")?.optString("uri").orEmpty()
        val url = MediaHttp.url(value)
        require(url.host == "generativelanguage.googleapis.com" && url.encodedPath.startsWith("/v1beta/files/")) { "Unexpected provider video location." }
        val file = File(ChatMediaStore.directory(context), "omni-${job.id}.mp4")
        val temp = File(file.path + ".part")
        try {
            download(value, key("gemini")).use { downloaded ->
                require(downloaded.isSuccessful) { "Video download failed (${downloaded.code}). Check provider access." }
                val body = downloaded.body ?: error("Empty video response.")
                require(body.contentLength() <= ChatMediaStore.MAX_BYTES)
                body.byteStream().use { source -> temp.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) { currentCoroutineContext().ensureActive(); val count = source.read(buffer); if (count < 0) break
                        total += count; require(total <= ChatMediaStore.MAX_BYTES); target.write(buffer, 0, count) }
                    require(total > 0)
                } }
            }
            check(temp.renameTo(file))
            job.copy(state = "completed", path = Uri.fromFile(file).toString(), error = null)
        } finally { temp.delete() }
    }
    companion object {
        fun validOperation(value: String) = value.matches(Regex("(?:models/[A-Za-z0-9._-]+/)?operations/[A-Za-z0-9._-]+"))
    }
}
