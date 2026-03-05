package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.tools.ToolManager
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first

/**
 * Autonomous "Build → Fix → Retry" loop that wraps the Agent Pipeline.
 *
 * Workflow:
 * 1. Execute `./gradlew assembleDebug` (or user-specified build command).
 * 2. If exit code == 0: emit success.
 * 3. If exit code != 0: extract stderr/errors, construct a hidden repair prompt
 *    to the LLM, let the Agent patch files, then retry.
 * 4. Repeat up to [maxRetries] times.
 *
 * @param agentPipeline The ReAct agent for applying fixes.
 * @param settingsRepository For resolving the user's preferred Agent model.
 * @param apiKeyRepository For injecting API keys.
 * @param toolManager For executing the build command.
 */
class AutoHealBuildUseCase(
    private val agentPipeline: AgentPipeline,
    private val settingsRepository: SettingsRepository,
    private val apiKeyRepository: ApiKeyRepository? = null,
    private val toolManager: ToolManager
) {

    companion object {
        /** Maximum characters of build error output to include in the LLM repair prompt. */
        private const val MAX_ERROR_CONTEXT_LENGTH = 4000
        private const val DEFAULT_MAX_RETRIES = 5
        private const val DEFAULT_BUILD_COMMAND = "./gradlew assembleDebug"
    }

    /**
     * Events emitted during the auto-heal build loop.
     */
    sealed class BuildEvent {
        data class BuildAttempt(val attempt: Int, val maxRetries: Int) : BuildEvent()
        data class BuildSuccess(val attempt: Int, val output: String) : BuildEvent()
        data class BuildFailed(val attempt: Int, val errors: String) : BuildEvent()
        data class FixAttempt(val attempt: Int) : BuildEvent()
        data class FixApplied(val attempt: Int, val fixSummary: String) : BuildEvent()
        data class FixFailed(val attempt: Int, val error: String) : BuildEvent()
        data class LoopExhausted(val totalAttempts: Int) : BuildEvent()
        data class AgentProgress(val event: AgentEvent) : BuildEvent()
    }

    /**
     * Executes the auto-heal build loop.
     *
     * @param scopePath Target Context directory where the build runs.
     * @param buildCommand The Gradle build command (default: `./gradlew assembleDebug`).
     * @param maxRetries Maximum repair attempts (default: 5).
     * @return A [Flow] of [BuildEvent]s for real-time UI updates.
     */
    fun execute(
        scopePath: String,
        buildCommand: String = DEFAULT_BUILD_COMMAND,
        maxRetries: Int = DEFAULT_MAX_RETRIES
    ): Flow<BuildEvent> = channelFlow {
        val modelId = settingsRepository
            .observeModelIdForRole(ModelRole.AGENT)
            .first()

        for (attempt in 1..maxRetries) {
            send(BuildEvent.BuildAttempt(attempt, maxRetries))

            // Execute the build command
            val buildResult = try {
                toolManager.executeTool(
                    name = "advanced_terminal",
                    arguments = mapOf(
                        "command" to buildCommand,
                        "workingDirectory" to scopePath
                    ),
                    scopePath = scopePath
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(BuildEvent.BuildFailed(attempt, "Build execution error: ${e.message}"))
                continue
            }

            // Check if build succeeded
            if (!buildResult.isError && buildResult.output.contains("BUILD SUCCESSFUL")) {
                send(BuildEvent.BuildSuccess(attempt, buildResult.output))
                return@channelFlow
            }

            // Build failed — extract errors
            val errors = buildResult.output
            send(BuildEvent.BuildFailed(attempt, errors))

            if (attempt >= maxRetries) {
                send(BuildEvent.LoopExhausted(attempt))
                return@channelFlow
            }

            // Ask the Agent to fix the errors
            send(BuildEvent.FixAttempt(attempt))

            val fixPrompt = buildString {
                appendLine("The build failed with the following errors:")
                appendLine("```")
                // Truncate very long error output to stay within context limits
                appendLine(errors.takeLast(MAX_ERROR_CONTEXT_LENGTH))
                appendLine("```")
                appendLine()
                appendLine("Please analyze the compilation errors above and use the `patch_file_content` tool " +
                    "to fix the bugs in the respective files. Focus on the most critical errors first. " +
                    "After applying fixes, reply with a brief summary of what you changed.")
            }

            var fixSummary = ""
            var fixFailed = false

            try {
                agentPipeline.execute(
                    userMessage = fixPrompt,
                    modelId = modelId,
                    scopePath = scopePath,
                    enableDeepThinking = false
                ).collect { event ->
                    send(BuildEvent.AgentProgress(event))
                    when (event) {
                        is AgentEvent.FinalAnswer -> fixSummary = event.content
                        is AgentEvent.Error -> {
                            fixFailed = true
                            fixSummary = event.message
                        }
                        else -> { /* forward other events */ }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fixFailed = true
                fixSummary = e.message ?: "Unknown fix error"
            }

            if (fixFailed) {
                send(BuildEvent.FixFailed(attempt, fixSummary))
            } else {
                send(BuildEvent.FixApplied(attempt, fixSummary))
            }
        }
    }
}
