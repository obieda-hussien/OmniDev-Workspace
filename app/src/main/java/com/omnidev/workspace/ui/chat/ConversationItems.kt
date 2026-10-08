package com.omnidev.workspace.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.domain.engine.ModeSwitchPermissionStore

/** One transcript renderer for full chat and the screen assistant; console always precedes output. */
internal fun LazyListScope.conversationItems(
    state: ChatUiState,
    layout: MediaConversationLayout,
    runningConsole: List<AgentConsoleEntry>,
    onSuggestion: (String) -> Unit,
    onReply: (ChatMessage) -> Unit,
    onEditLastUser: (String, String) -> Unit,
    onRegenerateLast: (String) -> Unit,
    onClearError: () -> Unit,
    onModeDecision: (String, ModeSwitchPermissionStore.Approval?) -> Unit,
    onBrowser: (() -> Unit)? = null,
    actionsEnabled: Boolean = !state.isProcessing && !state.isImportingAttachments,
    leadingContent: (@Composable () -> Unit)? = null,
    welcomeSuggestions: List<Pair<String, String>>? = null
) {
    val byId = state.messages.associateBy { it.messageId }
    val lastTurn = LastChatTurn.from(state.messages)
    leadingContent?.let { item(key = "local-work") { it() } }
    if (state.messages.isEmpty() && !state.isProcessing) item(key = "welcome") {
        EmptyStateContent(state.activeMode, onSuggestion, welcomeSuggestions)
    }
    items(layout.transcript, key = { it.messageId }, contentType = { it.role }) { message ->
        MessageBubble(message, state.messageConsoleEntries[message.timestamp], message.replyToMessageId?.let(byId::get),
            onReply = onReply, onOpenBrowser = onBrowser,
            onEdit = if (message.messageId == lastTurn?.user?.messageId) ({ text -> onEditLastUser(message.messageId, text) }) else null,
            onRegenerate = if (message.messageId == lastTurn?.lastAssistantId) ({ onRegenerateLast(message.messageId) }) else null,
            actionsEnabled = actionsEnabled)
        message.executionRequest?.let { request ->
            if (message.role == MessageRole.ASSISTANT) ModeSwitchRequestCard(request, actionsEnabled,
                onOnce = { onModeDecision(message.messageId, ModeSwitchPermissionStore.Approval.ONCE) },
                onAlwaysTransition = { onModeDecision(message.messageId, ModeSwitchPermissionStore.Approval.ALWAYS_THIS_TRANSITION) },
                onAllSession = { onModeDecision(message.messageId, ModeSwitchPermissionStore.Approval.ALL_THIS_SESSION) },
                onDeny = { onModeDecision(message.messageId, null) })
        }
    }
    state.errorMessage?.let { error -> item(key = "error", contentType = "error") { ChatErrorBanner(error, onClearError) } }
    if (state.isProcessing && runningConsole.isNotEmpty()) item(key = "live-console", contentType = "console") {
        AgentLiveConsole(runningConsole, true, onOpenBrowser = onBrowser)
    }
    if (state.isProcessing) {
        val content = state.streamingContent
        if (!content.isNullOrBlank()) item(key = "streaming", contentType = "streaming") { StreamingMessageBubble(content) }
        else item(key = "working", contentType = "status") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.MoreHoriz, null, tint = MaterialTheme.colorScheme.primary)
                Text(state.agentStatus ?: "Omni is thinking…", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    items(layout.liveOutputs, key = { it.messageId }, contentType = { "media-output" }) { message ->
        MessageBubble(message, onReply = onReply, onOpenBrowser = onBrowser,
            onRegenerate = if (message.messageId == lastTurn?.lastAssistantId) ({ onRegenerateLast(message.messageId) }) else null,
            actionsEnabled = actionsEnabled)
    }
}
