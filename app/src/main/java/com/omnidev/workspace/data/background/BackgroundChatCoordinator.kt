package com.omnidev.workspace.data.background

import android.content.Context
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.domain.engine.IntentClassifier
import com.omnidev.workspace.domain.engine.OmniMode
import com.omnidev.workspace.ui.chat.AgentConsoleEntry
import com.omnidev.workspace.ui.chat.ChatViewModel

/**
 * Process-wide bridge between the UI-owned ChatViewModel and the durable background runtime.
 *
 * Existing chat execution stays untouched while the Activity/process is healthy. The foreground
 * runtime mirrors its StateFlow into notifications and keeps a strong reference to the ViewModel
 * only while an active run exists. After process death there is no live bridge, so the durable
 * runtime safely takes ownership and resumes from Room.
 */
object BackgroundChatCoordinator {
    data class LiveSnapshot(
        val sessionId: Long,
        val isProcessing: Boolean,
        val mode: OmniMode,
        val status: String,
        val partialText: String,
        val finalText: String,
        val error: String?
    )

    @Volatile private var appContext: Context? = null
    @Volatile private var chatViewModel: ChatViewModel? = null
    @Volatile private var hostAttached: Boolean = false

    fun attach(context: Context, viewModel: ChatViewModel) {
        appContext = context.applicationContext
        chatViewModel = viewModel
        hostAttached = true
        BackgroundChatRuntime.ensureStarted(context.applicationContext)
    }

    /**
     * The manually-created ChatViewModel is intentionally retained while work is still active.
     * That lets a run continue after the Activity is closed without tying it to a visible screen.
     */
    fun onHostDestroyed(isChangingConfigurations: Boolean) {
        if (isChangingConfigurations) return
        hostAttached = false
        releaseViewModelIfIdle()
    }

    suspend fun armFromUserMessage(sessionId: Long, message: ChatMessage) {
        val context = appContext ?: return
        val state = chatViewModel?.uiState?.value
        val exactState = state?.takeIf { it.currentSessionId == sessionId }
        val mode = exactState?.activeMode ?: IntentClassifier.classify(message.content)
        val resolvedMode = if (mode == OmniMode.AUTO) IntentClassifier.classify(message.content) else mode
        val scope = exactState?.targetContext.orEmpty()
        val run = BackgroundChatTaskStore.arm(
            context = context,
            sessionId = sessionId,
            userMessageId = message.messageId,
            userTimestamp = message.timestamp,
            mode = resolvedMode.name,
            scopePath = scope,
            ownerUi = exactState != null
        )
        BackgroundChatRuntime.ensureStarted(context)
        BackgroundChatRuntime.kick(run.token)
    }

    suspend fun armAcceptedExecution(
        sessionId: Long,
        origin: ChatMessage,
        requestedMode: String
    ) {
        val context = appContext ?: return
        val state = chatViewModel?.uiState?.value
        val mode = runCatching { OmniMode.valueOf(requestedMode) }
            .getOrDefault(OmniMode.AGENT)
        val run = BackgroundChatTaskStore.arm(
            context = context,
            sessionId = sessionId,
            userMessageId = origin.messageId,
            userTimestamp = origin.timestamp,
            mode = mode.name,
            scopePath = state?.targetContext.orEmpty(),
            ownerUi = state?.currentSessionId == sessionId
        )
        BackgroundChatRuntime.ensureStarted(context)
        BackgroundChatRuntime.kick(run.token)
    }

    fun hasLiveOwner(run: BackgroundChatRun): Boolean {
        val state = chatViewModel?.uiState?.value ?: return false
        return state.currentSessionId == run.sessionId && run.ownerUi
    }

    fun liveSnapshot(run: BackgroundChatRun): LiveSnapshot? {
        val state = chatViewModel?.uiState?.value ?: return null
        if (state.currentSessionId != run.sessionId || !run.ownerUi) return null

        val lastConsole = state.consoleEntries.lastOrNull()
        val status = state.agentStatus
            ?: consoleStatus(lastConsole)
            ?: if (state.isProcessing) "Working in background…" else "Finishing…"
        val partial = state.streamingContent.orEmpty().takeLast(1_200)
        val lastAssistant = state.messages.lastOrNull { it.role.name == "ASSISTANT" }?.content.orEmpty()

        return LiveSnapshot(
            sessionId = run.sessionId,
            isProcessing = state.isProcessing,
            mode = state.activeMode,
            status = status,
            partialText = partial,
            finalText = lastAssistant,
            error = state.errorMessage
        )
    }

    fun cancelLiveRun(run: BackgroundChatRun): Boolean {
        val vm = chatViewModel ?: return false
        val state = vm.uiState.value
        if (state.currentSessionId != run.sessionId || !state.isProcessing) return false
        vm.cancelCurrentRun()
        return true
    }

    fun releaseViewModelIfIdle() {
        val context = appContext
        val noRuns = context == null || BackgroundChatTaskStore.active(context).isEmpty()
        if (!hostAttached && noRuns) chatViewModel = null
    }

    private fun consoleStatus(entry: AgentConsoleEntry?): String? = when (entry) {
        is AgentConsoleEntry.ThinkingEntry -> "Thinking • iteration ${entry.iteration}"
        is AgentConsoleEntry.DeepThinkingEntry -> "Deep thinking…"
        is AgentConsoleEntry.ToolEntry -> "Using ${entry.toolName}…"
        is AgentConsoleEntry.ResultEntry -> if (entry.isError) "${entry.toolName} returned an error" else "${entry.toolName} completed"
        is AgentConsoleEntry.TokenEntry -> "Thinking • ${entry.totalTokens} tokens"
        is AgentConsoleEntry.PhaseEntry -> entry.detail?.let { "${entry.phase} • ${it.take(90)}" } ?: entry.phase
        is AgentConsoleEntry.ReplyEntry -> "Preparing final reply…"
        is AgentConsoleEntry.ErrorEntry -> "Error • ${entry.message.take(120)}"
        is AgentConsoleEntry.ContextSummaryEntry -> "Compressing context…"
        null -> null
    }
}
