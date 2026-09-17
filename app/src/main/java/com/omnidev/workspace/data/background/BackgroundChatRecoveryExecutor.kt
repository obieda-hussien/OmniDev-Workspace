package com.omnidev.workspace.data.background

import android.content.Context
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.domain.engine.AgentEvent
import com.omnidev.workspace.domain.engine.AgentRuntime
import com.omnidev.workspace.domain.engine.ChatToolLoop
import com.omnidev.workspace.domain.engine.IntentClassifier
import com.omnidev.workspace.domain.engine.OmniMode
import com.omnidev.workspace.domain.engine.SwarmEvent
import com.omnidev.workspace.registry.ModelRegistry
import com.omnidev.workspace.ui.chat.AgentConsoleEntry
import com.omnidev.workspace.ui.chat.consoleEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Rebuilds an interrupted chat run from Room after process death.
 *
 * Recovery is checkpoint-aware: the existing conversation (including partial assistant rows and
 * persisted console activity) is fed back to the model with an explicit continuation directive.
 * This greatly reduces repeated work/tool calls compared with blindly replaying the original prompt.
 */
class BackgroundChatRecoveryExecutor(private val context: Context) {

    data class Progress(
        val status: String,
        val partialText: String = "",
        val toolsUsed: Int = 0
    )

    data class Outcome(
        val success: Boolean,
        val result: String,
        val error: String? = null,
        val transientFailure: Boolean = false
    )

    suspend fun execute(
        run: BackgroundChatRun,
        onProgress: suspend (Progress) -> Unit
    ): Outcome {
        val runtime = AgentRuntime(context.applicationContext)
        runtime.fileToolManager.godModeEnabled = runtime.settingsRepository.observeGodMode().first()
        runtime.toolManager.currentSessionId = run.sessionId

        val (allMessages, _) = runtime.chatRepository.loadMessages(run.sessionId)
        val origin = runtime.chatRepository.getMessageById(run.userMessageId)
            ?: return Outcome(false, "", "Original user message is no longer available.")
        val mode = resolveMode(run.mode, origin.content)
        val recentHistory = allMessages.takeLast(30)
        val scope = resolveScope(runtime, run)
        val resumePrompt = buildResumePrompt(origin, recentHistory)

        var assistant = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "Resuming interrupted task in background…"
        )
        val rowId = runtime.chatRepository.saveMessage(run.sessionId, assistant)
        val entries = mutableListOf<AgentConsoleEntry>()
        var lastPersistMs = 0L
        var partial = ""
        var toolsUsed = 0

        suspend fun persist(status: String, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPersistMs < 700L) return
            lastPersistMs = now
            val display = partial.trim().ifBlank { status }
            runtime.chatRepository.updateRun(
                rowId,
                assistant.copy(content = display.take(100_000)),
                entries.takeLast(500)
            )
            onProgress(Progress(status = status, partialText = partial.takeLast(1_500), toolsUsed = toolsUsed))
        }

        return try {
            val result = when (mode) {
                OmniMode.CHAT -> executeChat(
                    runtime = runtime,
                    origin = origin,
                    history = recentHistory,
                    resumePrompt = resumePrompt,
                    onText = { text -> partial = text; persist("Writing response…") },
                    onEvent = { event ->
                        event.consoleEntry()?.let(entries::add)
                        if (event is AgentEvent.ToolExecution) toolsUsed++
                        persist(agentStatus(event))
                    }
                )

                OmniMode.AGENT -> executeAgent(
                    runtime = runtime,
                    origin = origin,
                    history = recentHistory,
                    resumePrompt = resumePrompt,
                    scope = scope,
                    onEvent = { event ->
                        event.consoleEntry()?.let(entries::add)
                        when (event) {
                            is AgentEvent.StreamChunk -> partial += event.delta
                            is AgentEvent.FinalAnswer -> partial = event.content
                            is AgentEvent.ToolExecution -> toolsUsed++
                            else -> Unit
                        }
                        persist(agentStatus(event))
                    }
                )

                OmniMode.SWARM -> executeSwarm(
                    runtime = runtime,
                    resumePrompt = resumePrompt,
                    scope = scope,
                    onEvent = { event ->
                        when (event) {
                            SwarmEvent.PlanningStarted -> entries += AgentConsoleEntry.ThinkingEntry(0)
                            is SwarmEvent.TaskStarted -> entries += AgentConsoleEntry.ToolEntry(
                                toolName = "worker:${event.task.id}",
                                params = event.task.description.take(120),
                                iteration = event.task.priority
                            )
                            is SwarmEvent.WorkerToolUse -> {
                                toolsUsed++
                                entries += AgentConsoleEntry.ToolEntry(
                                    event.toolName,
                                    event.arguments.entries.joinToString(", ") { "${it.key}=${it.value.take(40)}" },
                                    event.task.priority
                                )
                            }
                            is SwarmEvent.WorkerToolResult -> entries += AgentConsoleEntry.ResultEntry(
                                event.toolName,
                                event.output.lineSequence().firstOrNull()?.take(140).orEmpty(),
                                event.isError,
                                event.output
                            )
                            is SwarmEvent.WorkerThinking -> entries += AgentConsoleEntry.ThinkingEntry(event.iteration)
                            is SwarmEvent.WorkerThinkingBlock -> entries += AgentConsoleEntry.DeepThinkingEntry(event.content.take(280))
                            is SwarmEvent.WorkerTokenUsage -> entries += AgentConsoleEntry.TokenEntry(event.totalTokens, event.budget)
                            is SwarmEvent.WorkerPhaseChanged -> entries += AgentConsoleEntry.PhaseEntry(event.phase, event.detail)
                            is SwarmEvent.WorkerStreamChunk -> partial += event.delta
                            is SwarmEvent.Completed -> partial = event.summary
                            is SwarmEvent.Error -> entries += AgentConsoleEntry.ErrorEntry(event.message)
                            is SwarmEvent.TaskFailed -> entries += AgentConsoleEntry.ResultEntry(
                                "worker:${event.task.id}", event.error.take(160), true, event.error
                            )
                            is SwarmEvent.TaskCompleted -> Unit
                            is SwarmEvent.TaskSkipped -> entries += AgentConsoleEntry.ResultEntry(
                                "worker:${event.task.id}", "Skipped: ${event.reason}", true
                            )
                            SwarmEvent.SynthesisStarted -> entries += AgentConsoleEntry.ThinkingEntry(99)
                            is SwarmEvent.PlanCompleted -> Unit
                        }
                        persist(swarmStatus(event))
                    }
                )

                OmniMode.AUTO -> error("AUTO is resolved before execution")
            }

            assistant = assistant.copy(content = result.content, executionRequest = result.executionRequest)
            val finalEntries = entries + AgentConsoleEntry.ReplyEntry()
            runtime.chatRepository.updateRun(rowId, assistant, finalEntries)
            runtime.chatRepository.updateSessionRunStatus(run.sessionId, "Completed")
            runtime.chatRepository.touchSession(run.sessionId, sessionTitle(allMessages, origin))
            onProgress(Progress("Completed", result.content.takeLast(1_500), toolsUsed))
            Outcome(true, result.content)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runtime.chatRepository.updateRun(
                    rowId,
                    assistant.copy(content = partial.ifBlank { "Background run interrupted; recovery checkpoint saved." }),
                    entries.takeLast(500)
                )
            }
            throw cancelled
        } catch (failure: Exception) {
            val message = failure.message ?: "Background execution failed"
            entries += AgentConsoleEntry.ErrorEntry(message)
            runtime.chatRepository.updateRun(
                rowId,
                assistant.copy(content = partial.ifBlank { "Background execution paused: $message" }),
                entries.takeLast(500)
            )
            runtime.chatRepository.updateSessionRunStatus(run.sessionId, "Interrupted")
            Outcome(
                success = false,
                result = partial,
                error = message,
                transientFailure = isTransient(failure, message)
            )
        }
    }

    private data class ExecutionResult(
        val content: String,
        val executionRequest: com.omnidev.workspace.data.model.ExecutionModeRequest? = null
    )

    private suspend fun executeChat(
        runtime: AgentRuntime,
        origin: ChatMessage,
        history: List<ChatMessage>,
        resumePrompt: String,
        onText: suspend (String) -> Unit,
        onEvent: suspend (AgentEvent) -> Unit
    ): ExecutionResult {
        val modelId = runtime.settingsRepository.observeModelIdForRole(ModelRole.CHAT).first()
        val model = ModelRegistry.findModelById(modelId) ?: ModelRegistry.getModelById(modelId)
        val settings = runtime.settingsRepository.observeChatSettings().first()
        var streamed = ""
        val request = CompletionRequest(
            modelId = modelId,
            messages = history + ChatMessage(MessageRole.USER, resumePrompt),
            systemPrompt = "You are Omni. Continue the interrupted in-app conversation from its durable checkpoint. " +
                "Use chat web tools when useful. Never repeat an already-finished external action merely because the process restarted.",
            maxTokens = minOf(model.maxOutputTokens, 8_192),
            enableThinking = runtime.settingsRepository.observeDeepThinking().first() && model.supportsThinking,
            apiKey = runtime.apiKeyRepository.getApiKey(model.provider)
        )
        val result = ChatToolLoop(runtime.toolManager).run(
            base = request,
            disabled = settings.disabledToolNames(),
            originMessageId = origin.messageId,
            complete = { next ->
                streamed = ""
                runtime.completionService.stream(next) { delta ->
                    streamed += delta
                    onText(streamed)
                }
            },
            event = onEvent
        )
        return ExecutionResult(result.content.ifBlank { streamed }, result.request)
    }

    private suspend fun executeAgent(
        runtime: AgentRuntime,
        origin: ChatMessage,
        history: List<ChatMessage>,
        resumePrompt: String,
        scope: String,
        onEvent: suspend (AgentEvent) -> Unit
    ): ExecutionResult {
        val modelId = runtime.settingsRepository.observeModelIdForRole(ModelRole.AGENT).first()
        val settings = runtime.settingsRepository.observeChatSettings().first()
        var final = ""
        var error: String? = null
        runtime.agentPipeline.execute(
            userMessage = resumePrompt,
            conversationHistory = history,
            modelId = modelId,
            scopePath = scope,
            enableDeepThinking = runtime.settingsRepository.observeDeepThinking().first(),
            userAttachments = origin.attachments,
            userContext = runtime.settingsRepository.observeUserPersona().first(),
            disabledToolNames = settings.disabledToolNames(),
            toolAccessMode = settings.toolAccessMode.name
        ).collect { event ->
            when (event) {
                is AgentEvent.FinalAnswer -> final = event.content
                is AgentEvent.Error -> error = event.message
                else -> Unit
            }
            onEvent(event)
        }
        error?.let { throw IllegalStateException(it) }
        if (final.isBlank()) throw IllegalStateException("Agent ended without a final response.")
        return ExecutionResult(final)
    }

    private suspend fun executeSwarm(
        runtime: AgentRuntime,
        resumePrompt: String,
        scope: String,
        onEvent: suspend (SwarmEvent) -> Unit
    ): ExecutionResult {
        val orchestrator = runtime.settingsRepository.observeModelIdForRole(ModelRole.SWARM_ORCHESTRATOR).first()
        val worker = runtime.settingsRepository.observeModelIdForRole(ModelRole.SWARM_WORKER).first()
        var final = ""
        var error: String? = null
        runtime.swarmOrchestrator.execute(
            userMessage = resumePrompt,
            orchestratorModelId = orchestrator,
            workerModelId = worker,
            scopePath = scope,
            enableDeepThinking = runtime.settingsRepository.observeDeepThinking().first(),
            godModeEnabled = runtime.settingsRepository.observeGodMode().first()
        ).collect { event ->
            when (event) {
                is SwarmEvent.Completed -> final = event.summary
                is SwarmEvent.Error -> error = event.message
                else -> Unit
            }
            onEvent(event)
        }
        error?.let { throw IllegalStateException(it) }
        if (final.isBlank()) throw IllegalStateException("Swarm ended without a final response.")
        return ExecutionResult(final)
    }

    private suspend fun resolveScope(runtime: AgentRuntime, run: BackgroundChatRun): String {
        if (run.scopePath.isNotBlank()) return run.scopePath
        if (runtime.settingsRepository.observeGodMode().first()) return "/"
        return runtime.settingsRepository.observeTargetContext().first().orEmpty()
    }

    private fun resolveMode(raw: String, prompt: String): OmniMode {
        val parsed = runCatching { OmniMode.valueOf(raw) }.getOrDefault(OmniMode.AGENT)
        return if (parsed == OmniMode.AUTO) IntentClassifier.classify(prompt) else parsed
    }

    private fun buildResumePrompt(origin: ChatMessage, history: List<ChatMessage>): String = buildString {
        appendLine("Resume this interrupted task from the durable checkpoint after an Android process restart.")
        appendLine("Do not redo completed tool calls or external side effects already evidenced in conversation history.")
        appendLine("Verify existing state first, then continue only unfinished work.")
        appendLine()
        appendLine("Original user request:")
        appendLine(origin.content)
        val partial = history.asReversed().firstOrNull {
            it.role == MessageRole.ASSISTANT && it.timestamp >= origin.timestamp && it.content.isNotBlank()
        }?.content
        if (!partial.isNullOrBlank()) {
            appendLine()
            appendLine("Latest persisted assistant checkpoint:")
            appendLine(partial.takeLast(4_000))
        }
    }

    private fun agentStatus(event: AgentEvent): String = when (event) {
        is AgentEvent.Started -> "Agent started…"
        is AgentEvent.Thinking -> "Thinking • iteration ${event.iteration}"
        is AgentEvent.ThinkingBlock -> "Deep thinking…"
        is AgentEvent.ToolExecution -> "Using ${event.toolName}…"
        is AgentEvent.ToolResult -> if (event.isError) "${event.toolName} returned an error" else "${event.toolName} completed"
        is AgentEvent.TokenUsageUpdate -> "Thinking • ${event.totalTokens} tokens"
        is AgentEvent.StreamChunk -> "Writing response…"
        is AgentEvent.FinalAnswer -> "Finalizing…"
        is AgentEvent.Error -> "Error • ${event.message.take(100)}"
        is AgentEvent.Reflecting -> "Reviewing the answer…"
        is AgentEvent.ContextCompaction -> "Compressing context…"
        is AgentEvent.PhaseChanged -> event.detail?.let { "${event.phase.name} • ${it.take(90)}" } ?: event.phase.name
    }

    private fun swarmStatus(event: SwarmEvent): String = when (event) {
        SwarmEvent.PlanningStarted -> "Swarm is planning…"
        is SwarmEvent.PlanCompleted -> "Plan ready • ${event.tasks.size} sub-tasks"
        is SwarmEvent.TaskStarted -> "Worker ${event.task.id} started…"
        is SwarmEvent.TaskCompleted -> "Worker ${event.task.id} completed"
        is SwarmEvent.TaskFailed -> "Worker ${event.task.id} failed"
        is SwarmEvent.TaskSkipped -> "Worker ${event.task.id} skipped"
        is SwarmEvent.WorkerToolUse -> "${event.task.id} • ${event.toolName}…"
        is SwarmEvent.WorkerToolResult -> "${event.task.id} • ${event.toolName} completed"
        is SwarmEvent.WorkerThinking -> "${event.task.id} • thinking ${event.iteration}"
        is SwarmEvent.WorkerThinkingBlock -> "${event.task.id} • deep thinking…"
        is SwarmEvent.WorkerTokenUsage -> "${event.task.id} • ${event.totalTokens} tokens"
        is SwarmEvent.WorkerPhaseChanged -> "${event.task.id} • ${event.phase}"
        SwarmEvent.SynthesisStarted -> "Synthesizing worker results…"
        is SwarmEvent.WorkerStreamChunk -> "Worker is writing…"
        is SwarmEvent.Completed -> "Finalizing swarm response…"
        is SwarmEvent.Error -> "Swarm error • ${event.message.take(100)}"
    }

    private fun sessionTitle(history: List<ChatMessage>, origin: ChatMessage): String =
        history.firstOrNull { it.role == MessageRole.USER }?.content?.take(50)
            ?.ifBlank { origin.content.take(50) }
            ?: origin.content.take(50).ifBlank { "Conversation" }

    private fun isTransient(error: Throwable, message: String): Boolean {
        if (error is IOException) return true
        val lower = message.lowercase()
        return listOf(
            "timeout", "timed out", "network", "connection", "socket", "dns", "unavailable",
            "temporarily", "429", "502", "503", "504", "rate limit"
        ).any(lower::contains)
    }
}
