package com.omnidev.workspace.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
    OmniMode.CHAT -> "Answers, ideas and lightweight tools"
    OmniMode.AGENT -> "One agent plans and executes your task"
    OmniMode.SWARM -> "A coordinator splits work across agents"
    OmniMode.AUTO -> "Omni chooses a mode for the task"
}

@Composable
private fun ModeIcon(mode: OmniMode) {
    Icon(when (mode) {
        OmniMode.CHAT -> Icons.Default.QuestionAnswer
        OmniMode.AGENT -> Icons.Default.SmartToy
        OmniMode.SWARM -> Icons.Default.Hub
        OmniMode.AUTO -> Icons.Default.AutoAwesome
    }, null, tint = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModeSelector(activeMode: OmniMode, onModeSelected: (OmniMode) -> Unit, enabled: Boolean = true,
    isProcessing: Boolean = false) {
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(onClick = { open = true }, enabled = enabled, shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModeIcon(activeMode)
            Column(Modifier.weight(1f)) {
                Text(modeTitle(activeMode), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(modeDescription(activeMode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.KeyboardArrowDown, "Choose conversation mode")
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Choose how Omni works", style = MaterialTheme.typography.headlineSmall)
                Text(if (isProcessing) "Changing mode checkpoints the active run before switching."
                    else "The conversation stays the same when you change modes.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(listOf(OmniMode.CHAT, OmniMode.AGENT, OmniMode.SWARM)) { mode ->
                Surface(shape = RoundedCornerShape(18.dp), color = if (mode == activeMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().selectable(selected = mode == activeMode, role = Role.RadioButton,
                        onClick = { onModeSelected(mode); open = false })) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ModeIcon(mode)
                        Column(Modifier.weight(1f)) {
                            Text(modeTitle(mode), style = MaterialTheme.typography.titleMedium)
                            Text(modeDescription(mode), style = MaterialTheme.typography.bodyMedium)
                            if (mode == OmniMode.SWARM) Text("Uses more model calls for coordinated work.", style = MaterialTheme.typography.bodySmall)
                        }
                        RadioButton(selected = mode == activeMode, onClick = null)
                    }
                }
            }
        }
    }
}

@Composable
internal fun MessageBubble(message: ChatMessage, consoleEntries: List<AgentConsoleEntry>? = null,
    replyToMessage: ChatMessage? = null, onReply: (ChatMessage) -> Unit = {}, onOpenBrowser: (() -> Unit)? = null) {
    val user = message.role == MessageRole.USER
    val parsed = remember(message.content, user) { if (user) null else MessageFormatter.parse(message.content) }
    val text = parsed?.cleanText?.ifBlank { null } ?: message.content
    val context = LocalContext.current
    var expanded by rememberSaveable(message.messageId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!user && !consoleEntries.isNullOrEmpty()) AgentLiveConsole(
            entries = remember(consoleEntries) { consoleEntries.map(ConsoleRedactor::entry) }, isRunning = false, onOpenBrowser = onOpenBrowser)
        parsed?.thoughtBlocks?.forEach { ExpandableBlock("Thought process", it) }
        parsed?.toolBlocks?.forEach { block ->
            ExpandableBlock("Tool execution", buildString { append(block.toolCode); block.observation?.let { append("\n\nOutput:\n"); append(it) } })
        }
        Surface(shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = if (user) 6.dp else 22.dp),
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = if (user) 560.dp else 840.dp).fillMaxWidth(if (user) .9f else 1f)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (user) "You" else "Omni", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                replyToMessage?.let { ReplyQuote(it) }
                SelectionContainer {
                    if (user) Text(if (text.length > 500 && !expanded) text.take(500) + "…" else text,
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
                    else MarkdownText(text)
                }
                if (user && text.length > 500) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Read full message") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OmniIconButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Omni", text))
                        Toast.makeText(context, "Message copied", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.ContentCopy, "Copy message", Modifier.size(18.dp)) }
                    OmniIconButton(onClick = { onReply(message) }) { Icon(Icons.AutoMirrored.Filled.Reply, "Reply to message", Modifier.size(20.dp)) }
                }
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
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Omni · Responding", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            SelectionContainer { MarkdownText(content) }
        }
    }
}

@Composable
private fun ExpandableBlock(title: String, content: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column {
            Surface(onClick = { expanded = !expanded }, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
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

@Composable
internal fun ChatInputBar(inputText: String, onInputChanged: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit = {},
    isProcessing: Boolean, pendingAttachments: List<PendingAttachment> = emptyList(), onAttachClick: () -> Unit = {},
    onRemoveAttachment: (Uri) -> Unit = {}, replyingTo: ChatMessage? = null, onDismissReply: () -> Unit = {},
    chatSettings: ChatSettings = ChatSettings(), onUpdateChatSettings: (ChatSettings) -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() }) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (showSettings) ChatSettingsSheet(chatSettings, { showSettings = false }, onUpdateChatSettings)
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            replyingTo?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { ReplyQuote(message) }
                    OmniIconButton(onClick = onDismissReply) { Icon(Icons.Default.Close, "Cancel reply") }
                }
            }
            if (pendingAttachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pendingAttachments.forEach { attachment ->
                    InputChip(selected = false, onClick = { onRemoveAttachment(attachment.uri) }, label = {
                        Text(attachment.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                    }, trailingIcon = { Icon(Icons.Default.Close, "Remove ${attachment.displayName}", Modifier.size(18.dp)) })
                }
            }
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    TextField(inputText, onInputChanged, modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        placeholder = { Text(if (isProcessing) "Omni is working…" else "Message Omni…") }, minLines = 1, maxLines = 5,
                        enabled = !isProcessing, textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                        colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OmniIconButton(onClick = { showSettings = true }, enabled = !isProcessing) { Icon(Icons.Default.Tune, "Chat tools and skills") }
                        OmniIconButton(onClick = onAttachClick, enabled = !isProcessing) { Icon(Icons.Default.AttachFile, "Attach files") }
                        Spacer(Modifier.weight(1f))
                        FilledIconButton(onClick = if (isProcessing) onStop else onSend, enabled = isProcessing || inputText.isNotBlank() || pendingAttachments.isNotEmpty(),
                            modifier = Modifier.size(48.dp), shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (isProcessing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                contentColor = if (isProcessing) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary)) {
                            Icon(if (isProcessing) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send, if (isProcessing) "Stop agent" else "Send")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EmptyStateContent(mode: OmniMode, onSuggestion: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("What would you like to do?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(when (mode) {
            OmniMode.AGENT -> "Give Omni a task to plan and carry out. Set a project scope for file work."
            OmniMode.SWARM -> "Give the team a larger task to split into coordinated steps."
            else -> "Ask a question, explore an idea, or work through a problem together."
        }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val suggestions = when (mode) {
            OmniMode.AGENT -> listOf("Review my project" to "Review the selected project and suggest the most useful improvements.",
                "Investigate a bug" to "Help me investigate this bug: ")
            OmniMode.SWARM -> listOf("Plan a feature" to "Plan and implement this feature with coordinated agents: ",
                "Review from multiple angles" to "Review the selected project for usability, performance and reliability.")
            else -> listOf("Explain something" to "Explain this clearly with an example: ", "Explore an idea" to "Help me develop this idea: ")
        }
        suggestions.forEach { (title, prompt) ->
            OutlinedButton(onClick = { onSuggestion(prompt) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(16.dp)) { Text(title, Modifier.weight(1f)); Icon(Icons.Default.NorthEast, null, Modifier.size(18.dp)) }
        }
    }
}
