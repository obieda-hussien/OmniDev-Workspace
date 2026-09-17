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
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.model.ExecutionModeRequest
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.repository.AnalyticsRepository
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.ChatRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.tools.CompositeToolManager
import com.omnidev.workspace.data.tools.FileToolManager
import com.omnidev.workspace.domain.attachment.AttachmentProcessor
import com.omnidev.workspace.domain.engine.AdaptiveModeRouter
import com.omnidev.workspace.domain.engine.AgentExecutionPhase
import com.omnidev.workspace.domain.engine.AgentEvent
import com.omnidev.workspace.domain.engine.AgentPipeline
import com.omnidev.workspace.domain.engine.IntentClassifier
import com.omnidev.workspace.domain.engine.ModeSwitchPermissionStore
import com.omnidev.workspace.domain.engine.OmniMode
import com.omnidev.workspace.domain.engine.SwarmEvent
import com.omnidev.workspace.domain.engine.SwarmOrchestrator
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** A file selected by the user but not sent yet. */
data class PendingAttachment(val uri: Uri, val displayName: String)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val targetContext: String? = null,
    val targetContextDisplayName: String? = null,
    val isProcessing: Boolean = false,
    val agentStatus: String? = null,
    val errorMessage: String? = null,
    val consoleEntries: List<AgentConsoleEntry> = emptyList(),
    val messageConsoleEntries: Map<Long, List<AgentConsoleEntry>> = emptyMap(),
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val sessions: List<ChatSessionEntity> = emptyList(),
    val isDrawerOpen: Boolean = false,
    val currentSessionId: Long? = null,
    val streamingContent: String? = null,
    /** User-facing tabs remain Chat / Agent / Team. AUTO is only for overlay routing. */
    val activeMode: OmniMode = OmniMode.CHAT,
    val pendingConfirmation: PendingConfirmation? = null,
    val isGodModeEnabled: Boolean = false,
    val replyingTo: ChatMessage? = null,
    val chatSettings: com.omnidev.workspace.domain.model.ChatSettings =
        com.omnidev.workspace.domain.model.ChatSettings()
)

/**
 * Primary chat/agent runtime.
 *
 * Mode switching is capability escalation, not a new conversation. Manual tab changes are
 * always allowed. Agent-initiated switches require explicit permission unless the user already
 * granted that exact transition or all transitions for this session.
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
        private const val MAX_HANDOFF_CONTEXT_CHARS = 2_000
        private val CHECKPOINT_SECRET_ASSIGNMENT_REGEX =
            Regex("(?i)(\\b(?:api_?key|key|token|secret|password|otp)\\b)\\s*(?:=|:)\\s*(?:\"[^\"]+\"|[^\\s,&\"']+)")
        private val CHECKPOINT_BEARER_REGEX =
            Regex("(?i)(\\bAuthorization\\b\\s*:\\s*Bearer\\s+|\\bbearer\\b\\s+)([^\\s,;]+)")
    }

    private class ModeHandoffSignal(val suggestion: AdaptiveModeRouter.Suggestion) : RuntimeException()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private val activeRunId = AtomicLong(0L)
    @Volatile private var currentAgentJob: Job? = null
    private var sessionObservation: Job? = null

    private val modePermissionStore: ModeSwitchPermissionStore by lazy {
        ModeSwitchPermissionStore(com.omnidev.workspace.OmniDevApp.instance.applicationContext)
    }

    init {
        loadTargetContext()
        observeSessions()
        observeGodMode()
        observeChatSettings()
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

    private fun observeGodMode() {
        viewModelScope.launch {
            settingsRepository.observeGodMode().collect { enabled ->
                fileToolManager?.godModeEnabled = enabled
                _uiState.update { it.copy(isGodModeEnabled = enabled) }
            }
        }
    }

    private fun observeChatSettings() {
        viewModelScope.launch {
            settingsRepository.observeChatSettings().collect { settings ->
                _uiState.update { it.copy(chatSettings = settings) }
            }
        }
    }

    fun updateChatSettings(settings: com.omnidev.workspace.domain.model.ChatSettings) {
        viewModelScope.launch { settingsRepository.saveChatSettings(settings) }
    }

    private fun wireFileConfirmationGate() {
        val ftm = fileToolManager ?: return
        val uiGate = com.omnidev.workspace.core.policy.ConfirmationGate { kind, preview, diff ->
            val deferred = CompletableDeferred<Boolean>()
            val confirmationType = when (kind) {
                com.omnidev.workspace.core.policy.ConfirmationKind.GOD_MODE_FILE_PATCH -> ConfirmationType.GOD_MODE_FILE_PATCH
                com.omnidev.workspace.core.policy.ConfirmationKind.GOD_MODE_FILE_WRITE -> ConfirmationType.GOD_MODE_FILE_WRITE
                com.omnidev.workspace.core.policy.ConfirmationKind.GOD_MODE_FILE_DELETE -> ConfirmationType.GOD_MODE_FILE_DELETE
                com.omnidev.workspace.core.policy.ConfirmationKind.SHIZUKU_COMMAND -> ConfirmationType.SHIZUKU_COMMAND
                com.omnidev.workspace.core.policy.ConfirmationKind.ANDROID_INTENT -> ConfirmationType.ANDROID_INTENT
            }
            showConfirmation(
                PendingConfirmation(
                    id = UUID.randomUUID().toString(),
                    type = confirmationType,
                    preview = preview,
                    diffContent = diff,
                    onApprove = {
                        com.omnidev.workspace.core.policy.OmniAuditLog.record(
                            tier = com.omnidev.workspace.core.policy.TierPolicyHolder.current.tier,
                            autoApproved = false,
                            kind = kind,
                            preview = preview,
                            diffContent = diff
                        )
                        deferred.complete(true)
                    },
                    onDeny = { deferred.complete(false) }
                )
            )
            deferred.await()
        }
        val effectiveGate = com.omnidev.workspace.core.policy.TierPolicyHolder.current.confirmationGate(uiGate)
        ftm.confirmationGate = { preview, diffContent ->
            val kind = if (diffContent != null)
                com.omnidev.workspace.core.policy.ConfirmationKind.GOD_MODE_FILE_PATCH
            else com.omnidev.workspace.core.policy.ConfirmationKind.GOD_MODE_FILE_DELETE
            effectiveGate.request(kind, preview, diffContent)
        }
        compositeToolManager?.confirmationGate = effectiveGate
    }

    fun showConfirmation(confirmation: PendingConfirmation) {
        _uiState.update { it.copy(pendingConfirmation = confirmation) }
    }

    fun clearConfirmation() {
        _uiState.update { it.copy(pendingConfirmation = null) }
    }

    fun setDrawerOpen(open: Boolean) {
        _uiState.update { it.copy(isDrawerOpen = open) }
    }

    /**
     * Manual tabs are always authoritative. If a run is active, save a checkpoint and stop it
     * before changing mode so late events cannot leak into the newly selected runtime.
     */
    fun setMode(mode: OmniMode) {
        if (_uiState.value.activeMode == mode) return
        if (_uiState.value.isProcessing) {
            cancelCurrentRun()
            _uiState.update { it.copy(errorMessage = null) }
        }
        _uiState.update { it.copy(activeMode = mode) }
    }

    fun loadSession(sessionId: Long) {
        modePermissionStore.clearSession(_uiState.value.currentSessionId)
        activeRunId.incrementAndGet()
        currentAgentJob?.cancel()
        _uiState.update { it.copy(isProcessing = false, streamingContent = null) }
        sessionObservation?.cancel()
        sessionObservation = viewModelScope.launch {
            chatRepository?.observeMessages(sessionId)?.collect {
                if (_uiState.value.isProcessing) return@collect
                val (messages, consoleMap) = chatRepository.loadMessages(sessionId)
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
    }

    fun newSession() {
        modePermissionStore.clearSession(_uiState.value.currentSessionId)
        sessionObservation?.cancel()
        activeRunId.incrementAndGet()
        currentAgentJob?.cancel()
        currentAgentJob = null
        _uiState.update {
            it.copy(
                currentSessionId = null,
                isProcessing = false,
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

    fun togglePinSession(sessionId: Long) {
        viewModelScope.launch { chatRepository?.togglePin(sessionId) }
    }

    fun renameSession(sessionId: Long, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch { chatRepository?.renameSession(sessionId, newTitle.trim()) }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            chatRepository?.deleteSession(sessionId)
            modePermissionStore.clearSession(sessionId)
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

    fun deleteAllSessions() {
        viewModelScope.launch {
            modePermissionStore.clearSession(_uiState.value.currentSessionId)
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

    fun deleteSelectedSessions(ids: Set<Long>) {
        viewModelScope.launch {
            ids.forEach(modePermissionStore::clearSession)
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

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun addAttachments(uris: List<Uri>, displayNames: List<String>) {
        require(uris.size == displayNames.size)
        val additions = uris.mapIndexed { index, uri -> PendingAttachment(uri, displayNames[index]) }
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + additions) }
    }

    fun removeAttachment(uri: Uri) {
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments.filterNot { a -> a.uri == uri }) }
    }

    fun setReplyingTo(message: ChatMessage) {
        _uiState.update { it.copy(replyingTo = message) }
    }

    fun clearReplyingTo() {
        _uiState.update { it.copy(replyingTo = null) }
    }

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

    fun setTargetContextFromUri(context: Context, uri: Uri) {
        val treeDocId = DocumentsContract.getTreeDocumentId(uri) ?: return
        val colon = treeDocId.indexOf(':')
        val relative = if (colon >= 0) treeDocId.substring(colon + 1) else treeDocId
        val root = if (treeDocId.startsWith("primary")) "/storage/emulated/0"
        else "/storage/${treeDocId.substringBefore(':')}"
        val path = if (relative.isEmpty()) root else "$root/$relative"
        val display = relative.substringAfterLast('/').ifBlank { path.substringAfterLast('/') }
        viewModelScope.launch {
            settingsRepository.setTargetContextUri(uri.toString(), path)
            _uiState.update { it.copy(targetContext = path, targetContextDisplayName = display) }
        }
    }

    fun sendMessage() {
        val state = _uiState.value
        val input = state.inputText.trim()
        if (input.isEmpty() || state.isProcessing) return

        val runId = activeRunId.incrementAndGet()
        currentAgentJob?.cancel()
        val mode = state.activeMode
        val scopePath = state.targetContext
        if (mode != OmniMode.CHAT && mode != OmniMode.AUTO && scopePath == null && !state.isGodModeEnabled) {
            _uiState.update { it.copy(errorMessage = "Please set a Target Context before sending messages.") }
            return
        }

        val attachments = state.pendingAttachments
        val attachmentNote = if (attachments.isEmpty()) "" else
            "\n\n[Attached files: ${attachments.joinToString(", ") { it.displayName }}]"
        val replyPrefix = state.replyingTo?.let { ref ->
            val who = if (ref.role == MessageRole.USER) "you" else "OmniDev"
            "[Replying to $who: \"${ref.content.take(150).replace("\n", " ")}\"]\n\n"
        }.orEmpty()
        val userMessage = ChatMessage(
            role = MessageRole.USER,
            content = replyPrefix + input + attachmentNote,
            replyToMessageId = state.replyingTo?.messageId
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                inputText = "",
                isProcessing = true,
                agentStatus = "Starting ${mode.label}...",
                errorMessage = null,
                consoleEntries = emptyList(),
                pendingAttachments = emptyList(),
                replyingTo = null
            )
        }

        currentAgentJob = viewModelScope.launch {
            val sessionId = ensureSession(input)
            chatRepository?.saveMessage(sessionId, userMessage)
            val imageAttachments = resolveImageAttachments(attachments)
            val scope = scopePath ?: if (_uiState.value.isGodModeEnabled) "/" else ""
            when (mode) {
                OmniMode.AUTO -> {
                    val resolved = classifyTaskComplexity(input)
                    _uiState.update { it.copy(agentStatus = "🧠 Auto-routed → ${resolved.label}") }
                    when (resolved) {
                        OmniMode.CHAT, OmniMode.AUTO -> executeChatMode(input, imageAttachments, sessionId, runId)
                        OmniMode.AGENT -> if (scope.isNotBlank()) executeAgentMode(input, imageAttachments, sessionId, scope, runId = runId)
                            else executeChatMode(input, imageAttachments, sessionId, runId)
                        OmniMode.SWARM -> if (scope.isNotBlank()) executeSwarmMode(input, sessionId, scope, runId)
                            else executeChatMode(input, imageAttachments, sessionId, runId)
                    }
                }
                OmniMode.CHAT -> executeChatMode(input, imageAttachments, sessionId, runId)
                OmniMode.AGENT -> executeAgentMode(input, imageAttachments, sessionId, scope, runId = runId)
                OmniMode.SWARM -> executeSwarmMode(input, sessionId, scope, runId)
            }
        }
    }

    private suspend fun resolveImageAttachments(attachments: List<PendingAttachment>): List<AttachmentMeta> =
        attachments.mapNotNull { pending ->
            val mime = attachmentProcessor?.getMimeType(pending.uri) ?: return@mapNotNull null
            if (!mime.startsWith("image/", true)) return@mapNotNull null
            val base64 = attachmentProcessor.readImageAsBase64(pending.uri) ?: return@mapNotNull null
            AttachmentMeta(
                uri = pending.uri.toString(),
                mimeType = mime,
                fileName = pending.displayName,
                sizeBytes = 0L,
                mediaType = AttachmentMediaType.IMAGE,
                base64Data = base64
            )
        }

    fun cancelCurrentRun() {
        if (!_uiState.value.isProcessing) return
        val state = _uiState.value
        val checkpoint = buildInterruptionCheckpointMessage(USER_STOPPED_MESSAGE, state)
        val status = checkpoint ?: buildRunStatusMessage(USER_STOPPED_MESSAGE)
        val sessionId = state.currentSessionId
        val console = state.consoleEntries

        activeRunId.incrementAndGet()
        currentAgentJob?.cancel()
        currentAgentJob = null
        _uiState.update {
            it.copy(
                messages = it.messages + status,
                messageConsoleEntries = if (console.isNotEmpty())
                    it.messageConsoleEntries + (status.timestamp to console) else it.messageConsoleEntries,
                isProcessing = false,
                agentStatus = null,
                streamingContent = null,
                errorMessage = USER_STOPPED_MESSAGE
            )
        }
        if (isPersistableSessionId(sessionId)) {
            viewModelScope.launch {
                chatRepository?.saveMessage(sessionId!!, status, console)
                chatRepository?.updateSessionRunStatus(sessionId, STATUS_USER_STOPPED)
            }
        }
    }

    internal fun classifyTaskComplexity(input: String): OmniMode = IntentClassifier.classify(input)

    private suspend fun executeChatMode(
        input: String,
        imageAttachments: List<AttachmentMeta>,
        sessionId: Long,
        runId: Long
    ) {
        val modelId = settingsRepository
            .observeModelIdForRole(com.omnidev.workspace.data.model.ModelRole.CHAT).first()
        val model = ModelRegistry.findModelById(modelId) ?: ModelRegistry.getModelById(modelId)
        val original = _uiState.value.messages.lastOrNull { it.role == MessageRole.USER } ?: return
        val withAttachments = original.copy(attachments = imageAttachments)
        _uiState.update { state -> state.copy(messages = state.messages.map {
            if (it.messageId == original.messageId) withAttachments else it
        }) }
        chatRepository?.updateMetadata(sessionId, withAttachments)

        var savedMessage = ChatMessage(MessageRole.ASSISTANT, "Run interrupted before completion. Saved activity is available below.")
        val rowId = chatRepository?.saveMessage(sessionId, savedMessage) ?: -1L
        var runEntries = emptyList<AgentConsoleEntry>()
        var runPartial: String? = null
        var lastCheckpoint = 0L

        suspend fun checkpoint(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastCheckpoint < 750) return
            lastCheckpoint = now
            chatRepository?.updateRun(
                rowId,
                savedMessage.copy(content = runPartial?.takeIf { it.isNotBlank() } ?: savedMessage.content),
                runEntries
            )
        }

        val request = CompletionRequest(
            modelId = modelId,
            messages = _uiState.value.messages.takeLast(20),
            systemPrompt = CHAT_SYSTEM_PROMPT +
                "\nChat can use its supplied research tools directly. Request AGENT only when execution tools are required, and TEAM only when independent parallel work is materially useful. A request is a proposal, never permission.",
            maxTokens = minOf(model.maxOutputTokens, 4096),
            enableThinking = settingsRepository.observeDeepThinking().first() && model.supportsThinking,
            apiKey = apiKeyRepository?.getApiKey(model.provider)
        )

        try {
            val result = com.omnidev.workspace.domain.engine.ChatToolLoop(compositeToolManager).run(
                base = request,
                disabled = _uiState.value.chatSettings.disabledToolNames(),
                originMessageId = original.messageId,
                complete = { next ->
                    if (runId != activeRunId.get()) throw CancellationException("Run superseded")
                    runPartial = null
                    _uiState.update { it.copy(streamingContent = null) }
                    streamingCompletionProvider?.let { stream ->
                        stream(next) { delta ->
                            if (runId == activeRunId.get()) {
                                _uiState.update { it.copy(streamingContent = (it.streamingContent ?: "") + delta) }
                                runPartial = _uiState.value.streamingContent
                                checkpoint()
                            }
                        }
                    } ?: completionProvider?.invoke(next)
                    ?: throw IllegalStateException("Chat completion service is unavailable.")
                },
                event = { event ->
                    if (runId != activeRunId.get()) throw CancellationException("Run superseded")
                    handleAgentEvent(event, sessionId, runId)
                    runEntries = _uiState.value.consoleEntries.toList()
                    checkpoint(event is AgentEvent.ToolExecution || event is AgentEvent.ToolResult)
                }
            )
            savedMessage = savedMessage.copy(content = result.content, executionRequest = result.request)
            runEntries = runEntries + AgentConsoleEntry.ReplyEntry()
            chatRepository?.updateRun(rowId, savedMessage, runEntries)
            chatRepository?.updateSessionRunStatus(sessionId, STATUS_COMPLETED)

            if (runId == activeRunId.get()) {
                _uiState.update {
                    it.copy(
                        messages = it.messages + savedMessage,
                        messageConsoleEntries = it.messageConsoleEntries + (savedMessage.timestamp to runEntries),
                        isProcessing = false,
                        streamingContent = null,
                        agentStatus = null,
                        consoleEntries = runEntries
                    )
                }
                result.request?.let { maybeAutoConsumeModeRequest(savedMessage, it, sessionId) }
            }
        } catch (cancelled: CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                chatRepository?.updateRun(
                    rowId,
                    savedMessage.copy(content = runPartial.orEmpty().ifBlank { USER_STOPPED_MESSAGE }),
                    runEntries
                )
            }
            throw cancelled
        } catch (error: Exception) {
            val text = "Chat failed: ${error.message ?: "Unknown error"}"
            savedMessage = savedMessage.copy(content = text)
            runEntries = runEntries + AgentConsoleEntry.ErrorEntry(text)
            chatRepository?.updateRun(rowId, savedMessage, runEntries)
            chatRepository?.updateSessionRunStatus(sessionId, STATUS_INTERRUPTED)
            if (runId == activeRunId.get()) {
                _uiState.update {
                    it.copy(
                        messages = it.messages + savedMessage,
                        isProcessing = false,
                        agentStatus = null,
                        streamingContent = null,
                        errorMessage = text,
                        consoleEntries = runEntries
                    )
                }
            }
        }
    }

    /** Backward-compatible one-shot action used by old UI/tests. */
    fun acceptExecutionMode(messageId: String) =
        acceptExecutionMode(messageId, ModeSwitchPermissionStore.Approval.ONCE)

    /**
     * Approves a mode request with explicit scope chosen by the user.
     */
    fun acceptExecutionMode(messageId: String, approval: ModeSwitchPermissionStore.Approval) {
        if (approval == ModeSwitchPermissionStore.Approval.DENY) {
            denyExecutionMode(messageId)
            return
        }
        val state = _uiState.value
        if (state.isProcessing) return
        val proposal = state.messages.find { it.messageId == messageId } ?: return
        val request = proposal.executionRequest?.takeIf { it.status == "pending" } ?: return
        val target = request.mode.toOmniModeOrNull() ?: return
        val source = request.sourceMode?.toOmniModeOrNull() ?: state.activeMode
        val sessionId = state.currentSessionId ?: return
        modePermissionStore.recordDecision(source, target, sessionId, approval)
        val status = when (approval) {
            ModeSwitchPermissionStore.Approval.ONCE -> "accepted_once"
            ModeSwitchPermissionStore.Approval.ALWAYS_THIS_TRANSITION -> "accepted_always"
            ModeSwitchPermissionStore.Approval.ALL_THIS_SESSION -> "accepted_session"
            ModeSwitchPermissionStore.Approval.DENY -> "denied"
        }
        val accepted = proposal.copy(executionRequest = request.copy(status = status))
        _uiState.update { s -> s.copy(messages = s.messages.map { if (it.messageId == messageId) accepted else it }) }
        viewModelScope.launch { chatRepository?.updateMetadata(sessionId, accepted) }
        startModeHandoff(target, request, accepted, autoApproved = false)
    }

    fun denyExecutionMode(messageId: String) {
        val state = _uiState.value
        if (state.isProcessing) return
        val proposal = state.messages.find { it.messageId == messageId } ?: return
        val request = proposal.executionRequest?.takeIf { it.status == "pending" } ?: return
        val target = request.mode.toOmniModeOrNull() ?: return
        val source = request.sourceMode?.toOmniModeOrNull() ?: state.activeMode
        modePermissionStore.recordDecision(source, target, state.currentSessionId, ModeSwitchPermissionStore.Approval.DENY)
        val denied = proposal.copy(executionRequest = request.copy(status = "denied"))
        _uiState.update { s -> s.copy(messages = s.messages.map { if (it.messageId == messageId) denied else it }) }
        state.currentSessionId?.let { sessionId -> viewModelScope.launch { chatRepository?.updateMetadata(sessionId, denied) } }
    }

    private fun maybeAutoConsumeModeRequest(
        proposal: ChatMessage,
        request: ExecutionModeRequest,
        sessionId: Long
    ) {
        val target = request.mode.toOmniModeOrNull() ?: return
        val source = request.sourceMode?.toOmniModeOrNull() ?: _uiState.value.activeMode
        if (!modePermissionStore.canAutoSwitch(source, target, sessionId)) return
        val accepted = proposal.copy(executionRequest = request.copy(status = "accepted_auto"))
        _uiState.update { s -> s.copy(messages = s.messages.map { if (it.messageId == proposal.messageId) accepted else it }) }
        viewModelScope.launch { chatRepository?.updateMetadata(sessionId, accepted) }
        startModeHandoff(target, request, accepted, autoApproved = true)
    }

    private fun startModeHandoff(
        target: OmniMode,
        request: ExecutionModeRequest,
        proposal: ChatMessage,
        autoApproved: Boolean
    ) {
        val state = _uiState.value
        if (state.isProcessing) return
        val sessionId = state.currentSessionId ?: return
        val original = state.messages.find { it.messageId == request.originMessageId }
            ?: state.messages.lastOrNull { it.role == MessageRole.USER }
            ?: return
        val scopePath = state.targetContext ?: if (state.isGodModeEnabled) "/" else null
        if (target != OmniMode.CHAT && scopePath == null) {
            _uiState.update { it.copy(errorMessage = "Select a project folder before switching to ${target.label}.") }
            return
        }

        val checkpoint = state.messages.asReversed().firstOrNull {
            it.role == MessageRole.ASSISTANT && it.content.startsWith("Execution checkpoint")
        }?.content?.take(MAX_HANDOFF_CONTEXT_CHARS)
        val handoffInput = buildString {
            append(original.content)
            if (!checkpoint.isNullOrBlank()) {
                appendLine()
                appendLine()
                appendLine("## Continuation checkpoint")
                appendLine(checkpoint)
            }
            appendLine()
            appendLine()
            append("Continue the same task from existing progress. Do not redo completed work.")
        }

        val runId = activeRunId.incrementAndGet()
        _uiState.update {
            it.copy(
                activeMode = target,
                isProcessing = true,
                errorMessage = null,
                streamingContent = null,
                consoleEntries = emptyList(),
                agentStatus = if (autoApproved) "Auto-switching → ${target.label}" else "Switching → ${target.label}"
            )
        }
        currentAgentJob = viewModelScope.launch {
            try {
                val attachments = original.attachments.map { attachment ->
                    if (attachment.base64Data != null) attachment else attachment.copy(
                        base64Data = attachmentProcessor?.readImageAsBase64(Uri.parse(attachment.uri))
                    )
                }
                when (target) {
                    OmniMode.CHAT -> executeChatMode(handoffInput, attachments, sessionId, runId)
                    OmniMode.AGENT -> executeAgentMode(handoffInput, attachments, sessionId, scopePath ?: "/", runId = runId)
                    OmniMode.SWARM -> executeSwarmMode(handoffInput, sessionId, scopePath ?: "/", runId)
                    OmniMode.AUTO -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                handleAgentEvent(AgentEvent.Error(error.message ?: "Mode handoff failed"), sessionId, runId)
            }
        }
    }

    private suspend fun executeAgentMode(
        input: String,
        imageAttachments: List<AttachmentMeta>,
        sessionId: Long,
        scopePath: String,
        modelRole: com.omnidev.workspace.data.model.ModelRole = com.omnidev.workspace.data.model.ModelRole.AGENT,
        runId: Long
    ) {
        val modelId = settingsRepository.observeModelIdForRole(modelRole).first()
        val deepThinking = settingsRepository.observeDeepThinking().first()
        val userPersona = settingsRepository.observeUserPersona().first()
        val chatSettings = _uiState.value.chatSettings
        var escalation: AdaptiveModeRouter.Suggestion? = null

        agentPipeline.execute(
            userMessage = input,
            conversationHistory = _uiState.value.messages.dropLast(1),
            modelId = modelId,
            scopePath = scopePath,
            enableDeepThinking = deepThinking,
            userAttachments = imageAttachments,
            customSystemPrompt = null,
            userContext = userPersona,
            disabledToolNames = chatSettings.disabledToolNames(),
            toolAccessMode = chatSettings.toolAccessMode.name
        ).collect { event ->
            handleAgentEvent(event, sessionId, runId)
            if (event is AgentEvent.Error) {
                escalation = AdaptiveModeRouter.fromAgentFailure(event.message, input)
            }
        }

        if (runId == activeRunId.get()) {
            escalation?.let { suggestion ->
                val origin = _uiState.value.messages.lastOrNull { it.role == MessageRole.USER }
                if (origin != null) publishModeSuggestion(suggestion, origin, sessionId)
            }
        }
    }

    private suspend fun executeSwarmMode(
        input: String,
        sessionId: Long,
        scopePath: String,
        runId: Long
    ) {
        val orchestrator = swarmOrchestrator
        if (orchestrator == null) {
            _uiState.update { it.copy(isProcessing = false, errorMessage = "Team Agents are unavailable.") }
            return
        }
        val orchestratorModelId = settingsRepository
            .observeModelIdForRole(com.omnidev.workspace.data.model.ModelRole.SWARM_ORCHESTRATOR).first()
        val workerModelId = settingsRepository
            .observeModelIdForRole(com.omnidev.workspace.data.model.ModelRole.SWARM_WORKER).first()
        val godMode = settingsRepository.observeGodMode().first()
        val deepThinking = settingsRepository.observeDeepThinking().first()
        analyticsRepository?.recordAgentRun(isSwarm = true)

        try {
            orchestrator.execute(
                userMessage = input,
                orchestratorModelId = orchestratorModelId,
                workerModelId = workerModelId,
                scopePath = scopePath,
                enableDeepThinking = deepThinking,
                godModeEnabled = godMode
            ).collect { event ->
                handleSwarmEvent(event, sessionId, runId)
                if (event is SwarmEvent.PlanCompleted) {
                    val suggestion = AdaptiveModeRouter.fromTeamPlan(
                        taskCount = event.tasks.size,
                        parallelSafeTaskCount = event.tasks.count { it.parallelSafe }
                    )
                    if (suggestion != null) throw ModeHandoffSignal(suggestion)
                }
            }
        } catch (signal: ModeHandoffSignal) {
            if (runId != activeRunId.get()) return
            _uiState.update { it.copy(isProcessing = false, agentStatus = null, streamingContent = null) }
            val origin = _uiState.value.messages.lastOrNull { it.role == MessageRole.USER } ?: return
            publishModeSuggestion(signal.suggestion, origin, sessionId)
        }
    }

    private fun publishModeSuggestion(
        suggestion: AdaptiveModeRouter.Suggestion,
        origin: ChatMessage,
        sessionId: Long
    ) {
        val request = ExecutionModeRequest(
            mode = suggestion.to.name,
            reason = suggestion.reason,
            originMessageId = origin.messageId,
            sourceMode = suggestion.from.name,
            confidence = suggestion.confidence,
            trigger = suggestion.trigger.name
        )
        val proposal = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = suggestion.reason,
            executionRequest = request
        )
        _uiState.update {
            it.copy(
                messages = it.messages + proposal,
                isProcessing = false,
                agentStatus = null,
                errorMessage = null,
                streamingContent = null
            )
        }
        viewModelScope.launch { chatRepository?.saveMessage(sessionId, proposal) }

        if (modePermissionStore.canAutoSwitch(suggestion.from, suggestion.to, sessionId)) {
            val accepted = proposal.copy(executionRequest = request.copy(status = "accepted_auto"))
            _uiState.update { state ->
                state.copy(messages = state.messages.map { if (it.messageId == proposal.messageId) accepted else it })
            }
            viewModelScope.launch { chatRepository?.updateMetadata(sessionId, accepted) }
            startModeHandoff(suggestion.to, request, accepted, autoApproved = true)
        }
    }

    private fun handleAgentEvent(event: AgentEvent, sessionId: Long, runId: Long) {
        if (runId != activeRunId.get()) return
        when (event) {
            is AgentEvent.Started -> _uiState.update { it.copy(agentStatus = "Agent started...") }
            is AgentEvent.Thinking -> _uiState.update {
                it.copy(
                    agentStatus = "Thinking (iteration ${event.iteration})...",
                    consoleEntries = it.consoleEntries + AgentConsoleEntry.ThinkingEntry(event.iteration)
                )
            }
            is AgentEvent.ThinkingBlock -> _uiState.update {
                it.copy(
                    agentStatus = "Deep thinking...",
                    consoleEntries = it.consoleEntries.let { entries ->
                        val last = entries.lastOrNull()
                        if (last is AgentConsoleEntry.DeepThinkingEntry)
                            entries.dropLast(1) + last.copy(snippet = last.snippet + event.content)
                        else entries + AgentConsoleEntry.DeepThinkingEntry(event.content)
                    }
                )
            }
            is AgentEvent.ToolExecution -> {
                val params = event.arguments.entries.joinToString(", ") { (k, v) -> "$k=${v.toString().take(40)}" }
                val full = event.arguments.entries.joinToString("\n") { (k, v) -> "$k = $v" }
                _uiState.update {
                    it.copy(
                        agentStatus = "Executing ${event.toolName}...",
                        consoleEntries = it.consoleEntries + AgentConsoleEntry.ToolEntry(event.toolName, params, event.iteration, full)
                    )
                }
            }
            is AgentEvent.ToolResult -> {
                val snippet = event.output.lines().firstOrNull()?.take(100).orEmpty()
                val duration = _uiState.value.consoleEntries
                    .filterIsInstance<AgentConsoleEntry.ToolEntry>()
                    .lastOrNull { it.toolName == event.toolName && it.iteration == event.iteration }
                    ?.let { System.currentTimeMillis() - it.timestamp } ?: 0L
                _uiState.update {
                    it.copy(
                        agentStatus = if (event.isError) "Tool error: ${event.toolName}" else "Tool completed: ${event.toolName}",
                        consoleEntries = it.consoleEntries + AgentConsoleEntry.ResultEntry(event.toolName, snippet, event.isError, event.output, duration)
                    )
                }
            }
            is AgentEvent.TokenUsageUpdate -> _uiState.update {
                it.copy(
                    agentStatus = "Thinking (${event.totalTokens} tokens used)...",
                    consoleEntries = it.consoleEntries + AgentConsoleEntry.TokenEntry(event.totalTokens, event.budget)
                )
            }
            is AgentEvent.PhaseChanged -> _uiState.update {
                it.copy(
                    agentStatus = "Phase: ${event.phase.displayLabel}",
                    consoleEntries = it.consoleEntries + AgentConsoleEntry.PhaseEntry(event.phase.displayLabel, event.detail)
                )
            }
            is AgentEvent.Reflecting -> _uiState.update { it.copy(agentStatus = "🔍 Reviewing result...") }
            is AgentEvent.ContextCompaction -> _uiState.update {
                it.copy(consoleEntries = it.consoleEntries + AgentConsoleEntry.ContextSummaryEntry(event.summary))
            }
            is AgentEvent.StreamChunk -> _uiState.update {
                it.copy(streamingContent = (it.streamingContent ?: "") + event.delta)
            }
            is AgentEvent.FinalAnswer -> {
                val message = ChatMessage(MessageRole.ASSISTANT, event.content)
                val console = _uiState.value.consoleEntries
                viewModelScope.launch {
                    chatRepository?.saveMessage(sessionId, message, console)
                    chatRepository?.updateSessionRunStatus(sessionId, STATUS_COMPLETED)
                }
                _uiState.update {
                    it.copy(
                        messages = it.messages + message,
                        messageConsoleEntries = if (console.isEmpty()) it.messageConsoleEntries
                        else it.messageConsoleEntries + (message.timestamp to console),
                        isProcessing = false,
                        agentStatus = null,
                        streamingContent = null,
                        consoleEntries = it.consoleEntries + AgentConsoleEntry.ReplyEntry()
                    )
                }
            }
            is AgentEvent.Error -> {
                val checkpoint = buildInterruptionCheckpointMessage(event.message, _uiState.value)
                val console = _uiState.value.consoleEntries
                val status = checkpoint ?: buildRunStatusMessage(event.message)
                _uiState.update {
                    it.copy(
                        messages = it.messages + status,
                        messageConsoleEntries = if (console.isEmpty()) it.messageConsoleEntries
                        else it.messageConsoleEntries + (status.timestamp to console),
                        isProcessing = false,
                        agentStatus = null,
                        errorMessage = event.message,
                        streamingContent = null,
                        consoleEntries = it.consoleEntries + AgentConsoleEntry.ErrorEntry(event.message)
                    )
                }
                if (isPersistableSessionId(sessionId)) {
                    viewModelScope.launch {
                        chatRepository?.saveMessage(sessionId, status, console)
                        chatRepository?.updateSessionRunStatus(sessionId, STATUS_INTERRUPTED)
                    }
                }
            }
        }
    }

    private fun handleSwarmEvent(event: SwarmEvent, sessionId: Long, runId: Long) {
        if (runId != activeRunId.get()) return
        when (event) {
            is SwarmEvent.PlanningStarted -> _uiState.update {
                it.copy(agentStatus = "🧠 Planning sub-tasks...", consoleEntries = it.consoleEntries + AgentConsoleEntry.ThinkingEntry(0))
            }
            is SwarmEvent.PlanCompleted -> _uiState.update {
                it.copy(
                    agentStatus = "Plan: ${event.tasks.size} sub-tasks",
                    consoleEntries = it.consoleEntries + AgentConsoleEntry.DeepThinkingEntry(
                        "Plan completed — ${event.tasks.size} sub-tasks:\n" + event.tasks.joinToString("\n") { task -> "  • [${task.id}] ${task.description}" }
                    )
                )
            }
            is SwarmEvent.TaskStarted -> _uiState.update {
                it.copy(
                    agentStatus = "⚙️ Worker: ${event.task.id}",
                    consoleEntries = it.consoleEntries + AgentConsoleEntry.ToolEntry(
                        "worker:${event.task.id}", event.task.description.take(80), event.task.priority
                    )
                )
            }
            is SwarmEvent.TaskCompleted -> {
                val snippet = event.result.lines().firstOrNull()?.take(100).orEmpty()
                _uiState.update { it.copy(consoleEntries = it.consoleEntries + AgentConsoleEntry.ResultEntry(event.task.id, snippet, false, event.result)) }
            }
            is SwarmEvent.TaskFailed -> _uiState.update {
                it.copy(consoleEntries = it.consoleEntries + AgentConsoleEntry.ResultEntry(event.task.id, event.error, true))
            }
            is SwarmEvent.TaskSkipped -> _uiState.update {
                it.copy(consoleEntries = it.consoleEntries + AgentConsoleEntry.ResultEntry(event.task.id, "Skipped: ${event.reason}", true))
            }
            is SwarmEvent.WorkerToolUse -> {
                val params = event.arguments.entries.joinToString(", ") { (k, v) -> "$k=${v.take(40)}" }
                _uiState.update {
                    it.copy(
                        agentStatus = "Worker ${event.task.id}: ${event.toolName}",
                        consoleEntries = it.consoleEntries + AgentConsoleEntry.ToolEntry(event.toolName, params, event.task.priority)
                    )
                }
            }
            is SwarmEvent.WorkerToolResult -> {
                val snippet = event.output.lines().firstOrNull()?.take(100).orEmpty()
                _uiState.update {
                    it.copy(consoleEntries = it.consoleEntries + AgentConsoleEntry.ResultEntry(event.toolName, snippet, event.isError, event.output, 0L))
                }
            }
            is SwarmEvent.WorkerThinking -> _uiState.update {
                it.copy(
                    agentStatus = "Worker ${event.task.id}: Thinking (iter ${event.iteration})...",
                    consoleEntries = it.consoleEntries + AgentConsoleEntry.ThinkingEntry(event.iteration)
                )
            }
            is SwarmEvent.WorkerThinkingBlock -> _uiState.update {
                it.copy(consoleEntries = it.consoleEntries + AgentConsoleEntry.DeepThinkingEntry(event.content))
            }
            is SwarmEvent.WorkerTokenUsage -> _uiState.update {
                it.copy(consoleEntries = it.consoleEntries + AgentConsoleEntry.TokenEntry(event.totalTokens, event.budget))
            }
            is SwarmEvent.WorkerPhaseChanged -> _uiState.update {
                it.copy(
                    agentStatus = "Worker ${event.task.id}: ${event.phase}",
                    consoleEntries = it.consoleEntries + AgentConsoleEntry.PhaseEntry(event.phase, event.detail)
                )
            }
            is SwarmEvent.SynthesisStarted -> _uiState.update {
                it.copy(agentStatus = "🔗 Synthesizing results...", consoleEntries = it.consoleEntries + AgentConsoleEntry.ThinkingEntry(99))
            }
            is SwarmEvent.Completed -> {
                val message = ChatMessage(MessageRole.ASSISTANT, event.summary)
                val console = _uiState.value.consoleEntries
                viewModelScope.launch {
                    chatRepository?.saveMessage(sessionId, message, console)
                    chatRepository?.updateSessionRunStatus(sessionId, STATUS_COMPLETED)
                }
                _uiState.update {
                    it.copy(
                        messages = it.messages + message,
                        messageConsoleEntries = if (console.isEmpty()) it.messageConsoleEntries
                        else it.messageConsoleEntries + (message.timestamp to console),
                        isProcessing = false,
                        agentStatus = null,
                        streamingContent = null,
                        consoleEntries = it.consoleEntries + AgentConsoleEntry.ReplyEntry()
                    )
                }
            }
            is SwarmEvent.WorkerStreamChunk -> _uiState.update {
                it.copy(
                    agentStatus = "⚙️ Worker ${event.task.id}: streaming…",
                    streamingContent = (it.streamingContent ?: "") + event.delta
                )
            }
            is SwarmEvent.Error -> {
                val status = buildRunStatusMessage(event.message)
                _uiState.update {
                    it.copy(
                        messages = it.messages + status,
                        isProcessing = false,
                        agentStatus = null,
                        errorMessage = event.message,
                        streamingContent = null,
                        consoleEntries = it.consoleEntries + AgentConsoleEntry.ErrorEntry(event.message)
                    )
                }
                if (isPersistableSessionId(sessionId)) {
                    viewModelScope.launch {
                        chatRepository?.saveMessage(sessionId, status, _uiState.value.consoleEntries)
                        chatRepository?.updateSessionRunStatus(sessionId, STATUS_INTERRUPTED)
                    }
                }
            }
        }
    }

    private fun buildInterruptionCheckpointMessage(reason: String, state: ChatUiState): ChatMessage? {
        val partial = state.streamingContent?.trim().orEmpty()
        val tail = state.consoleEntries.takeLast(CHECKPOINT_CONSOLE_TAIL_SIZE)
        if (partial.isBlank() && tail.isEmpty()) return null
        val progress = tail.mapNotNull { entry ->
            when (entry) {
                is AgentConsoleEntry.ToolEntry -> "• Tool call: ${entry.toolName}(${sanitizeCheckpointText(entry.params, CHECKPOINT_TOOL_PARAMS_PREVIEW_CHARS)})"
                is AgentConsoleEntry.ResultEntry -> "• Tool result: ${entry.toolName} → ${entry.snippet}"
                is AgentConsoleEntry.ThinkingEntry -> "• Iteration ${entry.iteration}: thinking"
                is AgentConsoleEntry.DeepThinkingEntry -> "• Deep thinking: ${sanitizeCheckpointText(entry.snippet, CHECKPOINT_DEEP_THINKING_PREVIEW_CHARS)}"
                is AgentConsoleEntry.TokenEntry -> "• Tokens used: ${entry.totalTokens}"
                is AgentConsoleEntry.PhaseEntry -> "• Phase: ${entry.phase}${entry.detail?.let { " — ${sanitizeCheckpointText(it, CHECKPOINT_TOOL_PARAMS_PREVIEW_CHARS)}" }.orEmpty()}"
                is AgentConsoleEntry.ErrorEntry -> "• Error observed: ${sanitizeCheckpointText(entry.message, CHECKPOINT_ERROR_PREVIEW_CHARS)}"
                is AgentConsoleEntry.ContextSummaryEntry -> "• Context compressed: ${entry.summary.take(80)}"
                is AgentConsoleEntry.ReplyEntry -> null
            }
        }
        return ChatMessage(
            role = MessageRole.ASSISTANT,
            content = buildString {
                appendLine("Execution checkpoint (auto-saved)")
                appendLine("Reason: $reason")
                if (partial.isNotBlank()) {
                    appendLine(); appendLine("Partial response:"); appendLine(partial)
                }
                if (progress.isNotEmpty()) {
                    appendLine(); appendLine("Latest progress:"); progress.forEach(::appendLine)
                }
                appendLine(); append("Continue from this checkpoint; do not restart previous finished steps.")
            }
        )
    }

    private fun sanitizeCheckpointText(value: String, maxChars: Int): String = value
        .replace(CHECKPOINT_SECRET_ASSIGNMENT_REGEX) { match -> "${match.groupValues[1]}=[REDACTED]" }
        .replace(CHECKPOINT_BEARER_REGEX, "$1[REDACTED]")
        .take(maxChars)

    private fun buildRunStatusMessage(statusText: String) = ChatMessage(
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

    private fun isPersistableSessionId(sessionId: Long?): Boolean = sessionId != null && sessionId >= 0L

    private suspend fun ensureSession(firstMessage: String): Long {
        _uiState.value.currentSessionId?.let { return it }
        val id = chatRepository?.createSession(firstMessage.take(50).ifBlank { "New conversation" }) ?: -1L
        compositeToolManager?.currentSessionId = id
        _uiState.update { it.copy(currentSessionId = id) }
        return id
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun runAutoHealBuild(buildCommand: String = "./gradlew assembleDebug", maxRetries: Int = 5) {
        val scopePath = _uiState.value.targetContext
        if (scopePath == null) {
            _uiState.update { it.copy(errorMessage = "Please set a Target Context before running Auto-Heal Build.") }
            return
        }
        val runId = activeRunId.incrementAndGet()
        currentAgentJob?.cancel()
        _uiState.update {
            it.copy(isProcessing = true, agentStatus = "🔨 Auto-Heal Build starting...", consoleEntries = emptyList(), errorMessage = null)
        }
        currentAgentJob = viewModelScope.launch {
            val useCase = autoHealBuildUseCase
            if (useCase == null) {
                _uiState.update { it.copy(isProcessing = false, errorMessage = "Auto-Heal Build is not available.") }
                return@launch
            }
            useCase.execute(scopePath, buildCommand, maxRetries).collect { event ->
                if (runId != activeRunId.get()) return@collect
                when (event) {
                    is com.omnidev.workspace.domain.engine.AutoHealBuildUseCase.BuildEvent.BuildAttempt -> _uiState.update {
                        it.copy(agentStatus = "🔨 Build attempt ${event.attempt}/${event.maxRetries}...", consoleEntries = it.consoleEntries + AgentConsoleEntry.ThinkingEntry(event.attempt))
                    }
                    is com.omnidev.workspace.domain.engine.AutoHealBuildUseCase.BuildEvent.BuildSuccess -> {
                        val msg = ChatMessage(MessageRole.ASSISTANT, "✅ Build Successful on attempt ${event.attempt}!\n\n${event.output.take(500)}")
                        val session = _uiState.value.currentSessionId ?: ensureSession("Auto-Heal Build")
                        chatRepository?.saveMessage(session, msg)
                        _uiState.update { it.copy(messages = it.messages + msg, isProcessing = false, agentStatus = null, consoleEntries = it.consoleEntries + AgentConsoleEntry.ReplyEntry()) }
                    }
                    is com.omnidev.workspace.domain.engine.AutoHealBuildUseCase.BuildEvent.BuildFailed -> _uiState.update {
                        it.copy(agentStatus = "❌ Build failed (attempt ${event.attempt}), analyzing...", consoleEntries = it.consoleEntries + AgentConsoleEntry.ErrorEntry(event.errors.take(200)))
                    }
                    is com.omnidev.workspace.domain.engine.AutoHealBuildUseCase.BuildEvent.FixAttempt -> _uiState.update {
                        it.copy(agentStatus = "🔧 Applying fix...", consoleEntries = it.consoleEntries + AgentConsoleEntry.ToolEntry("auto_fix", "Fixing build errors", event.attempt))
                    }
                    is com.omnidev.workspace.domain.engine.AutoHealBuildUseCase.BuildEvent.FixApplied -> _uiState.update {
                        it.copy(consoleEntries = it.consoleEntries + AgentConsoleEntry.ResultEntry("auto_fix", event.fixSummary.take(100), false))
                    }
                    is com.omnidev.workspace.domain.engine.AutoHealBuildUseCase.BuildEvent.FixFailed -> _uiState.update {
                        it.copy(consoleEntries = it.consoleEntries + AgentConsoleEntry.ResultEntry("auto_fix", event.error.take(100), true))
                    }
                    is com.omnidev.workspace.domain.engine.AutoHealBuildUseCase.BuildEvent.LoopExhausted -> {
                        val msg = ChatMessage(MessageRole.ASSISTANT, "❌ Auto-Heal Build exhausted all ${event.totalAttempts} attempts. Manual intervention is required.")
                        val session = _uiState.value.currentSessionId ?: ensureSession("Auto-Heal Build")
                        chatRepository?.saveMessage(session, msg)
                        _uiState.update { it.copy(messages = it.messages + msg, isProcessing = false, agentStatus = null, consoleEntries = it.consoleEntries + AgentConsoleEntry.ErrorEntry(msg.content)) }
                    }
                    is com.omnidev.workspace.domain.engine.AutoHealBuildUseCase.BuildEvent.AgentProgress ->
                        handleAgentEvent(event.event, _uiState.value.currentSessionId ?: -1L, runId)
                }
            }
        }
    }

    private fun String.toOmniModeOrNull(): OmniMode? = when (uppercase()) {
        "CHAT" -> OmniMode.CHAT
        "AGENT" -> OmniMode.AGENT
        "SWARM", "TEAM" -> OmniMode.SWARM
        else -> null
    }
}
