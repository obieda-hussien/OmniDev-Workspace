package com.omnidev.workspace.domain.engine

import android.content.Context
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.domain.engine.completion.CompletionReport
import com.omnidev.workspace.domain.engine.completion.RunIdentity
import com.omnidev.workspace.data.tools.ScheduledTaskChatLogger
import com.omnidev.workspace.data.tools.TaskSchedulerTool
import com.omnidev.workspace.ui.chat.AgentConsoleEntry
import com.omnidev.workspace.ui.chat.consoleEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class ScheduledTaskExecutor(private val context: Context) {
    suspend fun execute(task: TaskSchedulerTool.ScheduledTask): TaskSchedulerTool.ExecutionSummary {
        val runtime = AgentRuntime(context)
        runtime.fileToolManager.godModeEnabled = runtime.settingsRepository.observeGodMode().first()
        val logger = ScheduledTaskChatLogger(runtime.database.chatSessionDao(), runtime.database.chatMessageDao())
        val session = logger.sessionIdFor(task)
        runtime.toolManager.currentSessionId = session
        val row = logger.start(task, session)
        val started = System.currentTimeMillis()
        var lastSaved = started
        var result = ""
        var error: String? = null
        var completionReport: CompletionReport? = null
        var iterations = 0
        val tools = mutableListOf<String>()
        val entries = mutableListOf<AgentConsoleEntry>()
        try {
            val dependencies = task.dependsOn.mapNotNull { id ->
                TaskSchedulerTool.getTaskById(id)?.let { "${it.name}: ${it.lastResult.orEmpty()}" }
            }.joinToString("\n")
            runtime.agentPipeline.execute(
                userMessage = task.prompt + if (dependencies.isBlank()) "" else "\n\nDependency results:\n$dependencies",
                modelId = runtime.settingsRepository.observeModelIdForRole(ModelRole.AGENT).first(),
                scopePath = runtime.settingsRepository.observeTargetContext().first().orEmpty(),
                runIdentity = RunIdentity(sessionId = session, forceFinishProtocol = true)
            ).collect { event ->
                event.consoleEntry()?.let { entries.add(it) }
                when (event) {
                    is AgentEvent.StreamChunk -> result += event.delta
                    is AgentEvent.FinalAnswer -> {
                        result = event.content
                        completionReport = event.completion
                        iterations = event.totalIterations
                    }
                    is AgentEvent.Error -> error = event.message
                    is AgentEvent.ToolExecution -> tools.add(event.toolName)
                    else -> Unit
                }
                if (System.currentTimeMillis() - lastSaved >= 750) {
                    logger.progress(row, result.ifBlank { "Task running…" }, entries)
                    lastSaved = System.currentTimeMillis()
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { logger.progress(row, "Run interrupted.\n$result", entries) }
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Task execution failed"
        }
        val verdict = ScheduledRunVerification.evaluate(result, error, completionReport)
        val success = verdict.succeeded
        if (!success && error == null) error = verdict.reason
        val output = if (success) result else "${error ?: "Execution was not verified"}\n$result"
        logger.progress(row, output, entries)
        runtime.chatRepository.touchSession(session, task.name)
        return TaskSchedulerTool.ExecutionSummary(task.id, task.name, started, System.currentTimeMillis(),
            tools.size, tools.toList(), output, success, iterations, error)
    }
}
