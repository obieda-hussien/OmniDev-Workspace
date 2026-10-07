package com.omnidev.workspace.data.tools

import android.content.Context
import com.omnidev.workspace.data.chatmedia.*
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.repository.ApiKeyRepository
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

object MediaGenerationTool {
    fun definition() = ToolDefinition("media_generation",
        "Generate an image (Gemini or OpenAI) or a video with Gemini Veo, and attach it directly to this chat. Uses a separately configured provider API key and its media quota. " +
            "Generation is asynchronous and persists after the reply: never call queued work completed. status checks an existing job; never create it again to poll. " +
            "attach sends an accessible local file/path to chat without publishing externally. The user can preview, zoom, play, save and share media. Supports طلبات توليد الصور والفيديو وإرسال الملفات والصور والصوت في الشات.",
        listOf(ToolParameter("action", "string", "image, video, status, cancel, attach", allowedValues = listOf("image", "video", "status", "cancel", "attach")),
            ToolParameter("prompt", "string", "Image/video description", false, requiredForActions = listOf("image", "video")),
            ToolParameter("provider", "string", "gemini or openai; video uses gemini. Default gemini", false, allowedValues = listOf("gemini", "openai")),
            ToolParameter("model", "string", "Optional image/video model ID; no URL or path", false),
            ToolParameter("aspect_ratio", "string", "1:1, 16:9 or 9:16; video supports 16:9 or 9:16", false, allowedValues = listOf("1:1", "16:9", "9:16")),
            ToolParameter("job_id", "string", "Existing local generation job ID", false, requiredForActions = listOf("status", "cancel")),
            ToolParameter("path", "string", "Accessible phone path, file URI or user-selected content URI", false, requiredForActions = listOf("attach"))))
    suspend fun execute(context: Context, args: Map<String, String>): ToolExecutionResult {
        try {
            val action = args["action"] ?: error("Missing action.")
            val store = MediaJobStore(context)
            when (action) {
                "attach" -> {
                    val value = args["path"] ?: error("Missing path.")
                    require(!value.startsWith("https://")) { "For remote media, include a direct HTTPS media link in the reply." }
                    val meta = ChatMediaStore.metadata(context, value) ?: error("File unavailable. Ask the user to select it with Attach file.")
                    val payload = JSONObject().put("status", "attached").put("attachments", JSONArray().put(JSONObject()
                        .put("uri", meta.uri).put("mime_type", meta.mimeType).put("file_name", meta.fileName)))
                    return ToolExecutionResult(payload.toString(), classification = "SUCCESS", backend = "chat-media")
                }
                "image", "video" -> {
                    val provider = args["provider"] ?: "gemini"
                    require(provider in setOf("gemini", "openai") && (action != "video" || provider == "gemini")) { "Video generation uses Gemini Veo. Configure a Gemini API key." }
                    val key = ApiKeyRepository(context).getApiKey(if (provider == "gemini") ModelProvider.GEMINI else ModelProvider.OPENAI)
                    require(!key.isNullOrBlank()) { "Configure the $provider API key in Providers. The selected text model's key does not grant media generation access." }
                    val prompt = args["prompt"]?.trim()?.takeIf { it.length in 1..12_000 } ?: error("Provide a description up to 12000 characters.")
                    val model = args["model"] ?: if (action == "video") "veo-3.1-fast-generate-preview" else if (provider == "gemini") "gemini-3.1-flash-image" else "gpt-image-1.5"
                    require(model.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Invalid media model ID." }
                    val aspect = args["aspect_ratio"] ?: if (action == "video") "16:9" else "1:1"
                    require(aspect in if (action == "video") setOf("16:9", "9:16") else setOf("1:1", "16:9", "9:16"))
                    val job = store.create(action, provider, model, prompt, aspect)
                    MediaGenerationWorker.enqueue(context, job.id)
                    return result(job)
                }
                "status", "cancel" -> {
                    val id = args["job_id"] ?: error("Missing job_id.")
                    val job = store.get(id) ?: error("Unknown local generation job.")
                    if (action == "cancel") MediaGenerationWorker.cancel(context, id)
                    else if (job.state == "failed" && job.operation != null) MediaGenerationWorker.enqueue(context, id)
                    return result(store.get(id) ?: job)
                }
                else -> error("Unsupported media action.")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { return ToolExecutionResult(error.message ?: "Media operation unavailable.", true, backend = "chat-media") }
    }
    private fun result(job: MediaJob) = ToolExecutionResult(JSONObject().put("job_id", job.id).put("status", job.state)
        .put("detail", job.error ?: if (job.state == "completed") "Ready in chat." else "Generation submitted. The chat card updates when media is ready; do not claim completion yet.")
        .put("attachments", JSONArray().put(JSONObject().put("uri", "omni-media-job:${job.id}")
            .put("mime_type", if (job.kind == "video") "video/mp4" else "image/png")
            .put("file_name", if (job.kind == "video") "Generated video" else "Generated image"))).toString(),
        isError = job.state == "failed", classification = if (job.state == "failed") "MEDIA_GENERATION_FAILED" else "SUCCESS", backend = "chat-media")
}
