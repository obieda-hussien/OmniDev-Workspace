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
import com.omnidev.workspace.data.repository.AnalyticsRepository
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.ChatRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.tools.CompositeToolManager
import com.omnidev.workspace.data.tools.FileToolManager
import com.omnidev.workspace.domain.attachment.AttachmentProcessor
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.domain.engine.AgentExecutionPhase
import com.omnidev.workspace.domain.engine.AgentEvent
import com.omnidev.workspace.domain.engine.AgentPipeline
import com.omnidev.workspace.domain.engine.IntentClassifier
import com.omnidev.workspace.domain.engine.OmniMode
import com.omnidev.workspace.domain.engine.SwarmEvent
import com.omnidev.workspace.domain.engine.SwarmOrchestrator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

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
    /** Live console entries accumulating during the current agent run. */
    val consoleEntries: List<AgentConsoleEntry> = emptyList(),
    /**
     * Per-message agent console entries, keyed by message timestamp.
     * Populated for ASSISTANT messages that went through the agent/swarm pipeline.
     * Restored from the database when loading a past session.
     */
    val messageConsoleEntries: Map<Long, List<AgentConsoleEntry>> = emptyMap(),
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
    /** The currently active execution mode (Chat / Agent / Swarm). AUTO is used by the floating overlay only. */
    val activeMode: OmniMode = OmniMode.AGENT,
    /** A privileged action awaiting user approval via [ConfirmationGateDialog]. */
    val pendingConfirmation: PendingConfirmation? = null,
    /** Whether God Mode is enabled — hides scope selection when true. */
    val isGodModeEnabled: Boolean = false,
    /** When non-null, the user has activated a threaded reply to this message. */
    val replyingTo: ChatMessage? = null
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
 * @param compositeToolManager Optional reference to [CompositeToolManager] used to propagate the
 *   current session ID so the [search_messages] tool can scope database queries.
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
    private val autoHealBuildUseCase: com.omnidev.workspace.domain.engine.AutoHealBuildUseCase? = null,
    private val analyticsRepository: AnalyticsRepository? = null,
    private val compositeToolManager: CompositeToolManager? = null
) : ViewModel() {

    companion object {
        /**
         * Fallback system prompt for CHAT mode when no custom prompt has been saved.
         * The user can override this from Settings → System Prompt Studio → Chat tab.
         */
        private const val CHAT_SYSTEM_PROMPT =
            "You are a helpful, concise assistant. Answer questions directly. If the user asks you to write or edit code, be precise and professional."
        private const val CHECKPOINT_CONSOLE_TAIL_SIZE = 8
        private const val CHECKPOINT_DEEP_THINKING_PREVIEW_CHARS = 120
        private const val CHECKPOINT_ERROR_PREVIEW_CHARS = 160
        private const val CHECKPOINT_TOOL_PARAMS_PREVIEW_CHARS = 140
        private const val USER_STOPPED_MESSAGE = "⏹ Run stopped by user."
        private const val STATUS_USER_STOPPED = "Stopped by user"
        private const val STATUS_INTERRUPTED = "Stopped by guard/timeout"
        private const val STATUS_COMPLETED = "Completed"
        private val CHECKPOINT_SECRET_ASSIGNMENT_REGEX =
            Regex("(?i)(\\b(?:api_?key|key|token|secret|password|otp)\\b)\\s*(?:=|:)\\s*(?:\"[^\"]+\"|[^\\s,&\"']+)")
        private val CHECKPOINT_BEARER_REGEX =
            Regex("(?i)(\\bAuthorization\\b\\s*:\\s*Bearer\\s+|\\bbearer\\b\\s+)([^\\s,;]+)")
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Tracks the currently running agent/chat/swarm coroutine Job so it can be cancelled. */
    @Volatile private var currentAgentJob: Job? = null

    init {
        loadTargetContext()
        observeSessions()
        observeGodMode()
        wireFileConfirmationGate()
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

    /** Syncs the God Mode flag from settings into [FileToolManager] and UI state in real-time. */
    private fun observeGodMode() {
        viewModelScope.launch {
            settingsRepository.observeGodMode().collect { enabled ->
                fileToolManager?.let { it.godModeEnabled = enabled }
                _uiState.update { it.copy(isGodModeEnabled = enabled) }
            }
        }
    }


    /**
     * Wires [FileToolManager.confirmationGate] so that `patch_file_content`, `create_file`,
     * and `delete_file` suspend and show a [ConfirmationGateDialog] with a visual diff
     * before executing.
     *
     * Uses a [CompletableDeferred] to bridge the coroutine suspension point in
     * [FileToolManager] to the Compose-driven confirmation dialog in [ChatScreen].
     */
    private fun wireFileConfirmationGate() {
        val ftm = fileToolManager ?: return
        ftm.confirmationGate = { preview, diffContent ->
            val deferred = CompletableDeferred<Boolean>()
            val confirmationType = if (diffContent != null)
                ConfirmationType.GOD_MODE_FILE_PATCH
            else
                ConfirmationType.GOD_MODE_FILE_DELETE

            showConfirmation(
                PendingConfirmation(
                    id = UUID.randomUUID().toString(),
                    type = confirmationType,
                    preview = preview,
                    diffContent = diffContent,
                    onApprove = { deferred.complete(true) },
                    onDeny = { deferred.complete(false) }
                )
            )
            deferred.await()
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
            val (messages, consoleMap) = chatRepository?.loadMessages(sessionId) ?: return@launch
            compositeToolManager?.currentSessionId = sessionId
            _uiState.update {
                it.copy(
                    currentSessionId = sessionId,
                    messages = messages,
                    messageConsoleEntries = consoleMap,
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
                messageConsoleEntries = emptyMap(),
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
                        messageConsoleEntries = emptyMap(),
                        errorMessage = null,
                        streamingContent = null
                    )
                }
            }
        }
    }

    /** Deletes all sessions and resets to a new unsaved session. */
    fun deleteAllSessions() {
        viewModelScope.launch {
            chatRepository?.deleteAllSessions()
            _uiState.update {
                it.copy(
                    currentSessionId = null,
                    messages = emptyList(),
                    consoleEntries = emptyList(),
                    messageConsoleEntries = emptyMap(),
                    errorMessage = null,
                    streamingContent = null
                )
            }
        }
    }

    /** Deletes a set of sessions by their IDs and resets if the active session is included. */
    fun deleteSelectedSessions(ids: Set<Long>) {
        viewModelScope.launch {
            chatRepository?.deleteSelectedSessions(ids)
            if (_uiState.value.currentSessionId in ids) {
                _uiState.update {
                    it.copy(
                        currentSessionId = null,
                        messages = emptyList(),
                        consoleEntries = emptyList(),
                        messageConsoleEntries = emptyMap(),
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
     * Activates threaded-reply mode for [message].
     * The reply-preview bar is shown above the input field until the user sends or dismisses it.
     */
    fun setReplyingTo(message: ChatMessage) {
        _uiState.update { it.copy(replyingTo = message) }
    }

    /** Clears the pending reply target (dismiss the reply-preview bar). */
    fun clearReplyingTo() {
        _uiState.update { it.copy(replyingTo = null) }
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

        // CHAT and AUTO modes do not require a Target Context scope (AUTO may route to CHAT)
        val scopePath = _uiState.value.targetContext
        if (mode != OmniMode.CHAT && mode != OmniMode.AUTO && scopePath == null) {
            _uiState.update { it.copy(errorMessage = "Please set a Target Context before sending messages.") }
            return
        }

        val attachments = _uiState.value.pendingAttachments
        // Append attachment file names to the message content so the model is aware of them.
        val attachmentNote = if (attachments.isNotEmpty()) {
            "\n\n[Attached files: ${attachments.joinToString(", ") { it.displayName }}]"
        } else ""

        // Embed reply reference so both the UI and the agent know what message is being replied to.
        val replyingTo = _uiState.value.replyingTo
        val replyPrefix = replyingTo?.let { ref ->
            val senderLabel = if (ref.role == MessageRole.USER) "you" else "OmniDev"
            "[Replying to $senderLabel: \"${ref.content.take(150).replace("\n", " ")}\"]\n\n"
        } ?: ""

        val userMessage = ChatMessage(
            role = MessageRole.USER,
            content = replyPrefix + input + attachmentNote,
            replyToMessageId = replyingTo?.messageId
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                inputText = "",
                isProcessing = true,
                agentStatus = "Starting ${mode.label}...",
                errorMessage = null,
                consoleEntries = emptyList(), // fresh console for each run
                pendingAttachments = emptyList(), // clear after send
                replyingTo = null // clear after send
            )
        }

        currentAgentJob = viewModelScope.launch {
            val sessionId = ensureSession(input)
            chatRepository?.saveMessage(sessionId, userMessage)

            // Resolve base64 image data for vision-capable attachments
            val imageAttachments: List<AttachmentMeta> = attachments
                .mapNotNull { pending ->
                    val uri = pending.uri
                    val mimeType = attachmentProcessor?.getMimeType(uri) ?: return@mapNotNull null
                    if (!mimeType.startsWith("image/", ignoreCase = true)) {
                        return@mapNotNull null
                    }
                    val base64 = attachmentProcessor?.readImageAsBase64(uri) ?: return@mapNotNull null
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
                OmniMode.AUTO -> {
                    val resolved = classifyTaskComplexity(input)
                    _uiState.update {
                        it.copy(agentStatus = "🧠 Auto-routed → ${resolved.label}")
                    }
                    when (resolved) {
                        OmniMode.CHAT -> executeChatMode(input, imageAttachments, sessionId)
                        OmniMode.AGENT -> {
                            val scope = scopePath ?: run {
                                // No scope set — fall back to Chat for conversational auto requests
                                executeChatMode(input, imageAttachments, sessionId)
                                return@launch
                            }
                            executeAgentMode(input, imageAttachments, sessionId, scope)
                        }
                        OmniMode.SWARM -> {
                            val scope = scopePath ?: run {
                                executeAgentMode(input, imageAttachments, sessionId, "")
                                return@launch
                            }
                            executeSwarmMode(input, sessionId, scope)
                        }
                        OmniMode.AUTO -> executeChatMode(input, imageAttachments, sessionId)
                    }
                }
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

    /**
     * Immediately cancels the currently running agent/chat/swarm execution.
     *
     * Safe to call at any time — no-ops when nothing is running.
     * The coroutine cancellation propagates through [AgentPipeline] and [SwarmOrchestrator]
     * (both re-throw [kotlinx.coroutines.CancellationException]), cleanly terminating all
     * in-flight network calls and tool executions.
     */
    fun cancelCurrentRun() {
        val checkpoint = buildInterruptionCheckpointMessage(
            reason = USER_STOPPED_MESSAGE,
            state = _uiState.value
        )
        val statusMessage = checkpoint ?: buildRunStatusMessage(USER_STOPPED_MESSAGE)
        val sessionId = _uiState.value.currentSessionId
        val currentConsole = _uiState.value.consoleEntries
        val persistableSessionId = sessionId.takeIf { isPersistableSessionId(it) }

        currentAgentJob?.cancel()
        currentAgentJob = null

        _uiState.update {
            it.copy(
                messages = it.messages + statusMessage,
                messageConsoleEntries = if (currentConsole.isNotEmpty())
                    it.messageConsoleEntries + (statusMessage.timestamp to currentConsole)
                else it.messageConsoleEntries
            )
        }
        if (persistableSessionId != null) {
            viewModelScope.launch {
                chatRepository?.saveMessage(persistableSessionId, statusMessage, currentConsole)
                chatRepository?.updateSessionRunStatus(persistableSessionId, STATUS_USER_STOPPED)
            }
        }

        _uiState.update {
            it.copy(
                isProcessing = false,
                agentStatus = null,
                streamingContent = null,
                errorMessage = USER_STOPPED_MESSAGE
            )
        }
    }

    // ──────────────────────────────────────────────
    //  AUTO-ROUTING — Task Complexity Classifier
    // ──────────────────────────────────────────────

    /**
     * Intent-based task classifier — delegates to [IntentClassifier.classify].
     *
     * `internal` visibility allows unit tests in the same module to exercise the routing logic
     * directly without going through the full [sendMessage] flow.
     */
    internal fun classifyTaskComplexity(input: String): OmniMode =
        IntentClassifier.classify(input)
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

        val effectiveSystemPrompt = CHAT_SYSTEM_PROMPT

        val history = _uiState.value.messages.dropLast(1)

        val request = CompletionRequest(
            modelId = modelId,
            messages = history + ChatMessage(
                role = MessageRole.USER,
                content = input,
                attachments = imageAttachments
            ),
            systemPrompt = effectiveSystemPrompt,
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
        chatRepository?.updateSessionRunStatus(sessionId, STATUS_COMPLETED)
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
        val userPersona = settingsRepository.observeUserPersona().first()

        agentPipeline.execute(
            userMessage = input,
            conversationHistory = _uiState.value.messages.dropLast(1),
            modelId = modelId,
            scopePath = scopePath,
            enableDeepThinking = deepThinking,
            userAttachments = imageAttachments,
            customSystemPrompt = null,
            userContext = userPersona
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
                val fullParams = event.arguments.entries
                    .joinToString("\n") { (k, v) -> "$k = $v" }
                _uiState.update {
                    it.copy(
                        agentStatus = "Executing ${event.toolName}...",
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ToolEntry(event.toolName, params, event.iteration, fullParams)
                    )
                }
            }

            is AgentEvent.ToolResult -> {
                val snippet = event.output.lines().firstOrNull()?.take(100) ?: ""
                val durationMs = _uiState.value.consoleEntries
                    .filterIsInstance<AgentConsoleEntry.ToolEntry>()
                    .lastOrNull { it.toolName == event.toolName && it.iteration == event.iteration }
                    ?.let { System.currentTimeMillis() - it.timestamp } ?: 0L
                _uiState.update {
                    it.copy(
                        agentStatus = if (event.isError) "Tool error: ${event.toolName}"
                        else "Tool completed: ${event.toolName}",
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ResultEntry(event.toolName, snippet, event.isError, event.output, durationMs)
                    )
                }
                viewModelScope.launch { analyticsRepository?.recordToolUsage(event.toolName) }
            }

            is AgentEvent.TokenUsageUpdate ->
                _uiState.update {
                    it.copy(
                        agentStatus = "Thinking (${event.totalTokens} tokens used)...",
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.TokenEntry(event.totalTokens, event.budget)
                    )
                }

            is AgentEvent.PhaseChanged ->
                _uiState.update {
                    it.copy(
                        agentStatus = "Phase: ${event.phase.displayLabel}",
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.PhaseEntry(
                                phase = event.phase.displayLabel,
                                detail = event.detail
                            )
                    )
                }

            is AgentEvent.Reflecting ->
                _uiState.update {
                    it.copy(agentStatus = "🔍 Self-reflection (reviewing draft answer)...")
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
                val currentConsole = _uiState.value.consoleEntries
                viewModelScope.launch {
                    chatRepository?.saveMessage(sessionId, assistantMessage, currentConsole)
                    chatRepository?.updateSessionRunStatus(sessionId, STATUS_COMPLETED)
                }
                _uiState.update {
                    it.copy(
                        messages = it.messages + assistantMessage,
                        messageConsoleEntries = if (currentConsole.isNotEmpty())
                            it.messageConsoleEntries + (assistantMessage.timestamp to currentConsole)
                        else it.messageConsoleEntries,
                        isProcessing = false,
                        agentStatus = null,
                        streamingContent = null,
                        consoleEntries = it.consoleEntries + AgentConsoleEntry.ReplyEntry()
                    )
                }
            }

            is AgentEvent.Error -> {
                val checkpoint = buildInterruptionCheckpointMessage(
                    reason = event.message,
                    state = _uiState.value
                )
                val currentConsole = _uiState.value.consoleEntries
                val persistableSessionId = sessionId.takeIf { isPersistableSessionId(it) }
                _uiState.update {
                    it.copy(
                        messages = if (checkpoint != null) it.messages + checkpoint else it.messages,
                        messageConsoleEntries = if (checkpoint != null && currentConsole.isNotEmpty())
                            it.messageConsoleEntries + (checkpoint.timestamp to currentConsole)
                        else it.messageConsoleEntries,
                        isProcessing = false,
                        agentStatus = null,
                        errorMessage = event.message,
                        streamingContent = null,
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ErrorEntry(event.message)
                    )
                }
                if (checkpoint != null && persistableSessionId != null) {
                    viewModelScope.launch {
                        chatRepository?.saveMessage(persistableSessionId, checkpoint, currentConsole)
                        chatRepository?.updateSessionRunStatus(persistableSessionId, STATUS_INTERRUPTED)
                    }
                } else if (persistableSessionId != null) {
                    val statusMessage = buildRunStatusMessage(event.message)
                    _uiState.update { it.copy(messages = it.messages + statusMessage) }
                    viewModelScope.launch {
                        chatRepository?.saveMessage(persistableSessionId, statusMessage, currentConsole)
                        chatRepository?.updateSessionRunStatus(persistableSessionId, STATUS_INTERRUPTED)
                    }
                }
            }
        }
    }

    /**
     * Builds a compact assistant checkpoint message so follow-up prompts like "continue"
     * can resume from the latest known progress instead of restarting from scratch.
     */
    private fun buildInterruptionCheckpointMessage(
        reason: String,
        state: ChatUiState
    ): ChatMessage? {
        val partial = state.streamingContent?.trim().orEmpty()
        val consoleTail = state.consoleEntries.takeLast(CHECKPOINT_CONSOLE_TAIL_SIZE)

        if (partial.isBlank() && consoleTail.isEmpty()) return null

        val progressLines = consoleTail.mapNotNull { entry ->
            when (entry) {
                is AgentConsoleEntry.ToolEntry ->
                    "• Tool call: ${entry.toolName}(${sanitizeCheckpointText(entry.params, CHECKPOINT_TOOL_PARAMS_PREVIEW_CHARS)})"
                is AgentConsoleEntry.ResultEntry ->
                    "• Tool result: ${entry.toolName} → ${entry.snippet}"
                is AgentConsoleEntry.ThinkingEntry ->
                    "• Iteration ${entry.iteration}: thinking"
                is AgentConsoleEntry.DeepThinkingEntry ->
                    "• Deep thinking: ${sanitizeCheckpointText(entry.snippet, CHECKPOINT_DEEP_THINKING_PREVIEW_CHARS)}"
                is AgentConsoleEntry.TokenEntry ->
                    "• Tokens used: ${entry.totalTokens}"
                is AgentConsoleEntry.PhaseEntry ->
                    "• Phase: ${entry.phase}${entry.detail?.let { " — ${sanitizeCheckpointText(it, CHECKPOINT_TOOL_PARAMS_PREVIEW_CHARS)}" } ?: ""}"
                is AgentConsoleEntry.ErrorEntry ->
                    "• Error observed: ${sanitizeCheckpointText(entry.message, CHECKPOINT_ERROR_PREVIEW_CHARS)}"
                is AgentConsoleEntry.ReplyEntry -> null
            }
        }

        val checkpoint = buildString {
            appendLine("Execution checkpoint (auto-saved)")
            appendLine("Reason: $reason")
            if (partial.isNotBlank()) {
                appendLine()
                appendLine("Partial response:")
                appendLine(partial)
            }
            if (progressLines.isNotEmpty()) {
                appendLine()
                appendLine("Latest progress:")
                progressLines.forEach { appendLine(it) }
            }
            appendLine()
            append("Continue from this checkpoint; do not restart previous finished steps.")
        }

        return ChatMessage(
            role = MessageRole.ASSISTANT,
            content = checkpoint
        )
    }

    private fun isPersistableSessionId(sessionId: Long?): Boolean = sessionId != null && sessionId >= 0L

    private fun buildRunStatusMessage(statusText: String): ChatMessage = ChatMessage(
        role = MessageRole.ASSISTANT,
        content = "Run status: $statusText"
    )

    private val AgentExecutionPhase.displayLabel: String
        get() = when (this) {
            AgentExecutionPhase.ANALYZE -> "Analyze"
            AgentExecutionPhase.IMPLEMENT -> "Implement"
            AgentExecutionPhase.VERIFY -> "Verify"
            AgentExecutionPhase.REPORT -> "Report"
        }

    private fun sanitizeCheckpointText(value: String, maxChars: Int): String {
        return value
            .replace(CHECKPOINT_SECRET_ASSIGNMENT_REGEX) { match ->
                val key = match.groupValues[1]
                "$key=[REDACTED]"
            }
            .replace(CHECKPOINT_BEARER_REGEX, "$1[REDACTED]")
            .take(maxChars)
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
                            AgentConsoleEntry.ResultEntry(event.task.id, snippet, isError = false, fullOutput = event.result)
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
                val currentConsole = _uiState.value.consoleEntries
                viewModelScope.launch {
                    chatRepository?.saveMessage(sessionId, assistantMessage, currentConsole)
                    chatRepository?.updateSessionRunStatus(sessionId, STATUS_COMPLETED)
                }
                _uiState.update {
                    it.copy(
                        messages = it.messages + assistantMessage,
                        messageConsoleEntries = if (currentConsole.isNotEmpty())
                            it.messageConsoleEntries + (assistantMessage.timestamp to currentConsole)
                        else it.messageConsoleEntries,
                        isProcessing = false,
                        agentStatus = null,
                        streamingContent = null,
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ReplyEntry()
                    )
                }
            }

            is SwarmEvent.WorkerStreamChunk ->
                // Forward real-time worker streaming delta to the console
                _uiState.update {
                    it.copy(
                        agentStatus = "⚙️ Worker ${event.task.id}: streaming…",
                        streamingContent = (it.streamingContent ?: "") + event.delta
                    )
                }

            is SwarmEvent.Error -> {
                val statusMessage = buildRunStatusMessage(event.message)
                _uiState.update {
                    it.copy(
                        messages = it.messages + statusMessage,
                        isProcessing = false,
                        agentStatus = null,
                        errorMessage = event.message,
                        streamingContent = null,
                        consoleEntries = it.consoleEntries +
                            AgentConsoleEntry.ErrorEntry(event.message)
                    )
                }
                if (isPersistableSessionId(sessionId)) {
                    viewModelScope.launch {
                        chatRepository?.saveMessage(sessionId, statusMessage, _uiState.value.consoleEntries)
                        chatRepository?.updateSessionRunStatus(sessionId, STATUS_INTERRUPTED)
                    }
                }
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
        compositeToolManager?.currentSessionId = newId
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
