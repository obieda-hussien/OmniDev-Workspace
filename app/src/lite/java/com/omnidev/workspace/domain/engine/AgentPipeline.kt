package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.brain.EpisodeOutcome
import com.omnidev.workspace.data.model.AttachmentMeta
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ModelTier
import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.model.ToolCallResult
import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.CompositeToolManager
import com.omnidev.workspace.data.tools.ToolManager
import com.omnidev.workspace.data.tools.orchestration.ToolOrchestrator
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeout
import kotlin.math.min

/** Compatibility boundary; no autonomous engine is compiled into Lite. */
@Suppress("UNUSED_PARAMETER")
class AgentPipeline(
    private val toolManager: ToolManager,
    private val mcpRegistry: com.omnidev.workspace.data.mcp.McpRegistry? = null,
    private val completionProvider: suspend (CompletionRequest) -> CompletionResponse,
    private val streamingCompletionProvider: (suspend (CompletionRequest, suspend (String) -> Unit) -> CompletionResponse)? = null,
    private val config: AgentConfig = AgentConfig(),
    private val apiKeyRepository: com.omnidev.workspace.data.repository.ApiKeyRepository? = null,
    private val memoryManager: com.omnidev.workspace.data.tools.MemoryManager? = null,
    private val smartLearningBridge: com.omnidev.workspace.data.brain.SmartLearningBridge? = null,
    private val toolOrchestrator: ToolOrchestrator = ToolOrchestrator(),
    private val analyticsRepository: com.omnidev.workspace.data.repository.AnalyticsRepository? = null,
    private val toolEligibility: ((String) -> String?)? = null,
    private val toolCallEligibility: ((ToolCall) -> String?)? = null
) {
    fun execute(
        userMessage: String,
        conversationHistory: List<ChatMessage> = emptyList(),
        modelId: String,
        scopePath: String,
        enableDeepThinking: Boolean = false,
        userAttachments: List<AttachmentMeta> = emptyList(),
        customSystemPrompt: String? = null,
        workerPersona: String? = null,
        userContext: String? = null,
        disabledToolNames: Set<String> = emptySet(),
        toolAccessMode: String = "AUTO",
        additionalToolDomains: Set<IntentClassifier.ToolDomain> = emptySet(),
        preferredToolNames: Set<String> = emptySet(),
        steering: RunSteering? = null,
        steeringRevision: Long? = null,
        mentionFocus: MentionFocus = MentionFocus.parse(userMessage)
    ): Flow<AgentEvent> = kotlinx.coroutines.flow.flowOf(AgentEvent.Error("Agent mode is not included in this build."))
}
