package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.tools.ToolManager
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** No planner or workers in Lite/Standard. */
@Suppress("UNUSED_PARAMETER")
class SwarmOrchestrator(
    private val toolManager: ToolManager,
    private val completionProvider: suspend (CompletionRequest) -> CompletionResponse,
    private val apiKeyRepository: com.omnidev.workspace.data.repository.ApiKeyRepository? = null,
    private val memoryManager: com.omnidev.workspace.data.tools.MemoryManager? = null,
    private val streamingCompletionProvider: (suspend (CompletionRequest, suspend (String) -> Unit) -> CompletionResponse)? = null,
    private val smartLearningBridge: com.omnidev.workspace.data.brain.SmartLearningBridge? = null,
    private val analyticsRepository: com.omnidev.workspace.data.repository.AnalyticsRepository? = null
) {
    fun execute(
        userMessage: String,
        orchestratorModelId: String,
        workerModelId: String,
        coordinatorAuto: Boolean = false,
        workerAuto: Boolean = false,
        scopePath: String,
        enableDeepThinking: Boolean = false,
        godModeEnabled: Boolean = false,
        steering: RunSteering? = null,
        mentionFocus: MentionFocus = MentionFocus.parse(userMessage),
        disabledToolNames: Set<String> = emptySet(),
        toolAccessMode: String = "ON_DEMAND",
        userContext: String = ""
    ): Flow<SwarmEvent> = kotlinx.coroutines.flow.flowOf(SwarmEvent.Error("Team mode is not included in this build."))
}
