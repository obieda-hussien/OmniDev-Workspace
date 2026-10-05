package com.omnidev.workspace.ui.chat

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.domain.engine.ModeSwitchPermissionStore
import com.omnidev.workspace.domain.engine.OmniMode
import com.omnidev.workspace.domain.model.ChatSettings
import com.omnidev.workspace.ui.motion.OmniAnimatedVisibility
import com.omnidev.workspace.ui.motion.OmniIconButton
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, onNavigateToSettings: () -> Unit = {}, onOpenBrowser: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val drawer = rememberDrawerState(if (state.isDrawerOpen) DrawerValue.Open else DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val historyState = rememberSaveableStateHolder()
    state.pendingConfirmation?.let { confirmation ->
        ConfirmationGateDialog(confirmation.copy(
            onApprove = { confirmation.onApprove(); viewModel.clearConfirmation() },
            onDeny = { confirmation.onDeny(); viewModel.clearConfirmation() }))
    }
    LaunchedEffect(state.isDrawerOpen) { if (state.isDrawerOpen) drawer.open() else drawer.close() }
    LaunchedEffect(drawer.currentValue) { viewModel.setDrawerOpen(drawer.isOpen) }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            viewModel.setTargetContextFromUri(context, uri)
        }
    }
    val attachments = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            val names = uris.map { uri ->
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val col = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && col >= 0) cursor.getString(col) else null
                } ?: uri.lastPathSegment ?: "Attachment"
            }
            viewModel.addAttachments(uris, names)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val drawerWidth = (maxWidth * .9f).coerceAtMost(360.dp)
        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            ModalDrawerSheet(Modifier.width(drawerWidth)) {
                // Closed history does not rebuild while tokens arrive. Search/sort survive reopening.
                if (drawer.isOpen || drawer.targetValue == DrawerValue.Open) historyState.SaveableStateProvider("history") {
                    ChatHistoryDrawer(state.sessions, state.currentSessionId, viewModel::newSession, viewModel::loadSession,
                        viewModel::togglePinSession, viewModel::renameSession, viewModel::deleteSession, viewModel::deleteAllSessions,
                        viewModel::deleteSelectedSessions, { scope.launch { drawer.close() } },
                        isOpen = drawer.targetValue == DrawerValue.Open,
                        onSettings = { scope.launch { drawer.close(); onNavigateToSettings() } },
                        onBrowser = { scope.launch { drawer.close(); onOpenBrowser() } })
                }
            }
        }) {
            ChatConversation(state = state, onInputChanged = viewModel::onInputChanged, onSend = viewModel::sendMessage,
                onStop = { viewModel.cancelCurrentRun() }, onModeSelected = viewModel::setMode,
                onOpenConversations = { scope.launch { drawer.open() } }, onNewConversation = viewModel::newSession,
                onChooseScope = { directoryPicker.launch(null) },
                onBrowser = onOpenBrowser,
                onAttach = { attachments.launch("*/*") }, onRemoveAttachment = viewModel::removeAttachment,
                onReply = viewModel::setReplyingTo, onDismissReply = viewModel::clearReplyingTo,
                onUpdateChatSettings = viewModel::updateChatSettings, onClearError = viewModel::clearError,
                onModeDecision = { id, approval ->
                    if (approval == null) viewModel.denyExecutionMode(id) else viewModel.acceptExecutionMode(id, approval)
                })
        }
    }
}

/** Pure presentation: usable in narrow-window and IME regression tests without running an agent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatConversation(
    state: ChatUiState,
    onInputChanged: (String) -> Unit = {}, onSend: () -> Unit = {}, onStop: () -> Unit = {},
    onModeSelected: (OmniMode) -> Unit = {}, onOpenConversations: () -> Unit = {}, onNewConversation: () -> Unit = {},
    onChooseScope: () -> Unit = {}, onBrowser: () -> Unit = {},
    onAttach: () -> Unit = {}, onRemoveAttachment: (android.net.Uri) -> Unit = {},
    onReply: (ChatMessage) -> Unit = {}, onDismissReply: () -> Unit = {}, onUpdateChatSettings: (ChatSettings) -> Unit = {},
    onClearError: () -> Unit = {}, onModeDecision: (String, ModeSwitchPermissionStore.Approval?) -> Unit = { _, _ -> }
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()
    val messagesById = remember(state.messages) { state.messages.associateBy { it.messageId } }
    val lastUserId = remember(state.messages) { state.messages.lastOrNull { it.role == MessageRole.USER }?.messageId }
    val title = remember(state.sessions, state.currentSessionId) {
        state.sessions.firstOrNull { it.id == state.currentSessionId }?.title?.ifBlank { "Omni" } ?: "Omni"
    }
    val follow = rememberTailFollowState(list, state.currentSessionId,
        Triple(state.messages.size, state.streamingContent, state.consoleEntries.size), forceFollowKey = lastUserId)
    val runningConsole = remember(state.consoleEntries) {
        AgentConsoleSerializer.compact(state.consoleEntries.map(ConsoleRedactor::entry))
    }
    fun leaveEditor(action: () -> Unit) { focusManager.clearFocus(); keyboard?.hide(); action() }
    // Scaffold owns system and IME insets once; the body consumes its padding.
    Scaffold(modifier = Modifier.testTag("chat-conversation"), containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing, topBar = {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().testTag("conversation-header")) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OmniIconButton(onClick = { leaveEditor(onOpenConversations) }) { Icon(Icons.Default.Menu, "Open conversations") }
                Box(Modifier.weight(1f)) {
                    ModeSelector(state.activeMode, onModeSelected, isProcessing = state.isProcessing, conversationTitle = title)
                }
                OmniIconButton(onClick = { leaveEditor(onNewConversation) }) { Icon(Icons.Default.Edit, "New conversation") }
            }
        }
    }) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            val compactComposer = maxHeight < 280.dp
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(Modifier.fillMaxSize().testTag("conversation-messages"), state = list,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        if (state.messages.isEmpty() && !state.isProcessing) item(key = "welcome") {
                            EmptyStateContent(state.activeMode) { prompt -> onInputChanged(prompt); focusRequester.requestFocus(); keyboard?.show() }
                        }
                        items(state.messages, key = { it.messageId }, contentType = { it.role }) { message ->
                            MessageBubble(message, state.messageConsoleEntries[message.timestamp],
                                message.replyToMessageId?.let(messagesById::get), onReply = {
                                    onReply(it); focusRequester.requestFocus(); keyboard?.show()
                                }, onOpenBrowser = onBrowser)
                            message.executionRequest?.let { request ->
                                if (message.role == MessageRole.ASSISTANT) ModeSwitchRequestCard(request, !state.isProcessing,
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
                    }
                    OmniAnimatedVisibility(!follow.following, Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                        SmallFloatingActionButton(onClick = { follow.resume() }, containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                            Icon(Icons.Default.KeyboardArrowDown, "Jump to latest message")
                        }
                    }
                }
                ChatInputBar(state.inputText, onInputChanged, onSend, onStop, state.isProcessing, state.pendingAttachments, onAttach,
                    onRemoveAttachment, state.replyingTo, onDismissReply, state.chatSettings, onUpdateChatSettings, focusRequester,
                    scopeLabel = if (state.activeMode == OmniMode.CHAT || state.isGodModeEnabled) null
                        else state.targetContextDisplayName ?: state.targetContext?.substringAfterLast('/')?.ifBlank { state.targetContext },
                    onChooseScope = onChooseScope, compact = compactComposer,
                    showScopeChooser = state.activeMode != OmniMode.CHAT && !state.isGodModeEnabled)
            }
        }
    }
}

@Composable
private fun ChatErrorBanner(error: String, onDismiss: () -> Unit) {
    var details by rememberSaveable(error) { mutableStateOf(false) }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Conversation error") },
        text = { SelectionContainer { Text(error, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) } },
        confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } })
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(error, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { details = true }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Details") }
            }
            OmniIconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss error") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModeSwitchRequestCard(
    request: com.omnidev.workspace.data.model.ExecutionModeRequest,
    enabled: Boolean,
    onOnce: () -> Unit,
    onAlwaysTransition: () -> Unit,
    onAllSession: () -> Unit,
    onDeny: () -> Unit
) {
    val pending = request.status == "pending"
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val source = request.sourceMode ?: "current mode"
            val target = if (request.mode == "SWARM") "Multi-agent" else request.mode.lowercase().replaceFirstChar { it.uppercase() }
            Text(
                text = if (pending) "$source → $target" else "Mode decision: ${request.status.replace('_', ' ')}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            request.confidence?.let {
                Text(
                    text = "Local confidence: ${(it * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
            if (pending) {
                Text("Allow Omni to change execution mode for this task.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = onOnce, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow once")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = onAlwaysTransition, enabled = enabled) { Text("Always this switch") }
                    OutlinedButton(onClick = onAllSession, enabled = enabled) { Text("All this session") }
                    TextButton(onClick = onDeny, enabled = enabled) { Text("Deny") }
                }
            }
        }
    }
}
