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
        "Generate images, videos or music/songs using the user's enabled media models and saved settings in Model Selection. " +
            "Never bypass a disabled type or change its selected provider/model. Generation persists after the reply: queued is not completed. " +
            "The chat card becomes playable/viewable when ready and a completion message is posted. status polls an existing job; never generate again to poll. " +
            "attach sends an accessible phone file/path to chat. Supports توليد الصور والفيديو والموسيقى والأغاني وإرسال الملفات في الشات.",
        listOf(ToolParameter("action", "string", "image, video, music, status, cancel, attach", allowedValues = listOf("image", "video", "music", "status", "cancel", "attach")),
            ToolParameter("prompt", "string", "Description to generate using saved defaults", false, requiredForActions = listOf("image", "video", "music")),
            ToolParameter("aspect_ratio", "string", "Optional request override, if allowed by settings", false),
            ToolParameter("resolution", "string", "Optional supported resolution", false),
            ToolParameter("quality", "string", "Optional image quality: auto, low, medium, high", false),
            ToolParameter("format", "string", "Optional supported image or music format", false),
            ToolParameter("background", "string", "Optional supported image background: auto, opaque, transparent", false),
            ToolParameter("compression", "integer", "Optional JPEG/WebP output compression quality, 0–100", false),
            ToolParameter("duration_seconds", "integer", "Supported video duration or music duration hint", false),
            ToolParameter("video_audio", "boolean", "xAI video audio override", false),
            ToolParameter("style", "string", "Optional creative direction", false),
            ToolParameter("negative_prompt", "string", "Optional things to avoid", false),
            ToolParameter("lyrics", "string", "Optional song lyrics", false),
            ToolParameter("instrumental", "boolean", "Music without vocals", false),
            ToolParameter("tempo_bpm", "integer", "Optional 40–240 BPM music guidance", false),
            ToolParameter("genre", "string", "Optional music genre", false),
            ToolParameter("mood", "string", "Optional mood", false),
            ToolParameter("instruments", "string", "Optional instruments", false),
            ToolParameter("language", "string", "Optional song lyrics language", false),
            ToolParameter("job_id", "string", "Existing local generation job ID", false, requiredForActions = listOf("status", "cancel")),
            ToolParameter("path", "string", "Accessible phone path, file URI or user-selected content URI", false, requiredForActions = listOf("attach"))))
    suspend fun execute(context: Context, args: Map<String, String>, sessionId: Long? = null): ToolExecutionResult {
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
                "image", "video", "music" -> {
                    val kind = MediaKind.fromAction(action) ?: error("Unknown media kind")
                    val preferences = MediaSettingsStore(context).get()
                    val selected = preferences[kind]
                    val connected = if (!ApiKeyRepository(context).getApiKey(selected.provider).isNullOrBlank()) setOf(selected.provider) else emptySet()
                    val config = MediaRequestPolicy.resolve(kind, preferences, connected, args)
                    val prompt = args["prompt"]?.trim()?.takeIf { it.length in 1..12_000 } ?: error("Provide a description up to 12000 characters.")
                    val composed = MediaRequestPolicy.prompt(kind, prompt, if (config.provider == ModelProvider.MINIMAX) config.copy(lyrics = "") else config)
                    if (config.provider == ModelProvider.MINIMAX) require(composed.length <= 2000) { "MiniMax direction must be under 2000 characters; put lyrics in the lyrics field." }
                    val origin = if (sessionId != null && sessionId > 0) {
                        try { com.omnidev.workspace.data.db.OmniDevDatabase.getInstance(context).chatMessageDao().latestUser(sessionId) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    } else null
                    val requestText = origin?.content ?: prompt
                    val job = store.create(action, MediaModelCatalog.key(config.provider), config.model, composed, config.aspect, config, sessionId,
                        requestText.any { it in '\u0600'..'\u06FF' }, origin?.messageId?.takeIf { it.isNotBlank() })
                    MediaGenerationWorker.enqueue(context, job.id)
                    return result(store.get(job.id) ?: job)
                }
                "status", "cancel" -> {
                    val id = args["job_id"] ?: error("Missing job_id.")
                    val job = store.get(id) ?: error("Unknown local generation job.")
                    if (action == "cancel") MediaGenerationWorker.cancel(context, id)
                    else if (MediaGenerationFailure.canResume(job)) {
                        require(MediaSettingsStore(context).get()[MediaKind.fromAction(job.kind) ?: error("Unknown media kind")].enabled) { "This media type is disabled in Model Selection." }
                        MediaGenerationWorker.enqueue(context, id)
                    }
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
            .put("mime_type", when(job.kind) { "video" -> "video/mp4"; "music" -> "audio/mpeg"; else -> "image/png" })
            .put("file_name", "Generated ${job.kind}"))).toString(),
        isError = job.state == "failed", classification = if (job.state == "failed") "MEDIA_GENERATION_FAILED" else "SUCCESS", backend = "chat-media")
}
