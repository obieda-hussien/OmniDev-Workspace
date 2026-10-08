package com.omnidev.workspace.data.routines

import android.content.Context
import android.util.Base64
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolParameter
import com.omnidev.workspace.data.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LearnedRoutineTool(
    private val context: Context,
    execute: suspend (String, Map<String, String>, String?) -> ToolExecutionResult,
    private val confirmTeaching: suspend (String) -> Boolean = { false }
) {
    val hub = RoutineLearningHub.get(context)
    val runner = hub.createRunner(execute)
    private val json = Json { ignoreUnknownKeys = false }
    fun definitions() = listOf(ToolDefinition("learned_routine",
        "Durable local executable skills. Actions: list, inspect, save_draft, run, status, resume, pause, teach_start, teach_decision, teach_stop, video_frame. " +
            "UI selectors use packageName + viewId/text/description, never N IDs/coordinates. Drafts require user review and activation in Agent Skills. " +
            "Use {{parameter}} for typed values. DECISION/USER steps pause; solve only the pending step using current evidence. " +
            "Never run a recipe just because its name or trigger appears in conversation. Match the latest user's intent, " +
            "bound values and constraints; inspect candidates first. Non-exact run/resume proposals require user review. " +
            "Resume advances only when expected selector is present. Never claim an imported video is fully understood from sparse samples.",
        listOf(ToolParameter("action", "string", "Action", true),
            ToolParameter("routine_id", "string", "Saved recipe ID", false),
            ToolParameter("run_id", "string", "Paused run ID", false),
            ToolParameter("recipe", "string", "LearnedRoutine JSON for save_draft (forced disabled)", false),
            ToolParameter("parameters", "string", "JSON map of runtime parameters, never stored on disk", false),
            ToolParameter("name", "string", "Name for a teaching session", false),
            ToolParameter("index", "string", "Video sample index 0..7", false))))
    suspend fun execute(args: Map<String, String>, scopePath: String?): ToolExecutionResult = try {
        val id = args["routine_id"].orEmpty()
        val parameters = args["parameters"]?.let { json.decodeFromString<Map<String, String>>(it) }.orEmpty()
        when (args["action"]) {
            "list" -> ToolExecutionResult(json.encodeToString(hub.store.list().map { it.copy(steps = emptyList()) }))
            "inspect" -> ToolExecutionResult(json.encodeToString(hub.store.get(id) ?: error("Routine not found")))
            "save_draft" -> {
                val recipe = json.decodeFromString<LearnedRoutine>(args["recipe"] ?: error("Missing recipe"))
                val previous = hub.store.get(recipe.id)
                hub.store.save(recipe.copy(enabled = false, source = previous?.source ?: recipe.source, revision = (previous?.revision ?: 0) + 1))
                hub.changed(); ToolExecutionResult("Draft saved. User must review and enable it in Agent Skills.")
            }
            "run" -> result(runner.start(hub.store.get(id) ?: error("Routine not found"), parameters, scopePath))
            "resume" -> result(runner.resume(args["run_id"] ?: error("Missing run ID"), parameters))
            "status" -> ToolExecutionResult(json.encodeToString(hub.store.runs()))
            "pause" -> { runner.pause(); ToolExecutionResult("Pause requested after the current action") }
            "teach_start" -> {
                val name = args["name"] ?: error("Missing name")
                if (!confirmTeaching("Teach task '$name': capture semantic interactions in other apps until you stop using notification controls."))
                    ToolExecutionResult("Teaching was not approved", true, classification = "USER_DENIED")
                else { hub.startTeaching(name); ToolExecutionResult("Teaching started; stop or mark decisions using notification controls") }
            }
            "teach_decision" -> { hub.decision(); ToolExecutionResult("Decision checkpoint added") }
            "teach_stop" -> ToolExecutionResult(hub.stopTeaching()?.let { "Saved draft ${it.id}" } ?: "No steps captured")
            "video_frame" -> withContext(Dispatchers.IO) {
                val recipe = hub.store.get(id) ?: error("Routine not found")
                val file = RoutineVideoImporter.frameFile(context, recipe, args["index"]?.toIntOrNull() ?: 0) ?: error("Frame not found")
                ToolExecutionResult("[SCREENSHOT_BASE64]\ndata:image/jpeg;base64,${Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)}\n[/SCREENSHOT_BASE64]\nVideo sample only; actions and hidden UI identities cannot be inferred with certainty.")
            }
            else -> ToolExecutionResult("Unknown routine action", true)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (error: Exception) { ToolExecutionResult(error.message ?: "Routine operation failed", true) }
    fun result(run: RoutineRun) = ToolExecutionResult(
        "${hub.store.get(run.routineId)?.name.orEmpty()}: ${run.status.name.lowercase()} · step ${run.nextStep + 1}. ${run.reason}\nCheckpoint: " +
            json.encodeToString(run.copy(parameters = emptyMap())),
        isError = run.status != RoutineRunStatus.COMPLETED, classification = if (run.status == RoutineRunStatus.COMPLETED) "LOCAL_ROUTINE_SUCCESS" else "ROUTINE_HANDOFF", retryable = false)
}
