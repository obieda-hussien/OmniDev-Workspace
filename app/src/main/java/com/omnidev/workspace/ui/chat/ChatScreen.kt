package com.omnidev.workspace.ui.chat

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DismissibleDrawerSheet
import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.domain.engine.OmniMode
import kotlinx.coroutines.launch

/**
 * Omni-Chat Interface — the primary conversational UI.
 *
 * Features:
 * - Rich navigation drawer for searching, filtering, sorting and managing chat history
 * - New session button
 * - Target Context selector with SAF directory picker
 * - Real-time agent status streaming with Glass Brain console
 * - User/Assistant message bubbles with Markdown rendering
 * - Multi-modal attachment picker
 */

private const val REPLY_PREVIEW_MAX_CHARS = 120
private const val USER_MESSAGE_COLLAPSE_THRESHOLD = 300

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit = {},
    onOpenBrowser: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(
        initialValue = if (uiState.isDrawerOpen) DrawerValue.Open else DrawerValue.Closed
    )
    val scope = rememberCoroutineScope()

    // Show the confirmation gate dialog if there's a pending privileged action
    uiState.pendingConfirmation?.let { confirmation ->
        ConfirmationGateDialog(
            confirmation = confirmation.copy(
                onApprove = {
                    confirmation.onApprove()
                    viewModel.clearConfirmation()
                },
                onDeny = {
                    confirmation.onDeny()
                    viewModel.clearConfirmation()
                }
            )
        )
    }

    // Sync drawer open state with ViewModel
    LaunchedEffect(uiState.isDrawerOpen) {
        if (uiState.isDrawerOpen) drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState.currentValue) {
        viewModel.setDrawerOpen(drawerState.isOpen)
    }

    // SAF directory picker
    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            viewModel.setTargetContextFromUri(context, uri)
        }
    }

    // Multi-file attachment picker
    val attachmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val displayNames = uris.map { uri ->
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use { c ->
                    val nameCol = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && nameCol >= 0) c.getString(nameCol) else null
                } ?: uri.lastPathSegment ?: "Attachment"
            }
            viewModel.addAttachments(uris, displayNames)
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size, uiState.streamingContent) {
        if (uiState.messages.isNotEmpty() || uiState.streamingContent != null) {
            val lastIndex = if (uiState.streamingContent != null) {
                uiState.messages.size // streaming item is after all messages
            } else {
                uiState.messages.lastIndex
            }
            if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
        }
    }

    DismissibleNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DismissibleDrawerSheet(modifier = Modifier.width(344.dp)) {
                ChatHistoryDrawer(
                    sessions = uiState.sessions,
                    currentSessionId = uiState.currentSessionId,
                    onNewSession = { viewModel.newSession() },
                    onSessionClick = { viewModel.loadSession(it) },
                    onTogglePin = { viewModel.togglePinSession(it) },
                    onRenameSession = { id, title -> viewModel.renameSession(id, title) },
                    onDeleteSession = { viewModel.deleteSession(it) },
                    onDeleteAllSessions = { viewModel.deleteAllSessions() },
                    onDeleteSelectedSessions = { viewModel.deleteSelectedSessions(it) },
                    onCloseDrawer = { scope.launch { drawerState.close() } }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "OmniDev Workspace",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (uiState.isGodModeEnabled) {
                                Text(
                                    text = "⚡ God Mode — Full Root Access",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                val displayName = uiState.targetContextDisplayName ?: uiState.targetContext
                                if (displayName != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Filled.FolderOpen,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = displayName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "Tap 📁 to set scope",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = "Chat History"
                            )
                        }
                    },
                    actions = {
                        // Hide folder/scope picker when God Mode is enabled — root has access to /
                        if (!uiState.isGodModeEnabled) {
                            IconButton(onClick = { directoryPickerLauncher.launch(null) }) {
                                Icon(
                                    imageVector = Icons.Filled.FolderOpen,
                                    contentDescription = "Set Target Context"
                                )
                            }
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "AI Settings"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                AnimatedVisibility(visible = uiState.isProcessing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // ── Mode Selector (Chat / Agent / Team Agents) ──
                ModeSelector(
                    activeMode = uiState.activeMode,
                    onModeSelected = { viewModel.setMode(it) },
                    enabled = !uiState.isProcessing
                )

                AnimatedVisibility(visible = uiState.isProcessing && uiState.consoleEntries.isNotEmpty()) {
                    AgentLiveConsole(
                        entries = AgentConsoleSerializer.compact(uiState.consoleEntries.map(ConsoleRedactor::entry)),
                        isRunning = uiState.isProcessing,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        onOpenBrowser = onOpenBrowser
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (uiState.messages.isEmpty()) {
                        item { EmptyStateContent() }
                    }
                    items(uiState.messages, key = { it.messageId }) { message ->
                        val replyToMessage = message.replyToMessageId?.let { id ->
                            uiState.messages.find { it.messageId == id }
                        }
                        MessageBubble(
                            message = message,
                            consoleEntries = uiState.messageConsoleEntries[message.timestamp],
                            replyToMessage = replyToMessage,
                            onReply = { viewModel.setReplyingTo(it) },
                            onOpenBrowser = onOpenBrowser
                        )
                        message.executionRequest?.let { request ->
                            if (message.role == MessageRole.ASSISTANT) {
                                OutlinedButton(
                                    onClick = { viewModel.acceptExecutionMode(message.messageId) },
                                    enabled = !uiState.isProcessing && request.status == "pending",
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                                ) {
                                    Text(
                                        if (request.status == "accepted") "Mode enabled"
                                        else if (request.mode == "SWARM") "Enable multi-agent mode"
                                        else "Enable agent mode"
                                    )
                                }
                            }
                        }
                    }
                    // Show partial streaming response while the model is still generating
                    val streamingContent = uiState.streamingContent
                    if (uiState.isProcessing && streamingContent != null) {
                        item {
                            StreamingMessageBubble(content = streamingContent)
                        }
                    }
                }

                uiState.errorMessage?.let { error ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                ChatInputBar(
                    inputText = uiState.inputText,
                    onInputChanged = { viewModel.onInputChanged(it) },
                    onSend = { viewModel.sendMessage() },
                    onStop = { viewModel.cancelCurrentRun() },
                    isProcessing = uiState.isProcessing,
                    pendingAttachments = uiState.pendingAttachments,
                    onAttachClick = { attachmentLauncher.launch("*/*") },
                    onRemoveAttachment = { viewModel.removeAttachment(it) },
                    replyingTo = uiState.replyingTo,
                    onDismissReply = { viewModel.clearReplyingTo() },
                    chatSettings = uiState.chatSettings,
                    onUpdateChatSettings = { viewModel.updateChatSettings(it) }
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
//  Mode Selector (Segmented Buttons)
// ──────────────────────────────────────────────

@Composable
private fun ModeSelector(
    activeMode: OmniMode,
    onModeSelected: (OmniMode) -> Unit,
    enabled: Boolean = true
) {
    // Only show the three explicit modes the user can pick manually.
    // AUTO is reserved for the floating overlay which routes by intent automatically.
    val manualModes = listOf(OmniMode.CHAT, OmniMode.AGENT, OmniMode.SWARM)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        manualModes.forEachIndexed { index, mode ->
            val isSelected = mode == activeMode
            val shape = when (index) {
                0 -> RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
                manualModes.lastIndex -> RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
                else -> RoundedCornerShape(0.dp)
            }

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp),
                shape = shape,
                color = if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                onClick = { if (enabled) onModeSelected(mode) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
//  Message Bubble with Markdown + Parsed Tags
// ──────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    consoleEntries: List<AgentConsoleEntry>? = null,
    replyToMessage: ChatMessage? = null,
    onReply: (ChatMessage) -> Unit = {},
    onOpenBrowser: (() -> Unit)? = null
) {
    val isUser = message.role == MessageRole.USER
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val backgroundColor = if (isUser)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    val icon = if (isUser) Icons.Filled.Person else Icons.Filled.SmartToy

    // Parse assistant messages to extract <thinking> and <tool_code> blocks
    val parsed = if (!isUser) MessageFormatter.parse(message.content) else null

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val copyText = parsed?.cleanText?.ifBlank { null } ?: message.content

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        // Persistent per-message Agent Console — collapsed by default, expandable
        if (!isUser && !consoleEntries.isNullOrEmpty()) {
            AgentLiveConsole(
                entries = consoleEntries.map(ConsoleRedactor::entry),
                isRunning = false,
                modifier = Modifier.padding(bottom = 4.dp),
                onOpenBrowser = onOpenBrowser
            )
        }

        // Expandable "🧠 Thought Process" cards (assistant only)
        if (parsed != null && parsed.thoughtBlocks.isNotEmpty()) {
            parsed.thoughtBlocks.forEach { thought ->
                ExpandableBlock(
                    headerLabel = "🧠 Thought Process",
                    content = thought,
                    headerColor = Color(0xFF0D2137)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        // Expandable "🛠️ Tool Execution" cards (assistant only)
        if (parsed != null && parsed.toolBlocks.isNotEmpty()) {
            parsed.toolBlocks.forEach { toolBlock ->
                ExpandableBlock(
                    headerLabel = "🛠️ Tool Execution",
                    content = buildString {
                        appendLine("```")
                        appendLine(toolBlock.toolCode)
                        appendLine("```")
                        toolBlock.observation?.let {
                            appendLine()
                            appendLine("**Output:**")
                            appendLine(it)
                        }
                    },
                    headerColor = Color(0xFF1A1A2E)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (!isUser) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Card(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onReply(message)
                        }
                    ),
                shape = RoundedCornerShape(
                    topStart = if (isUser) 16.dp else 4.dp,
                    topEnd = if (isUser) 4.dp else 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp
                ),
                colors = CardDefaults.cardColors(containerColor = backgroundColor)
            ) {
                Column {
                    // Quoted reply header — shown when this message is a reply to another
                    if (replyToMessage != null) {
                        val quoteSenderLabel = if (replyToMessage.role == MessageRole.USER) "You" else "OmniDev"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                                    RoundedCornerShape(topStart = if (isUser) 16.dp else 4.dp, topEnd = if (isUser) 4.dp else 16.dp)
                                )
                                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(32.dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = quoteSenderLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = replyToMessage.content.take(REPLY_PREVIEW_MAX_CHARS).replace("\n", " "),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    Box {
                        SelectionContainer(modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onReply(message)
                            }
                        )) {
                            if (isUser) {
                                val isLong = message.content.length > USER_MESSAGE_COLLAPSE_THRESHOLD
                                var userExpanded by remember(message.messageId) { mutableStateOf(false) }
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = if (isLong && !userExpanded)
                                            message.content.take(USER_MESSAGE_COLLAPSE_THRESHOLD) + "…"
                                        else
                                            message.content,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (isLong) {
                                        Row(
                                            modifier = Modifier
                                                .padding(top = 4.dp)
                                                .clickable { userExpanded = !userExpanded },
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (userExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                                contentDescription = if (userExpanded) "Read less" else "Read more",
                                                tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = if (userExpanded) "Read less" else "Read more",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Assistant: show only the clean text (tags extracted to expandable blocks)
                                val displayText = parsed?.cleanText?.ifBlank { null } ?: message.content
                                MarkdownText(
                                    text = displayText,
                                    // Extra end padding reserves space for the 32dp copy button
                                    modifier = Modifier.padding(start = 12.dp, end = 36.dp, top = 12.dp, bottom = 12.dp)
                                )
                            }
                        }
                        // Copy button — top-right corner of the bubble
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText("OmniDev", copyText))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(32.dp)
                                .padding(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy message",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }

            if (isUser) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Animated bubble showing real-time streaming content from the model.
 * Displayed while the agent is generating the final answer via SSE.
 * Once [AgentEvent.FinalAnswer] arrives the full [MessageBubble] replaces this.
 */
@Composable
private fun StreamingMessageBubble(content: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            SelectionContainer {
                MarkdownText(
                    text = content,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/**
 * Collapsible card for displaying extracted LLM internals (thinking blocks, tool calls).
 * Collapsed by default to keep the chat UI clean.
 */
@Composable
private fun ExpandableBlock(
    headerLabel: String,
    content: String,
    headerColor: Color
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = headerColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = headerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8BBFD4),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = Color(0xFF8BBFD4),
                    modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    text = content.trim(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    color = Color(0xFFCDD6E8),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
//  Input Bar
// ──────────────────────────────────────────────

@Composable
private fun ChatInputBar(
    inputText: String,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit = {},
    isProcessing: Boolean,
    pendingAttachments: List<PendingAttachment> = emptyList(),
    onAttachClick: () -> Unit = {},
    onRemoveAttachment: (android.net.Uri) -> Unit = {},
    replyingTo: ChatMessage? = null,
    onDismissReply: () -> Unit = {},
    chatSettings: com.omnidev.workspace.domain.model.ChatSettings = com.omnidev.workspace.domain.model.ChatSettings(),
    onUpdateChatSettings: (com.omnidev.workspace.domain.model.ChatSettings) -> Unit = {}
) {
    var showSettingsSheet by remember { mutableStateOf(false) }

    if (showSettingsSheet) {
        ChatSettingsSheet(
            settings = chatSettings,
            onDismiss = { showSettingsSheet = false },
            onUpdate = { updated ->
                onUpdateChatSettings(updated)
            }
        )
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        // Reply-preview bar — shown above attachments when user is replying to a message
        if (replyingTo != null) {
            val senderLabel = if (replyingTo.role == MessageRole.USER) "You" else "OmniDev"
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Reply,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(36.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(2.dp)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = senderLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = replyingTo.content.take(REPLY_PREVIEW_MAX_CHARS).replace("\n", " "),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    IconButton(
                        onClick = onDismissReply,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cancel reply",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        if (pendingAttachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pendingAttachments.forEach { attachment ->
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = {
                            Text(
                                text = attachment.displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { onRemoveAttachment(attachment.uri) },
                                modifier = Modifier.size(18.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove attachment",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // "+" button — opens the "Add to chat" settings sheet
            IconButton(
                onClick = { showSettingsSheet = true },
                enabled = !isProcessing,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add to chat",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = onAttachClick,
                enabled = !isProcessing,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = "Attach files",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChanged,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("Ask OmniDev anything...")
                },
                shape = RoundedCornerShape(24.dp),
                maxLines = 5,
                enabled = !isProcessing
            )
            Spacer(modifier = Modifier.width(8.dp))
            FloatingActionButton(
                onClick = if (isProcessing) onStop else onSend,
                modifier = Modifier.size(48.dp),
                containerColor = if (isProcessing)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary,
                shape = CircleShape
            ) {
                if (isProcessing) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "Stop agent",
                        tint = MaterialTheme.colorScheme.onError
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
//  Empty State
// ──────────────────────────────────────────────

@Composable
private fun EmptyStateContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.SmartToy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            modifier = Modifier.size(80.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "OmniDev Workspace",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Set a Target Context and start coding with AI",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}
