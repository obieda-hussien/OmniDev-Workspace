package com.omnidev.workspace.data.background

import android.content.Context
import com.omnidev.workspace.OmniDevApp
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.domain.engine.IntentClassifier
import com.omnidev.workspace.domain.engine.OmniMode
import com.omnidev.workspace.ui.chat.AgentConsoleEntry
import com.omnidev.workspace.ui.chat.ChatViewModel
import java.lang.ref.WeakReference

/**
 * Optional process-wide bridge between a visible ChatViewModel and the durable runtime.
 *
 * Room + [BackgroundChatTaskStore] remain the source of truth. A weak UI reference exposes rich
 * live status while the screen exists, and a temporary strong reference is held only for active
 * work so a user-initiated run can continue when the Activity is closed. After process death no
 * UI owner survives and [BackgroundChatRecoveryExecutor] resumes from the durable checkpoint.
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
    @Volatile private var weakViewModel: WeakReference<ChatViewModel>? = null
    @Volatile private var activeStrongViewModel: ChatViewModel? = null

    fun attach(context: Context, viewModel: ChatViewModel) {
        val app = context.applicationContext
        appContext = app
        weakViewModel = WeakReference(viewModel)
        BackgroundChatRuntime.ensureStarted(app)
    }

    fun attach(viewModel: ChatViewModel) {
        val context = resolveContext() ?: return
        attach(context, viewModel)
    }

    suspend fun armFromUserMessage(sessionId: Long, message: ChatMessage) {
        val context = resolveContext() ?: return
        // Initialize process recovery before creating a fresh UI-owned run. This ensures only stale
        // pre-existing runs are reclassified as recovery work.
        BackgroundChatRuntime.ensureStarted(context)

        val vm = currentViewModel()
        val state = vm?.uiState?.value
        val exactState = state?.takeIf { it.currentSessionId == sessionId }
        val mode = exactState?.activeMode ?: IntentClassifier.classify(message.content)
        val resolvedMode = if (mode == OmniMode.AUTO) IntentClassifier.classify(message.content) else mode
        val scope = exactState?.targetContext.orEmpty()
        if (exactState != null) activeStrongViewModel = vm

        val run = BackgroundChatTaskStore.arm(
            context = context,
            sessionId = sessionId,
            userMessageId = message.messageId,
            userTimestamp = message.timestamp,
            mode = resolvedMode.name,
            scopePath = scope,
            ownerUi = exactState != null
        )
        BackgroundChatRuntime.kick(run.token)
    }

    suspend fun armAcceptedExecution(
        sessionId: Long,
        origin: ChatMessage,
        requestedMode: String
    ) {
        val context = resolveContext() ?: return
        BackgroundChatRuntime.ensureStarted(context)

        val vm = currentViewModel()
        val state = vm?.uiState?.value
        val mode = runCatching { OmniMode.valueOf(requestedMode) }.getOrDefault(OmniMode.AGENT)
        val ownsSession = state?.currentSessionId == sessionId
        if (ownsSession) activeStrongViewModel = vm

        val run = BackgroundChatTaskStore.arm(
            context = context,
            sessionId = sessionId,
            userMessageId = origin.messageId,
            userTimestamp = origin.timestamp,
            mode = mode.name,
            scopePath = state?.targetContext.orEmpty(),
            ownerUi = ownsSession
        )
        BackgroundChatRuntime.kick(run.token)
    }

    fun hasLiveOwner(run: BackgroundChatRun): Boolean {
        val state = currentViewModel()?.uiState?.value ?: return false
        return state.currentSessionId == run.sessionId && run.ownerUi
    }

    fun liveSnapshot(run: BackgroundChatRun): LiveSnapshot? {
        val state = currentViewModel()?.uiState?.value ?: return null
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
        val vm = currentViewModel() ?: return false
        val state = vm.uiState.value
        if (state.currentSessionId != run.sessionId || !state.isProcessing) return false
        vm.cancelCurrentRun()
        return true
    }

    fun releaseViewModelIfIdle() {
        val context = resolveContext()
        if (context == null || BackgroundChatTaskStore.active(context).isEmpty()) {
            activeStrongViewModel = null
        }
    }

    private fun currentViewModel(): ChatViewModel? = activeStrongViewModel ?: weakViewModel?.get()

    private fun resolveContext(): Context? = appContext
        ?: runCatching { OmniDevApp.instance.applicationContext }.getOrNull()?.also { appContext = it }

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
