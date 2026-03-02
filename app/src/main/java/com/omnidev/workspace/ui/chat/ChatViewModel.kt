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
import com.omnidev.workspace.data.repository.ChatRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.domain.attachment.AttachmentProcessor
import com.omnidev.workspace.domain.engine.AgentEvent
import com.omnidev.workspace.domain.engine.AgentPipeline
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
    val currentSessionId: Long? = null
)

/**
 * ViewModel for the Omni-Chat interface, managing conversation state
 * and agent pipeline execution.
 *
 * @param attachmentProcessor Optional processor for reading image bytes for vision models.
 */
class ChatViewModel(
    private val settingsRepository: SettingsRepository,
    private val agentPipeline: AgentPipeline,
    private val chatRepository: ChatRepository? = null,
    private val attachmentProcessor: AttachmentProcessor? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        loadTargetContext()
        observeSessions()
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

    /** Opens or closes the history drawer. */
    fun setDrawerOpen(open: Boolean) {
        _uiState.update { it.copy(isDrawerOpen = open) }
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
                isDrawerOpen = false
            )
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
     * Sends the current input message (and any pending attachments) to the agent pipeline.
     */
    fun sendMessage() {
        val input = _uiState.value.inputText.trim()
        if (input.isEmpty()) return

        val scopePath = _uiState.value.targetContext
        if (scopePath == null) {
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
                agentStatus = "Starting agent...",
                errorMessage = null,
                consoleEntries = emptyList(), // fresh console for each run
                pendingAttachments = emptyList() // clear after send
            )
        }

        viewModelScope.launch {
            // Ensure the session is persisted before saving any messages
            val sessionId = ensureSession(input)
            chatRepository?.saveMessage(sessionId, userMessage)

            val modelId = settingsRepository
                .observeModelIdForRole(com.omnidev.workspace.data.model.ModelRole.AGENT)
                .first()

            val deepThinking = settingsRepository.observeDeepThinking().first()

            // Resolve base64 image data for vision-capable attachments
            val imageAttachments: List<AttachmentMeta> = attachments
                .mapNotNull { pending ->
                    val uri = pending.uri
                    // Read base64 data for image attachments only
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

            agentPipeline.execute(
                userMessage = input,
                conversationHistory = _uiState.value.messages.dropLast(1),
                modelId = modelId,
                scopePath = scopePath,
                enableDeepThinking = deepThinking,
                userAttachments = imageAttachments
            ).collect { event ->
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

                    is AgentEvent.FinalAnswer -> {
                        val assistantMessage = ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = event.content
                        )
                        chatRepository?.saveMessage(sessionId, assistantMessage)
                        _uiState.update {
                            it.copy(
                                messages = it.messages + assistantMessage,
                                isProcessing = false,
                                agentStatus = null,
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
                                consoleEntries = it.consoleEntries +
                                    AgentConsoleEntry.ErrorEntry(event.message)
                            )
                        }
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
        _uiState.update { it.copy(currentSessionId = newId) }
        return newId
    }

    /**
     * Clears the error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
