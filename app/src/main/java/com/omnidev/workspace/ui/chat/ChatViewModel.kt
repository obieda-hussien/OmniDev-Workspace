package com.omnidev.workspace.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import com.omnidev.workspace.data.model.AttachmentMediaType
import com.omnidev.workspace.data.model.AttachmentMeta
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.ChatRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.tools.FileToolManager
import com.omnidev.workspace.domain.attachment.AttachmentProcessor
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.domain.engine.AgentEvent
import com.omnidev.workspace.domain.engine.AgentPipeline
import com.omnidev.workspace.domain.engine.OmniMode
import com.omnidev.workspace.domain.engine.SwarmEvent
import com.omnidev.workspace.domain.engine.SwarmOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A file the user has selected but not yet sent.
 *
 * @property uri The content URI returned by the file picker.
 * @property displayName The human-readable file name shown in the attachment chip.
 */
data class PendingAttachment(val uri: Uri, val displayName: String)

/**
 * UI state for the Omni-Chat interface.
 */
data class ChatUiState(
    /** The conversation message history. */
    val messages: List<ChatMessage> = emptyList(),
    /** The current text input. */
    val inputText: String = "",
    /** The file-system path used by FileToolManager for scope validation. */
    val targetContext: String? = null,
    /** Human-readable folder name shown in the top bar (e.g. "MyApp"). */
    val targetContextDisplayName: String? = null,
    /** Whether the agent is currently executing. */
    val isProcessing: Boolean = false,
    /** Current agent status text for streaming display. */
    val agentStatus: String? = null,
    /** Error message to display. */
    val errorMessage: String? = null,
    /** Live console entries for the Agent Observability Console ("Glass Brain"). */
    val consoleEntries: List<AgentConsoleEntry> = emptyList(),
    /** Files selected by the user, waiting to be included in the next message. */
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    /** Past sessions for the navigation drawer. */
    val sessions: List<ChatSessionEntity> = emptyList(),
    /** Whether the history drawer is open. */
    val isDrawerOpen: Boolean = false,
    /** The currently active session ID (null = unsaved new session). */
    val currentSessionId: Long? = null,
    /** Partial text from the current streaming response (null = not streaming). */
    val streamingContent: String? = null,
    /** The currently active execution mode (Chat / Agent / Swarm). */
    val activeMode: OmniMode = OmniMode.AGENT,
    /** A privileged action awaiting user approval via [ConfirmationGateDialog]. */
    val pendingConfirmation: PendingConfirmation? = null
)

/**
 * ViewModel for the Omni-Chat interface, managing conversation state
 * and agent pipeline execution.
 *
 * @param settingsRepository User preferences (model IDs, target context, etc.).
 * @param agentPipeline The ReAct agent engine for AGENT mode.
 * @param chatRepository Optional persistence layer for chat sessions/messages.
 * @param attachmentProcessor Optional processor for reading image bytes for vision models.
 * @param completionProvider Direct completion call for CHAT mode (no tools).
 * @param streamingCompletionProvider Optional streaming variant for CHAT mode.
 *        The first parameter is the [CompletionRequest]; the second is a `suspend (String) -> Unit`
 *        callback that receives each text-delta chunk as it arrives from the SSE stream.
 * @param swarmOrchestrator Optional orchestrator engine for SWARM mode.
 * @param fileToolManager Optional reference to the [FileToolManager] for syncing God Mode.
 */
class ChatViewModel(
    private val settingsRepository: SettingsRepository,
    private val agentPipeline: AgentPipeline,
    private val chatRepository: ChatRepository? = null,
    private val attachmentProcessor: AttachmentProcessor? = null,
    private val completionProvider: (suspend (CompletionRequest) -> CompletionResponse)? = null,
    private val streamingCompletionProvider: (suspend (CompletionRequest, suspend (String) -> Unit) -> CompletionResponse)? = null,
    private val swarmOrchestrator: SwarmOrchestrator? = null,
    private val apiKeyRepository: ApiKeyRepository? = null,
    private val fileToolManager: FileToolManager? = null,
    private val autoHealBuildUseCase: com.omnidev.workspace.domain.engine.AutoHealBuildUseCase? = null
) : ViewModel() {

    companion object {
        /** System prompt for CHAT mode — conversational, no tools. */
        private const val CHAT_SYSTEM_PROMPT =
            "You are a helpful coding assistant. Answer questions directly without using tools."
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        loadTargetContext()
        observeSessions()
        observeGodMode()
    }

    private fun loadTargetContext() {
        viewModelScope.launch {
            settingsRepository.observeTargetContext().collect { path ->
                _uiState.update { it.copy(targetContext = path) }
            }
        }
    }

    private fun observeSessions() {
        val repo = chatRepository ?: return
        viewModelScope.launch {
            repo.observeSessions().collect { sessions ->
                _uiState.update { it.copy(sessions = sessions) }
            }
        }
    }

    /** Syncs the God Mode flag from settings into [FileToolManager] in real-time. */
    private fun observeGodMode() {
        val ftm = fileToolManager ?: return
        viewModelScope.launch {
            settingsRepository.observeGodMode().collect { enabled ->
                ftm.godModeEnabled = enabled
            }
        }
    }

    /** Raises a pending confirmation that must be approved by the user before the action executes. */
    fun showConfirmation(confirmation: PendingConfirmation) {
        _uiState.update { it.copy(pendingConfirmation = confirmation) }
    }

    /** Clears the pending confirmation (called after approve or deny). */
    fun clearConfirmation() {
        _uiState.update { it.copy(pendingConfirmation = null) }
    }

    /** Opens or closes the history drawer. */
    fun setDrawerOpen(open: Boolean) {
        _uiState.update { it.copy(isDrawerOpen = open) }
    }

    /** Switches the active execution mode (Chat / Agent / Swarm). */
    fun setMode(mode: OmniMode) {
        _uiState.update { it.copy(activeMode = mode) }
    }

    /**
     * Loads a past session's messages and switches the active context to it.
     */
    fun loadSession(sessionId: Long) {
        viewModelScope.launch {
            val messages = chatRepository?.loadMessages(sessionId) ?: return@launch
            _uiState.update {
                it.copy(
                    currentSessionId = sessionId,
                    messages = messages,
                    isDrawerOpen = false,
                    errorMessage = null,
                    consoleEntries = emptyList()
                )
            }
        }
    }

    /** Clears the current conversation and starts a brand-new (unsaved) session. */
    fun newSession() {
        _uiState.update {
            it.copy(
                currentSessionId = null,
                messages = emptyList(),
                inputText = "",
                pendingAttachments = emptyList(),
                consoleEntries = emptyList(),
                errorMessage = null,
                streamingContent = null,
                isDrawerOpen = false
            )
        }
    }

    /** Toggles the pinned state for the given session. */
    fun togglePinSession(sessionId: Long) {
        viewModelScope.launch { chatRepository?.togglePin(sessionId) }
    }

    /** Renames the given session. */
    fun renameSession(sessionId: Long, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch { chatRepository?.renameSession(sessionId, newTitle.trim()) }
    }

    /** Deletes a session and, if it was the active session, starts a new one. */
    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            chatRepository?.deleteSession(sessionId)
            if (_uiState.value.currentSessionId == sessionId) {
                _uiState.update {
                    it.copy(
                        currentSessionId = null,
                        messages = emptyList(),
                        consoleEntries = emptyList(),
                        errorMessage = null,
                        streamingContent = null
                    )
                }
            }
        }
    }

    /**
     * Updates the text input field.
     */
    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    /**
     * Adds one or more files to the pending attachment list.
     * [uris] and [displayNames] must have the same size.
     */
    fun addAttachments(uris: List<Uri>, displayNames: List<String>) {
        require(uris.size == displayNames.size) {
            "uris and displayNames must have equal size (${uris.size} vs ${displayNames.size})"
        }
        val newAttachments = uris.mapIndexed { i, uri ->
            PendingAttachment(uri = uri, displayName = displayNames[i])
        }
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + newAttachments) }
    }

    /** Removes a single pending attachment by its URI. */
    fun removeAttachment(uri: Uri) {
        _uiState.update {
            it.copy(pendingAttachments = it.pendingAttachments.filter { a -> a.uri != uri })
        }
    }

    /**
     * Sets the Target Context scope path directly (legacy / testing use).
     */
    fun setTargetContext(path: String?) {
        viewModelScope.launch {
            settingsRepository.setTargetContext(path)
            _uiState.update {
                it.copy(
                    targetContext = path,
                    targetContextDisplayName = path?.substringAfterLast('/')?.ifBlank { path }
                )
            }
        }
    }

    /**
     * Handles a URI returned by the SAF ACTION_OPEN_DOCUMENT_TREE picker.
     *
     * Derives:
     * 1. A human-readable display name (last path segment) for the top bar.
     * 2. A filesystem path for FileToolManager scope validation, by converting
     *    the tree document ID (`primary:path/to/dir`) to `/storage/emulated/0/path/to/dir`.
     *
     * Both values are persisted via [SettingsRepository].
     */
    fun setTargetContextFromUri(context: Context, uri: Uri) {
        val treeDocId = DocumentsContract.getTreeDocumentId(uri) ?: return

        // Parse the tree document ID: "primary:AndroidIDEProjects/MyApp"
        val colonIdx = treeDocId.indexOf(':')
        val relativePath = if (colonIdx >= 0) treeDocId.substring(colonIdx + 1) else treeDocId

        // Derive filesystem path — works for primary internal storage
        val volumeRoot = if (treeDocId.startsWith("primary")) {
            "/storage/emulated/0"
        } else {
            // External SD card — the volume name is the part before the colon
            "/storage/${treeDocId.substringBefore(':')}"
        }
        val derivedPath = if (relativePath.isEmpty()) volumeRoot else "$volumeRoot/$relativePath"

        // Last segment of the path is the folder's display name
        val pathSegment = relativePath.substringAfterLast('/')
        val folderName = pathSegment.ifBlank { relativePath }.ifBlank { derivedPath.substringAfterLast('/') }

        viewModelScope.launch {
            settingsRepository.setTargetContextUri(uri.toString(), derivedPath)
            _uiState.update {
                it.copy(
                    targetContext = derivedPath,
                    targetContextDisplayName = folderName
                )
            }
        }
    }

    /**
     * Sends the current input message (and any pending attachments) to the
     * execution engine selected by the active [OmniMode].
     */
    fun sendMessage() {
        val input = _uiState.value.inputText.trim()
        if (input.isEmpty()) return

        val mode = _uiState.value.activeMode

        // CHAT mode does not require a Target Context scope
        val scopePath = _uiState.value.targetContext
        if (mode != OmniMode.CHAT && scopePath == null) {
            _uiState.update { it.copy(errorMessage = "Please set a Target Context before sending messages.") }
            return
        }

        val attachments = _uiState.value.pendingAttachments
        // Append attachment file names to the message content so the model is aware of them.
        val attachmentNote = if (attachments.isNotEmpty()) {
            "\n\n[Attached files: ${attachments.joinToString(", ") { it.displayName }}]"
        } else ""

        val userMessage = ChatMessage(
            role = MessageRole.USER,
            content = input + attachmentNote
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                inputText = "",
                isProcessing = true,
                agentStatus = "Starting ${mode.label}...",
                errorMessage = null,
                consoleEntries = emptyList(), // fresh console for each run
                pendingAttachments = emptyList() // clear after send
            )
        }

        viewModelScope.launch {
            // Ensure the session is persisted before saving any messages
            val sessionId = ensureSession(input)
            chatRepository?.saveMessage(sessionId, userMessage)

            // Resolve base64 image data for vision-capable attachments
            val imageAttachments: List<AttachmentMeta> = attachments
                .mapNotNull { pending ->
                    val uri = pending.uri
                    val base64 = attachmentProcessor?.readImageAsBase64(uri) ?: return@mapNotNull null
                    val mimeType = attachmentProcessor.getMimeType(uri)
                    AttachmentMeta(
                        uri = uri.toString(),
                        mimeType = mimeType,
                        fileName = pending.displayName,
                        sizeBytes = 0L,
                        mediaType = AttachmentMediaType.IMAGE,
                        base64Data = base64
                    )
                }

            when (mode) {
                OmniMode.CHAT -> executeChatMode(input, imageAttachments, sessionId)
                OmniMode.AGENT -> {
                    val scope = scopePath ?: return@launch
                    executeAgentMode(input, imageAttachments, sessionId, scope)
                }
                OmniMode.SWARM -> {
                    val scope = scopePath ?: return@launch
                    executeSwarmMode(input, sessionId, scope)
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    //  MODE: CHAT — Direct Completion (No Tools)
    // ──────────────────────────────────────────────

    /**
     * Sends the prompt directly to [CompletionService] without the ReAct loop or tools.
     * Uses the **Chat Model** preference from [SettingsRepository].
     */
    private suspend fun executeChatMode(
        input: String,
        imageAttachments: List<AttachmentMeta>,
        sessionId: Long
    ) {
        val modelId = settingsRepository
            .observeModelIdForRole(com.omnidev.workspace.data.model.ModelRole.CHAT)
            .first()

        val history = _uiState.value.messages.dropLast(1)

        val request = CompletionRequest(
            modelId = modelId,
            messages = history + ChatMessage(
                role = MessageRole.USER,
                content = input,
                attachments = imageAttachments
            ),
            systemPrompt = CHAT_SYSTEM_PROMPT,
            maxTokens = 4096,
            temperature = 0.7
        )

        // Inject API key
        val model = com.omnidev.workspace.registry.ModelRegistry.findModelById(modelId)
        val apiKey = model?.let { apiKeyRepository?.getApiKey(it.provider) }
        val requestWithKey = request.copy(apiKey = apiKey)

        val response: CompletionResponse
        try {
            response = if (streamingCompletionProvider != null) {
                streamingCompletionProvider.invoke(requestWithKey) { delta ->
                    _uiState.update {
                        it.copy(streamingContent = (it.streamingContent ?: "") + delta)
                    }
                }
            } else if (completionProvider != null) {
                completionProvider.invoke(requestWithKey)
            } else {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = "Chat mode is not available — no completion provider configured."
                    )
                }
                return
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isProcessing = false,
                    agentStatus = null,
                    streamingContent = null,
                    errorMessage = e.message ?: "Chat request failed."
                )
            }
            return
        }

        val assistantMessage = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = response.content
        )
        chatRepository?.saveMessage(sessionId, assistantMessage)
        _uiState.update {
            it.copy(
                messages = it.messages + assistantMessage,
                isProcessing = false,
                agentStatus = null,
                streamingContent = null
            )
        }
    }

    // ──────────────────────────────────────────────
    //  MODE: AGENT — Single ReAct Agent
    // ──────────────────────────────────────────────

    /**
     * Routes the prompt through the [AgentPipeline] ReAct loop with full tool access.
     * Uses the **Agent Model** preference from [SettingsRepository].
     */
    private suspend fun executeAgentMode(
        input: String,
        imageAttachments: List<AttachmentMeta>,
        sessionId: Long,
        scopePath: String
    ) {
        val modelId = settingsRepository
            .observeModelIdForRole(com.omnidev.workspace.data.model.ModelRole.AGENT)
            .first()

        val deepThinking = settingsRepository.observeDeepThinking().first()
        val customPrompt = settingsRepository
            .observeCustomPrompt(SettingsRepository.PromptRole.AGENT)
            .first()

        agentPipeline.execute(
            userMessage = input,
            conversationHistory = _uiState.value.messages.dropLast(1),
            modelId = modelId,
            scopePath = scopePath,
            enableDeepThinking = deepThinking,
            userAttachments = imageAttachments,
            customSystemPrompt = customPrompt
        ).collect { event ->
            handleAgentEvent(event, sessionId)
        }
    }

    // ──────────────────────────────────────────────
    //  MODE: SWARM — Team Agents (Orchestrator → Workers)
    // ──────────────────────────────────────────────

    /**
     * Routes the prompt through the [SwarmOrchestrator]: an Orchestrator model plans,
     * then Worker agents execute each sub-task.
     * Uses the **Swarm Orchestrator Model** for planning and the **Swarm Worker Model** for execution.
     */
    private suspend fun executeSwarmMode(
        input: String,
        sessionId: Long,
        scopePath: String
    ) {
        val orchestrator = swarmOrchestrator
        if (orchestrator == null) {
            _uiState.update {
                it.copy(
                    isProcessing = false,
                    errorMessage = "Swarm mode is not available — no SwarmOrchestrator configured."
                )
            }
            return
        }

        val orchestratorModelId = settingsRepository
            .observeModelIdForRole(com.omnidev.workspace.data.model.ModelRole.SWARM_ORCHESTRATOR)
            .first()
        val workerModelId = settingsRepository
            .observeModelIdForRole(com.omnidev.workspace.data.model.ModelRole.SWARM_WORKER)
            .first()
        val deepThinking = settingsRepository.observeDeepThinking().first()

        orchestrator.execute(
            userMessage = input,
            orchestratorModelId = orchestratorModelId,
            workerModelId = workerModelId,
            scopePath = scopePath,
            enableDeepThinking = deepThinking
        ).collect { event ->
            handleSwarmEvent(event, sessionId)
        }
    }

    // ──────────────────────────────────────────────
    //  Event Handlers
    // ──────────────────────────────────────────────

    /** Maps [AgentEvent]s to UI state updates and console entries. */
    private fun handleAgentEvent(event: AgentEvent, sessionId: Long) {
        when (event) {
            is AgentEvent.Started ->
                _uiState.update { it.copy(agentStatus = "Agent started...") }

            is AgentEvent.Thinking ->
                _uiState.update {
                    it.copy(
                        agentStatus = "Thinking (iteration ${event.iteration})...",
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ThinkingEntry(event.iteration)
                    )
                }

            is AgentEvent.ThinkingBlock ->
                _uiState.update {
                    it.copy(
                        agentStatus = "Deep thinking...",
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.DeepThinkingEntry(event.content)
                    )
                }

            is AgentEvent.ToolExecution -> {
                val params = event.arguments.entries
                    .joinToString(", ") { (k, v) -> "$k=${v.toString().take(40)}" }
                _uiState.update {
                    it.copy(
                        agentStatus = "Executing ${event.toolName}...",
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ToolEntry(event.toolName, params, event.iteration)
                    )
                }
            }

            is AgentEvent.ToolResult -> {
                val snippet = event.output.lines().firstOrNull()?.take(100) ?: ""
                _uiState.update {
                    it.copy(
                        agentStatus = if (event.isError) "Tool error: ${event.toolName}"
                        else "Tool completed: ${event.toolName}",
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ResultEntry(event.toolName, snippet, event.isError)
                    )
                }
            }

            is AgentEvent.TokenUsageUpdate ->
                _uiState.update {
                    it.copy(
                        agentStatus = "Thinking (${event.totalTokens} tokens used)...",
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.TokenEntry(event.totalTokens, event.budget)
                    )
                }

            is AgentEvent.StreamChunk ->
                _uiState.update {
                    it.copy(streamingContent = (it.streamingContent ?: "") + event.delta)
                }

            is AgentEvent.FinalAnswer -> {
                val assistantMessage = ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = event.content
                )
                viewModelScope.launch {
                    chatRepository?.saveMessage(sessionId, assistantMessage)
                }
                _uiState.update {
                    it.copy(
                        messages = it.messages + assistantMessage,
                        isProcessing = false,
                        agentStatus = null,
                        streamingContent = null,
                        consoleEntries = it.consoleEntries + AgentConsoleEntry.ReplyEntry()
                    )
                }
            }

            is AgentEvent.Error -> {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        agentStatus = null,
                        errorMessage = event.message,
                        streamingContent = null,
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ErrorEntry(event.message)
                    )
                }
            }
        }
    }

    /** Maps [SwarmEvent]s to UI state updates and console entries. */
    private fun handleSwarmEvent(event: SwarmEvent, sessionId: Long) {
        when (event) {
            is SwarmEvent.PlanningStarted ->
                _uiState.update {
                    it.copy(
                        agentStatus = "🧠 Planning sub-tasks...",
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ThinkingEntry(0)
                    )
                }

            is SwarmEvent.PlanCompleted ->
                _uiState.update {
                    it.copy(
                        agentStatus = "Plan: ${event.tasks.size} sub-tasks",
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.DeepThinkingEntry(
                                "Plan completed — ${event.tasks.size} sub-tasks:\n" +
                                    event.tasks.joinToString("\n") { "  • [${it.id}] ${it.description}" }
                            )
                    )
                }

            is SwarmEvent.TaskStarted ->
                _uiState.update {
                    it.copy(
                        agentStatus = "⚙️ Worker: ${event.task.id}",
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ToolEntry(
                                toolName = "worker:${event.task.id}",
                                params = event.task.description.take(80),
                                iteration = event.task.priority
                            )
                    )
                }

            is SwarmEvent.TaskCompleted -> {
                val snippet = event.result.lines().firstOrNull()?.take(100) ?: ""
                _uiState.update {
                    it.copy(
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ResultEntry(event.task.id, snippet, isError = false)
                    )
                }
            }

            is SwarmEvent.TaskFailed ->
                _uiState.update {
                    it.copy(
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ResultEntry(event.task.id, event.error, isError = true)
                    )
                }

            is SwarmEvent.TaskSkipped ->
                _uiState.update {
                    it.copy(
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ResultEntry(event.task.id, "Skipped: ${event.reason}", isError = true)
                    )
                }

            is SwarmEvent.WorkerToolUse ->
                _uiState.update {
                    val params = event.arguments.entries
                        .joinToString(", ") { (k, v) -> "$k=${v.take(40)}" }
                    it.copy(
                        agentStatus = "Worker ${event.task.id}: ${event.toolName}",
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ToolEntry(event.toolName, params, event.task.priority)
                    )
                }

            is SwarmEvent.SynthesisStarted ->
                _uiState.update {
                    it.copy(
                        agentStatus = "🔗 Synthesizing results...",
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ThinkingEntry(99)
                    )
                }

            is SwarmEvent.Completed -> {
                val assistantMessage = ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = event.summary
                )
                viewModelScope.launch {
                    chatRepository?.saveMessage(sessionId, assistantMessage)
                }
                _uiState.update {
                    it.copy(
                        messages = it.messages + assistantMessage,
                        isProcessing = false,
                        agentStatus = null,
                        streamingContent = null,
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ReplyEntry()
                    )
                }
            }

            is SwarmEvent.Error ->
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        agentStatus = null,
                        errorMessage = event.message,
                        streamingContent = null,
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ErrorEntry(event.message)
                    )
                }
        }
    }

    /**
     * Returns the current session ID, creating a new session if none exists.
     * When [chatRepository] is null (running without DB), returns -1L — all
     * subsequent `chatRepository?.saveMessage(...)` calls are no-ops due to null-safety.
     */
    private suspend fun ensureSession(firstMessage: String): Long {
        val existing = _uiState.value.currentSessionId
        if (existing != null) return existing

        val title = firstMessage.take(50).ifBlank { "New conversation" }
        val newId = chatRepository?.createSession(title) ?: -1L
        _uiState.update { it.copy(currentSessionId = newId) }
        return newId
    }

    /**
     * Clears the error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Executes an autonomous "Build → Fix → Retry" loop using the [AutoHealBuildUseCase].
     *
     * @param buildCommand The Gradle build command (default: `./gradlew assembleDebug`).
     * @param maxRetries Maximum repair attempts (default: 5).
     */
    fun runAutoHealBuild(
        buildCommand: String = "./gradlew assembleDebug",
        maxRetries: Int = 5
    ) {
        val scopePath = _uiState.value.targetContext
        if (scopePath == null) {
            _uiState.update { it.copy(errorMessage = "Please set a Target Context before running Auto-Heal Build.") }
            return
        }

        _uiState.update {
            it.copy(
                isProcessing = true,
                agentStatus = "🔨 Auto-Heal Build starting...",
                consoleEntries = emptyList(),
                errorMessage = null
            )
        }

        viewModelScope.launch {
            val useCase = autoHealBuildUseCase
            if (useCase == null) {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = "Auto-Heal Build is not available — missing dependencies."
                    )
                }
                return@launch
            }

            useCase.execute(
                scopePath = scopePath,
                buildCommand = buildCommand,
                maxRetries = maxRetries
            ).collect { event ->
                when (event) {
                    is com.omnidev.workspace.domain.engine.AutoHealBuildUseCase.BuildEvent.BuildAttempt ->
                        _uiState.update {
                            it.copy(
                                agentStatus = "🔨 Build attempt ${event.attempt}/${event.maxRetries}...",
                                consoleEntries = it.consoleEntries +
                                    AgentConsoleEntry.ThinkingEntry(event.attempt)
                            )
                        }

                    is com.omnidev.workspace.domain.engine.AutoHealBuildUseCase.BuildEvent.BuildSuccess -> {
                        val msg = ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = "✅ Build Successful on attempt ${event.attempt}!\n\n${event.output.take(500)}"
                        )
                        val sessionId = _uiState.value.currentSessionId ?: ensureSession("Auto-Heal Build")
                        chatRepository?.saveMessage(sessionId, msg)
                        _uiState.update {
                            it.copy(
                                messages = it.messages + msg,
                                isProcessing = false,
                                agentStatus = null,
                                consoleEntries = it.consoleEntries +
                                    AgentConsoleEntry.ReplyEntry()
                            )
                        }
                    }

                    is com.omnidev.workspace.domain.engine.AutoHealBuildUseCase.BuildEvent.BuildFailed ->
                        _uiState.update {
                            it.copy(
                                agentStatus = "❌ Build failed (attempt ${event.attempt}), analyzing errors...",
                                consoleEntries = it.consoleEntries +
                                    AgentConsoleEntry.ErrorEntry("Build failed: ${event.errors.take(200)}")
                            )
                        }

                    is com.omnidev.workspace.domain.engine.AutoHealBuildUseCase.BuildEvent.FixAttempt ->
                        _uiState.update {
                            it.copy(
                                agentStatus = "🔧 Agent applying fix (attempt ${event.attempt})...",
                                consoleEntries = it.consoleEntries +
                                    AgentConsoleEntry.ToolEntry("auto_fix", "Fixing build errors", event.attempt)
                            )
                        }

                    is com.omnidev.workspace.domain.engine.AutoHealBuildUseCase.BuildEvent.FixApplied ->
                        _uiState.update {
                            it.copy(
                                consoleEntries = it.consoleEntries +
                                    AgentConsoleEntry.ResultEntry("auto_fix", event.fixSummary.take(100), isError = false)
                            )
                        }

                    is com.omnidev.workspace.domain.engine.AutoHealBuildUseCase.BuildEvent.FixFailed ->
                        _uiState.update {
                            it.copy(
                                consoleEntries = it.consoleEntries +
                                    AgentConsoleEntry.ResultEntry("auto_fix", event.error.take(100), isError = true)
                            )
                        }

                    is com.omnidev.workspace.domain.engine.AutoHealBuildUseCase.BuildEvent.LoopExhausted -> {
                        val msg = ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = "❌ Auto-Heal Build exhausted all ${event.totalAttempts} attempts. " +
                                "Manual intervention is required."
                        )
                        val sessionId = _uiState.value.currentSessionId ?: ensureSession("Auto-Heal Build")
                        chatRepository?.saveMessage(sessionId, msg)
                        _uiState.update {
                            it.copy(
                                messages = it.messages + msg,
                                isProcessing = false,
                                agentStatus = null,
                                consoleEntries = it.consoleEntries +
                                    AgentConsoleEntry.ErrorEntry("Build loop exhausted after ${event.totalAttempts} attempts.")
                            )
                        }
                    }

                    is com.omnidev.workspace.domain.engine.AutoHealBuildUseCase.BuildEvent.AgentProgress ->
                        handleAgentEvent(event.event, _uiState.value.currentSessionId ?: -1L)
                }
            }
        }
    }
}
