package com.omnidev.workspace.ui.assistant

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.assistant.AssistantFlavorPolicy
import com.omnidev.workspace.data.assistant.AssistantScreenState
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.domain.engine.ModeSwitchPermissionStore
import com.omnidev.workspace.ui.chat.*
import com.omnidev.workspace.ui.components.OmniMark
import com.omnidev.workspace.ui.motion.LocalOmniMotion
import com.omnidev.workspace.ui.motion.OmniAnimatedVisibility
import com.omnidev.workspace.ui.motion.OmniEasing
import com.omnidev.workspace.ui.motion.OmniIconButton
import kotlinx.coroutines.launch

/** Presentation shared by the VoiceInteractionSession and translucent Activity. No dialog windows. */
@Composable
internal fun AssistantConversation(
    screen: AssistantScreenState, chat: ChatUiState, flavor: AssistantFlavorPolicy,
    onInputChanged: (String) -> Unit = {}, onSend: () -> Unit = {}, onStop: () -> Unit = {},
    onDismiss: () -> Unit = {}, onExpand: () -> Unit = {}, onMinimize: () -> Unit = {},
    onMicrophone: () -> Unit = {}, onAttach: () -> Unit = {}, onSystemVoice: () -> Unit = {},
    onAccess: () -> Unit = {}, onSetup: () -> Unit = {}, onScreen: (Boolean) -> Unit = {},
    onRemoveImage: () -> Unit = {}, onRemoveFile: (Uri) -> Unit = {},
    onReply: (ChatMessage) -> Unit = {}, onDismissReply: () -> Unit = {},
    onEditLastUser: (String, String) -> Unit = { _, _ -> }, onRegenerateLast: (String) -> Unit = {},
    onClearError: () -> Unit = {},
    onModeDecision: (String, ModeSwitchPermissionStore.Approval?) -> Unit = { _, _ -> },
    extraContent: @Composable () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalOmniMotion.current
    val blocked = screen.saving || screen.minimizing
    val busy = chat.isProcessing || blocked
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var panel by rememberSaveable { mutableStateOf(AssistantPanel.NONE) }
    var filePath by rememberSaveable { mutableStateOf("") }
    var tracks by rememberSaveable { mutableStateOf(false) }
    var presented by remember { mutableStateOf(false) }
    LaunchedEffect(screen.visible) { presented = screen.visible }
    val byId = remember(chat.messages) { chat.messages.associateBy { it.messageId } }
    val lastUser = remember(chat.messages) { chat.messages.lastOrNull { it.role == MessageRole.USER }?.messageId }
    val lastTurn = remember(chat.messages) { LastChatTurn.from(chat.messages) }
    val mediaLayout = remember(chat.messages, chat.messageConsoleEntries, chat.isProcessing) {
        com.omnidev.workspace.ui.chat.MediaConversationLayout.from(chat.messages, chat.messageConsoleEntries.keys, chat.isProcessing)
    }
    val follow = rememberTailFollowState(list, chat.currentSessionId,
        Triple(chat.messages.size, chat.streamingContent, chat.consoleEntries.size), lastUser,
        enabled = panel == AssistantPanel.NONE && chat.pendingConfirmation == null && screen.visible)
    val entries = remember(chat.messageConsoleEntries, chat.consoleEntries) {
        (chat.messageConsoleEntries.values.flatten() + chat.consoleEntries).distinctBy { it.id }
            .sortedWith(compareBy<AgentConsoleEntry> { it.timestamp }.thenBy { it.id })
            .filter { it is AgentConsoleEntry.ToolEntry || it is AgentConsoleEntry.ResultEntry || it is AgentConsoleEntry.PhaseEntry || it is AgentConsoleEntry.ErrorEntry }
            .map(ConsoleRedactor::entry)
    }
    val console = remember(chat.consoleEntries) { chat.consoleEntries.map(ConsoleRedactor::entry) }
    LaunchedEffect(busy, chat.pendingConfirmation) {
        if (busy || chat.pendingConfirmation != null) panel = AssistantPanel.NONE
    }
    fun suggest(prompt: String, attachScreen: Boolean = false) {
        onInputChanged(prompt)
        if (attachScreen) onScreen(false)
        focus.requestFocus(); keyboard?.show()
    }
    // The window owns safe drawing/IME padding once, before measuring the available panel height.
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val compact = maxHeight < 400.dp
        val margin = if (compact) 4.dp else 12.dp
        val panelHeight = ((maxHeight - margin * 2).coerceAtLeast(0.dp) * if (compact) 1f else .86f).coerceAtMost(640.dp)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .14f)).clickable(onClick = onDismiss)
            .semantics { contentDescription = "Dismiss floating assistant" })
        OmniAnimatedVisibility(if (motion.reduced) screen.visible else presented, Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = margin),
            enter = fadeIn(tween(motion.responseMillis, easing = OmniEasing)) +
                slideInVertically(tween(motion.navigationMillis, easing = OmniEasing)) { it / 8 },
            exit = fadeOut(tween(motion.responseMillis / 2)) +
                slideOutVertically(tween(motion.navigationMillis, easing = OmniEasing)) { it / 8 }) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().height(panelHeight).testTag("floating-assistant"),
                shape = RoundedCornerShape(28.dp), color = colors.surface, shadowElevation = 8.dp) {
                Column {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(32.dp, 3.dp)
                        .background(colors.outlineVariant, CircleShape))
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                        .testTag("assistant-header"), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).background(colors.primaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            OmniMark(Modifier.size(26.dp))
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text("Omni", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when {
                                screen.listening -> "Listening…"
                                screen.minimizing -> "Starting bubble…"
                                screen.saving -> "Saving…"
                                chat.isProcessing -> chat.agentStatus ?: "Working…"
                                else -> "Here to help"
                            }, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (flavor.allowBubble) OmniIconButton(onClick = onMinimize, enabled = !blocked) {
                            Icon(Icons.Default.Remove, "Minimize to floating bubble")
                        }
                        AssistantExpandButton(screen.saving, screen.minimizing, chat.isProcessing, chat.pendingConfirmation != null, onExpand)
                        OmniIconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close and save conversation") }
                    }
                    val confirmation = chat.pendingConfirmation
                    if (confirmation != null) {
                        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            ConfirmationGateCard(confirmation, Modifier.fillMaxSize())
                        }
                    } else {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (panel == AssistantPanel.NONE) {
                                LazyColumn(Modifier.fillMaxSize().testTag("assistant-messages"), state = list,
                                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                    item(key = "local-work") { extraContent() }
                                    if (chat.messages.isEmpty() && !busy) item(key = "welcome") {
                                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text("A little help,\nright where you are.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                            Text("Ask about your screen or give Omni a task.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                                            AssistantSuggestion("Ask about this screen") { suggest("Explain what's on this screen.", true) }
                                            AssistantSuggestion("Help with a task") { suggest("Help me with this task: ") }
                                        }
                                    }
                                    items(mediaLayout.transcript, key = { it.messageId }, contentType = { it.role }) { message ->
                                        MessageBubble(message, chat.messageConsoleEntries[message.timestamp],
                                            message.replyToMessageId?.let(byId::get), onReply = { onReply(it); focus.requestFocus(); keyboard?.show() },
                                            onEdit = if (message.messageId == lastTurn?.user?.messageId) ({ text -> onEditLastUser(message.messageId, text) }) else null,
                                            onRegenerate = if (message.messageId == lastTurn?.lastAssistantId) ({ onRegenerateLast(message.messageId) }) else null,
                                            actionsEnabled = !busy && !chat.isImportingAttachments)
                                        message.executionRequest?.let { request ->
                                            ModeSwitchRequestCard(request, !busy,
                                                onOnce = { onModeDecision(message.messageId, ModeSwitchPermissionStore.Approval.ONCE) },
                                                onAlwaysTransition = { onModeDecision(message.messageId, ModeSwitchPermissionStore.Approval.ALWAYS_THIS_TRANSITION) },
                                                onAllSession = { onModeDecision(message.messageId, ModeSwitchPermissionStore.Approval.ALL_THIS_SESSION) },
                                                onDeny = { onModeDecision(message.messageId, null) })
                                        }
                                    }
                                    chat.streamingContent?.takeIf { it.isNotBlank() }?.let { content ->
                                        item(key = "streaming") { StreamingMessageBubble(content) }
                                    }
                                    if (console.isNotEmpty() && (chat.isProcessing || chat.messageConsoleEntries.values.none { saved -> saved.any { it.id == chat.consoleEntries.first().id } })) {
                                        item(key = "live-console") { AgentLiveConsole(console, chat.isProcessing) }
                                    }
                                    items(mediaLayout.liveOutputs, key = { it.messageId }, contentType = { "media-output" }) { message ->
                                        MessageBubble(message, onReply = { onReply(it); focus.requestFocus(); keyboard?.show() },
                                            onRegenerate = if (message.messageId == lastTurn?.lastAssistantId) ({ onRegenerateLast(message.messageId) }) else null,
                                            actionsEnabled = !busy && !chat.isImportingAttachments)
                                    }
                                    if (entries.isNotEmpty()) item(key = "activity-control") {
                                        TextButton(onClick = { tracks = !tracks }) {
                                            Icon(Icons.Default.Timeline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                            Text(if (tracks) "Hide activity" else "Activity · ${entries.size} events")
                                        }
                                    }
                                    if (tracks) items(entries, key = { "track_${it.id}" }) { AssistantTrack(it) }
                                    if (busy && chat.streamingContent.isNullOrBlank()) item(key = "progress") {
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                    }
                                    (screen.message ?: chat.errorMessage)?.let { message -> item(key = "error") {
                                        Surface(shape = RoundedCornerShape(16.dp), color = colors.errorContainer) {
                                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(message, style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
                                                Row {
                                                    if (screen.screenshot == null && message.contains("screen", true)) TextButton(onClick = onSetup) { Text("Screen access settings") }
                                                    TextButton(onClick = onClearError) { Text("Dismiss") }
                                                }
                                            }
                                        }
                                    } }
                                }
                                if (!follow.following && (chat.messages.isNotEmpty() || chat.isProcessing)) {
                                    FilledTonalIconButton(onClick = { follow.resume(); scope.launch {
                                        if (list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
                                    } }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                                        Icon(Icons.Default.KeyboardArrowDown, "Jump to latest assistant message")
                                    }
                                }
                            } else AssistantInlinePanel(panel, filePath, { filePath = it },
                                onAddPath = { onInputChanged(screen.input + "\n[User-provided file path: ${filePath.trim()}]"); filePath = ""; panel = AssistantPanel.NONE },
                                onChoosePath = { panel = AssistantPanel.FILE_PATH },
                                onAttach = { panel = AssistantPanel.NONE; onAttach() },
                                onSystemVoice = { panel = AssistantPanel.NONE; onSystemVoice() },
                                onClose = { panel = AssistantPanel.NONE }, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                onMicrophone = { panel = AssistantPanel.NONE; onMicrophone() },
                                onScreen = { panel = AssistantPanel.NONE; onScreen(false) },
                                onSelectArea = { panel = AssistantPanel.NONE; onScreen(true) },
                                onAccess = if (flavor.allowScreenActions) ({ panel = AssistantPanel.NONE; onAccess() }) else null)
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("assistant-composer"),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (chat.replyingTo != null || screen.attachment != null || screen.files.isNotEmpty()) {
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    chat.replyingTo?.let { reply ->
                                        InputChip(false, onDismissReply, label = { Text("Replying to ${if (reply.role == MessageRole.USER) "you" else "Omni"}") },
                                            modifier = Modifier.semantics { contentDescription = "Cancel reply" }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
                                    }
                                    if (screen.attachment != null) InputChip(false, onRemoveImage, enabled = !busy,
                                        label = { Text("Screen attached") }, modifier = Modifier.semantics { contentDescription = "Remove screen image" },
                                        leadingIcon = { Icon(Icons.Default.Screenshot, null, Modifier.size(16.dp)) }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
                                    screen.files.forEach { file -> key(file.uri) {
                                        InputChip(false, { onRemoveFile(file.uri) }, enabled = !busy,
                                            label = { Text(file.displayName, Modifier.widthIn(max = 160.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            modifier = Modifier.semantics { contentDescription = "Remove ${file.displayName}" },
                                            leadingIcon = { Icon(Icons.Default.AttachFile, null, Modifier.size(16.dp)) }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
                                    } }
                                }
                            }
                            if (!compact || screen.listening) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (!compact) {
                                    AssistChip({ onScreen(false) }, { Text("Screen") }, enabled = !busy,
                                        leadingIcon = { Icon(Icons.Default.Screenshot, null, Modifier.size(16.dp)) })
                                    AssistChip({ onScreen(true) }, { Text("Select area") }, enabled = !busy,
                                        leadingIcon = { Icon(Icons.Default.CropFree, null, Modifier.size(16.dp)) })
                                }
                                AssistChip(onMicrophone, { Text(if (screen.listening) "Listening…" else "Voice") }, enabled = screen.listening || !busy,
                                    modifier = Modifier.semantics { contentDescription = if (screen.listening) "Stop listening" else "Speak your question" },
                                    leadingIcon = { Icon(if (screen.listening) Icons.Default.MicOff else Icons.Default.Mic, null, Modifier.size(16.dp)) })
                            }
                            ChatComposerSurface(screen.input, onInputChanged, onSend, onStop, chat.isProcessing,
                                onTools = { panel = if (panel == AssistantPanel.NONE) AssistantPanel.ATTACHMENTS else AssistantPanel.NONE },
                                sendEnabled = screen.input.isNotBlank(), focusRequester = focus, compact = compact,
                                editorEnabled = !blocked, toolsEnabled = !busy, actionEnabled = !blocked,
                                toolsDescription = "Assistant tools", sendDescription = "Send question", stopDescription = "Stop request")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantSuggestion(title: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Default.NorthEast, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
