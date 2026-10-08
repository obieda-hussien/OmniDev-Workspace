package com.omnidev.workspace.ui.chat

import com.omnidev.workspace.ui.components.OmniMark

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.domain.engine.OmniMode
import com.omnidev.workspace.domain.model.ChatSettings
import com.omnidev.workspace.ui.motion.OmniAnimatedVisibility
import com.omnidev.workspace.ui.motion.OmniIconButton

internal fun modeTitle(mode: OmniMode) = when (mode) {
    OmniMode.CHAT -> "Chat"
    OmniMode.AGENT -> "Agent"
    OmniMode.SWARM -> "Multi-agent"
    OmniMode.AUTO -> "Auto"
}

private fun modeDescription(mode: OmniMode) = when (mode) {
    OmniMode.CHAT -> "Ask, explore and think things through"
    OmniMode.AGENT -> "Plan and carry out a task with one agent"
    OmniMode.SWARM -> "Split a bigger task across coordinated agents"
    OmniMode.AUTO -> "Omni chooses a mode for the task"
}

@Composable
private fun ModeIcon(mode: OmniMode, modifier: Modifier = Modifier) {
    if (mode == OmniMode.AUTO) { OmniMark(modifier); return }
    Icon(when (mode) {
        OmniMode.CHAT -> Icons.Default.QuestionAnswer
        OmniMode.AGENT -> Icons.Default.SmartToy
        OmniMode.SWARM -> Icons.Default.Hub
        OmniMode.AUTO -> Icons.Default.SmartToy
    }, null, modifier, tint = MaterialTheme.colorScheme.primary)
}

/** Lives in the app bar rather than consuming a separate row above the conversation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModeSelector(activeMode: OmniMode, onModeSelected: (OmniMode) -> Unit, enabled: Boolean = true,
    isProcessing: Boolean = false, conversationTitle: String = "Omni") {
    var open by rememberSaveable { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(onClick = { focus.clearFocus(); keyboard?.hide(); open = true }, enabled = enabled, shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface, modifier = Modifier.heightIn(min = 48.dp)
            .semantics { contentDescription = "Choose conversation mode" }) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(conversationTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            ChatControlTransition(activeMode, "conversation mode") { mode ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(modeTitle(mode), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("How would you like to work?", style = MaterialTheme.typography.headlineSmall)
                Text(if (isProcessing) "Switching modes saves a checkpoint and stops the current run."
                    else "Choose a mode for this conversation.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            items(listOf(OmniMode.CHAT, OmniMode.AGENT, OmniMode.SWARM), key = { it.name }) { mode ->
                Surface(shape = RoundedCornerShape(20.dp), color = if (mode == activeMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().selectable(selected = mode == activeMode, role = Role.RadioButton,
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onModeSelected(mode); open = false })) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ModeIcon(mode)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(modeTitle(mode), style = MaterialTheme.typography.titleMedium)
                            Text(modeDescription(mode), style = MaterialTheme.typography.bodyMedium)
                            if (mode == OmniMode.SWARM) Text("More model calls for coordinated work", style = MaterialTheme.typography.bodySmall)
                        }
                        RadioButton(selected = mode == activeMode, onClick = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantSignature(status: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            OmniMark(Modifier.size(20.dp))
        }
        Text("Omni", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** User bubbles wrap short messages; assistant text has a quiet, full-width reading surface. */
@Composable
internal fun MessageBubble(message: ChatMessage, consoleEntries: List<AgentConsoleEntry>? = null,
    replyToMessage: ChatMessage? = null, onReply: (ChatMessage) -> Unit = {}, onOpenBrowser: (() -> Unit)? = null,
    onEdit: ((String) -> Unit)? = null, onRegenerate: (() -> Unit)? = null, actionsEnabled: Boolean = true) {
    val user = message.role == MessageRole.USER
    val mediaOnly = !user && message.attachments.isNotEmpty() && message.content in setOf("Media generation", "File attached.")
    val parsed = remember(message.content, user) { if (user) null else MessageFormatter.parse(message.content) }
    val text = parsed?.cleanText?.ifBlank { null } ?: message.content
    val context = LocalContext.current
    var expanded by rememberSaveable(message.messageId) { mutableStateOf(false) }
    var editing by rememberSaveable(message.messageId) { mutableStateOf(false) }
    var editText by rememberSaveable(message.messageId) { mutableStateOf(LastChatTurn.from(listOf(message))?.editableText.orEmpty()) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleWidth = if (user) (maxWidth * .88f).coerceAtMost(560.dp) else maxWidth.coerceAtMost(800.dp)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!user && !mediaOnly) AssistantSignature()
            if (!user && !consoleEntries.isNullOrEmpty()) AgentLiveConsole(
                entries = remember(consoleEntries) { consoleEntries.map(ConsoleRedactor::entry) }, isRunning = false, onOpenBrowser = onOpenBrowser)
            parsed?.thoughtBlocks?.forEach { ExpandableBlock("Thought process", it) }
            parsed?.toolBlocks?.forEach { block ->
                ExpandableBlock("Tool execution", buildString { append(block.toolCode); block.observation?.let { append("\n\nOutput:\n"); append(it) } })
            }
            Surface(shape = RoundedCornerShape(22.dp),
                color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(max = bubbleWidth)) {
                Column(Modifier.padding(horizontal = if (user) 16.dp else 0.dp, vertical = if (user) 12.dp else 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    replyToMessage?.let { ReplyQuote(it) }
                    ChatMessageMedia(message)
                    if (!mediaOnly) SelectionContainer {
                        if (user) Text(if (text.length > 500 && !expanded) text.take(500) + "…" else text,
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
                        else MarkdownText(text, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (user && text.length > 500) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Read full message") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!mediaOnly) OmniIconButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Omni", text))
                    Toast.makeText(context, "Message copied", Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Default.ContentCopy, "Copy message", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                OmniIconButton(onClick = { onReply(message) }) {
                    Icon(Icons.AutoMirrored.Filled.Reply, "Reply to message", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                onEdit?.let {
                    OmniIconButton(onClick = { editing = true }, enabled = actionsEnabled) {
                        Icon(Icons.Default.Edit, "Edit last message", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                onRegenerate?.let { regenerate ->
                    OmniIconButton(onClick = regenerate, enabled = actionsEnabled) {
                        Icon(Icons.Default.Refresh, "Regenerate last response", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (editing && onEdit != null) {
                LastUserMessageEditor(editText, { editText = it }, actionsEnabled,
                    canSave = editText.isNotBlank() || message.attachments.isNotEmpty(),
                    attachmentCount = message.attachments.size,
                    onSave = { onEdit(editText) },
                    onCancel = { editing = false; editText = LastChatTurn.from(listOf(message))?.editableText.orEmpty() })
            }
        }
    }
}

@Composable
private fun ReplyQuote(message: ChatMessage) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Replying to ${if (message.role == MessageRole.USER) "you" else "Omni"}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Text(message.content.take(160).replace("\n", " "), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
        }
    }
}

@Composable
internal fun StreamingMessageBubble(content: String) {
    val displayed = rememberStreamedText(content)
    Column(Modifier.widthIn(max = 800.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AssistantSignature("Responding")
        SelectionContainer { MarkdownText(displayed, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun ExpandableBlock(title: String, content: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column {
            Surface(onClick = { expanded = !expanded }, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Collapse $title" else "Expand $title")
                }
            }
            OmniAnimatedVisibility(expanded) {
                SelectionContainer { Text(content.trim(), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace, textDirection = TextDirection.Content)) }
            }
        }
    }
}

/** A single-row composer grows for multiline input without moving its editing node. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatInputBar(inputText: String, onInputChanged: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit = {},
    isProcessing: Boolean, pendingAttachments: List<PendingAttachment> = emptyList(), onAttachClick: () -> Unit = {},
    onRemoveAttachment: (Uri) -> Unit = {}, replyingTo: ChatMessage? = null, onDismissReply: () -> Unit = {},
    chatSettings: ChatSettings = ChatSettings(), onUpdateChatSettings: (ChatSettings) -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() }, scopeLabel: String? = null,
    onChooseScope: () -> Unit = {}, compact: Boolean = false, showScopeChooser: Boolean = true,
    allowSteering: Boolean = false, submittedRevision: Long = 0L, appliedRevision: Long = 0L) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var previousReply by remember { mutableStateOf(replyingTo) }
    LaunchedEffect(replyingTo) { if (replyingTo != null) previousReply = replyingTo }
    if (showSettings) ChatSettingsSheet(chatSettings, { showSettings = false }, onUpdateChatSettings)
    if (showTools) ModalBottomSheet(onDismissRequest = { showTools = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding()) {
            Text("Add to your conversation", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
            ChatToolRow("Attach files", "Images, documents or code", Icons.Default.AttachFile) { showTools = false; onAttachClick() }
            if (showScopeChooser) ChatToolRow("Project scope", scopeLabel ?: "Choose a folder for file work", Icons.Default.FolderOpen) { showTools = false; onChooseScope() }
            ChatToolRow("Chat tools and skills", "Choose available tools and capabilities", Icons.Default.Tune) { showTools = false; showSettings = true }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("conversation-composer").padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (compact) {
            if (replyingTo != null || pendingAttachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                replyingTo?.let { reply ->
                    InputChip(selected = false, onClick = onDismissReply, modifier = Modifier.semantics { contentDescription = "Cancel reply" },
                        label = { Text("Replying to ${if (reply.role == MessageRole.USER) "you" else "Omni"}") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.size(16.dp)) },
                        trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
                }
                AttachmentChips(pendingAttachments, onRemoveAttachment)
            }
        } else {
            OmniAnimatedVisibility(replyingTo != null) {
                (replyingTo ?: previousReply)?.let { message ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Replying to ${if (message.role == MessageRole.USER) "you" else "Omni"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(message.content.take(160).replace("\n", " "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
                            }
                            OmniIconButton(onClick = onDismissReply) { Icon(Icons.Default.Close, "Cancel reply") }
                        }
                    }
                }
            }
            if (pendingAttachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AttachmentChips(pendingAttachments, onRemoveAttachment)
            }
            scopeLabel?.takeIf { !focused }?.let { label ->
                Surface(onClick = onChooseScope, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(14.dp))
                    }
                }
            }
        }
        ChatComposerSurface(inputText, onInputChanged, onSend, onStop, isProcessing,
            onTools = { showTools = true },
            sendEnabled = inputText.isNotBlank() || pendingAttachments.isNotEmpty(),
            focusRequester = focusRequester, compact = compact, onFocusChanged = { focused = it }, allowSteering = allowSteering)
        LiveSteeringHint(isProcessing && allowSteering, submittedRevision, appliedRevision)
    }
}

@Composable
internal fun LiveSteeringHint(visible: Boolean, submittedRevision: Long, appliedRevision: Long) {
    if (!visible) return
    Text(when {
        submittedRevision > appliedRevision -> "Follow-up #$submittedRevision received · updating the route…"
        appliedRevision > 0 -> "Route updated · follow-up #$appliedRevision applied"
        else -> "You can correct the route or add an instruction while Omni works"
    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 12.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
}

@Composable
private fun RowScope.AttachmentChips(attachments: List<PendingAttachment>, onRemove: (Uri) -> Unit) {
    attachments.forEach { attachment -> key(attachment.uri) {
        InputChip(selected = false, onClick = { onRemove(attachment.uri) }, label = {
            Text(attachment.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        }, leadingIcon = { Icon(Icons.Default.AttachFile, null, Modifier.size(16.dp)) },
            trailingIcon = { Icon(Icons.Default.Close, "Remove ${attachment.displayName}", Modifier.size(16.dp)) })
    } }
}

@Composable
private fun ChatToolRow(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun EmptyStateContent(mode: OmniMode, onSuggestion: (String) -> Unit,
    customSuggestions: List<Pair<String, String>>? = null) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            OmniMark(Modifier.size(36.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A little help.\nA lot of possibilities.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(when (mode) {
                OmniMode.AGENT -> "Give Omni a task. It will plan the steps and get to work."
                OmniMode.SWARM -> "Bring a bigger idea. Let a team work through it together."
                else -> "Ask a question, work through a problem, or start with an idea."
            }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val suggestions = customSuggestions ?: when (mode) {
            OmniMode.AGENT -> listOf("Review my project" to "Review the selected project and suggest the most useful improvements.",
                "Investigate a bug" to "Help me investigate this bug: ")
            OmniMode.SWARM -> listOf("Plan a feature" to "Plan and implement this feature with coordinated agents: ",
                "Review from multiple angles" to "Review the selected project for usability, performance and reliability.")
            else -> listOf("Explain something" to "Explain this clearly with an example: ", "Explore an idea" to "Help me develop this idea: ")
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { (title, prompt) ->
                Surface(onClick = { onSuggestion(prompt) }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Icon(Icons.Default.NorthEast, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
