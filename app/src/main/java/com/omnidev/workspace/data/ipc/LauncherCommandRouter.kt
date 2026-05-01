package com.omnidev.workspace.data.ipc

import android.content.Context
import android.util.Log
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.data.network.CompletionService
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.tools.AgentBrainTools
import com.omnidev.workspace.data.tools.BuildDoctorTools
import com.omnidev.workspace.data.tools.CompositeToolManager
import com.omnidev.workspace.data.tools.DiscordPublisherTool
import com.omnidev.workspace.data.tools.EnvironmentSetupManager
import com.omnidev.workspace.data.tools.FileToolManager
import com.omnidev.workspace.data.tools.GodEyeProfilerTool
import com.omnidev.workspace.data.tools.HardwareToggleTool
import com.omnidev.workspace.data.tools.HeadlessBrowserManager
import com.omnidev.workspace.data.tools.MemoryManager
import com.omnidev.workspace.data.tools.NotionPublisherTool
import com.omnidev.workspace.data.tools.RepoContextTools
import com.omnidev.workspace.data.tools.RollbackTools
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.VectorMemoryManager
import com.omnidev.workspace.domain.engine.AgentConfig
import com.omnidev.workspace.domain.engine.AgentEvent
import com.omnidev.workspace.domain.engine.AgentPipeline
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Domain router for launcher-originated IPC commands.
 *
 * Keeps OmniCoreService thin by owning:
 * - command string routing
 * - EXECUTE_TOOL JSON parsing
 * - silent AgentPipeline execution
 */
class LauncherCommandRouter(
    context: Context
) {
    companion object {
        private const val TAG = "LauncherCommandRouter"
        const val STATUS_IDLE = 1
        const val STATUS_RUNNING = 2
    }

    private val appContext = context.applicationContext
    private val settingsRepository = SettingsRepository(appContext)
    private val apiKeyRepository = ApiKeyRepository(appContext)
    private val database = OmniDevDatabase.getInstance(appContext)
    private val memoryManager = MemoryManager(database.knowledgeDao())
    private val completionService = CompletionService()
    private val fileToolManager = FileToolManager()
    // ── Agent Brain 2.0: مراجع المحركات المُهيَّأة في OmniDevApp ──
    private val omniApp = com.omnidev.workspace.OmniDevApp.instance
    private val agentBrainTools = AgentBrainTools(
        reflexion = omniApp.reflexionEngine,
        episodic = omniApp.episodicMemoryStore
    )
    private val rollbackTools = RollbackTools(omniApp.rollbackManager)
    private val repoContextTools = RepoContextTools(omniApp.repoIndexer, omniApp.repoContextEngine)
    private val buildDoctorTools = BuildDoctorTools(omniApp.buildDoctorPro)

    private val toolManager = CompositeToolManager(
        fileToolManager = fileToolManager,
        memoryManager = memoryManager,
        context = appContext,
        environmentSetupManager = EnvironmentSetupManager,
        settingsRepository = settingsRepository,
        godEyeProfilerTool = GodEyeProfilerTool(appContext, ShizukuCommandTool),
        discordPublisherTool = DiscordPublisherTool(settingsRepository),
        notionPublisherTool = NotionPublisherTool(settingsRepository),
        vectorMemoryManager = VectorMemoryManager(database.knowledgeDao()),
        apiKeyRepository = apiKeyRepository,
        headlessBrowserManager = HeadlessBrowserManager(appContext),
        agentBrainTools = agentBrainTools,
        rollbackTools = rollbackTools,
        repoContextTools = repoContextTools,
        buildDoctorTools = buildDoctorTools
    )
    private val agentPipeline = AgentPipeline(
        toolManager = toolManager,
            mcpRegistry = com.omnidev.workspace.OmniDevApp.instance.mcpRegistry,
        completionProvider = completionService::invoke,
        streamingCompletionProvider = { req, onChunk -> completionService.stream(req, onChunk) },
        config = AgentConfig.THOROUGH,
        apiKeyRepository = apiKeyRepository,
        memoryManager = memoryManager,
        smartLearningBridge = omniApp.smartLearningBridge
    )

    @Volatile
    private var systemStatus: Int = STATUS_IDLE

    private data class AgentRuntimeConfig(
        val modelId: String,
        val scopePath: String,
        val deepThinking: Boolean,
        val userPersona: String?
    )

    fun getSystemStatus(): Int = systemStatus

    suspend fun executeSystemCommand(command: String, contextData: String?) {
        systemStatus = STATUS_RUNNING
        try {
            when (command.trim().uppercase()) {
                "TURN_ON_WIFI" -> HardwareToggleTool.execute("wifi", "true")
                "TURN_OFF_WIFI" -> HardwareToggleTool.execute("wifi", "false")
                "TURN_ON_BLUETOOTH" -> HardwareToggleTool.execute("bluetooth", "true")
                "TURN_OFF_BLUETOOTH" -> HardwareToggleTool.execute("bluetooth", "false")
                "EXECUTE_TOOL" -> executeToolFromJson(contextData)
                else -> Log.w(TAG, "Unknown launcher command: $command")
            }
        } finally {
            systemStatus = STATUS_IDLE
        }
    }

    suspend fun askAgentSilent(prompt: String): String {
        systemStatus = STATUS_RUNNING
        return try {
            val runtimeConfig = loadAgentRuntimeConfig()
            var final = ""
            agentPipeline.execute(
                userMessage = prompt,
                modelId = runtimeConfig.modelId,
                scopePath = runtimeConfig.scopePath,
                enableDeepThinking = runtimeConfig.deepThinking,
                customSystemPrompt = null,
                userContext = runtimeConfig.userPersona
            ).collect { event ->
                when (event) {
                    is AgentEvent.FinalAnswer -> final = event.content
                    is AgentEvent.Error -> final = "ERROR: ${event.message}"
                    else -> Unit
                }
            }
            final
        } finally {
            systemStatus = STATUS_IDLE
        }
    }

    suspend fun streamAgentResponse(
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        systemStatus = STATUS_RUNNING
        try {
            val runtimeConfig = loadAgentRuntimeConfig()
            var final = ""
            agentPipeline.execute(
                userMessage = prompt,
                modelId = runtimeConfig.modelId,
                scopePath = runtimeConfig.scopePath,
                enableDeepThinking = runtimeConfig.deepThinking,
                customSystemPrompt = null,
                userContext = runtimeConfig.userPersona
            ).collect { event ->
                when (event) {
                    is AgentEvent.StreamChunk -> onToken(event.delta)
                    is AgentEvent.FinalAnswer -> {
                        final = event.content
                        onComplete(final)
                    }
                    is AgentEvent.Error -> onError(event.message)
                    else -> Unit
                }
            }
        } finally {
            systemStatus = STATUS_IDLE
        }
    }

    private suspend fun executeToolFromJson(contextData: String?) {
        val jsonText = contextData?.trim().orEmpty()
        if (jsonText.isBlank()) {
            Log.w(TAG, "EXECUTE_TOOL missing contextData JSON")
            return
        }

        val obj = JSONObject(jsonText)
        val toolName = obj.optString("tool_name").ifBlank { obj.optString("name") }
        if (toolName.isBlank()) {
            Log.w(TAG, "EXECUTE_TOOL JSON missing tool_name/name")
            return
        }

        val argsObj = obj.optJSONObject("args") ?: JSONObject()
        val argsMap = mutableMapOf<String, String>()
        argsObj.keys().forEach { key ->
            argsMap[key] = argsObj.opt(key)?.toString().orEmpty()
        }

        val scopePath = obj.optString("scope_path").ifBlank {
            settingsRepository.observeTargetContext().first().orEmpty()
        }

        val result = toolManager.executeTool(
            name = toolName,
            arguments = argsMap,
            scopePath = scopePath
        )
        if (result.isError) {
            Log.w(TAG, "EXECUTE_TOOL failed for $toolName: ${result.output}")
        }
    }

    private suspend fun loadAgentRuntimeConfig(): AgentRuntimeConfig {
        val modelId = settingsRepository.observeModelIdForRole(ModelRole.AGENT).first()
        val scopePath = settingsRepository.observeTargetContext().first().orEmpty()
        val deepThinking = settingsRepository.observeDeepThinking().first()
        val userPersona = settingsRepository.observeUserPersona().first()
        return AgentRuntimeConfig(
            modelId = modelId,
            scopePath = scopePath,
            deepThinking = deepThinking,
            userPersona = userPersona
        )
    }
}
