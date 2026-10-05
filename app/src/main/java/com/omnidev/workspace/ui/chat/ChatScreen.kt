package com.omnidev.workspace.ui.chat

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.domain.engine.ModeSwitchPermissionStore
import com.omnidev.workspace.ui.motion.OmniAnimatedVisibility as AnimatedVisibility
import com.omnidev.workspace.ui.motion.OmniIconButton
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit = {},
    onOpenBrowser: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(
        initialValue = if (uiState.isDrawerOpen) DrawerValue.Open else DrawerValue.Closed
    )
    val scope = rememberCoroutineScope()

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

    LaunchedEffect(uiState.isDrawerOpen) {
        if (uiState.isDrawerOpen) drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState.currentValue) {
        viewModel.setDrawerOpen(drawerState.isOpen)
    }

    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            viewModel.setTargetContextFromUri(context, uri)
        }
    }

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

    val rememberedFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var showChatMenu by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val messagesById = remember(uiState.messages) { uiState.messages.associateBy { it.messageId } }
    val tailFollow = rememberTailFollowState(
        listState = listState,
        sessionKey = uiState.currentSessionId,
        contentRevision = Triple(uiState.messages.size, uiState.streamingContent, uiState.isProcessing),
        forceFollowKey = uiState.messages.lastOrNull { it.role == MessageRole.USER }?.messageId
    )

    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet(modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(.9f)) {
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
                    onCloseDrawer = { scope.launch { drawerState.close() } },
                    isOpen = drawerState.isOpen,
                    onSettings = { scope.launch { drawerState.close(); onNavigateToSettings() } },
                    onBrowser = { scope.launch { drawerState.close(); onOpenBrowser() } }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(uiState.sessions.firstOrNull { it.id == uiState.currentSessionId }?.title?.ifBlank { "New conversation" }
                        ?: "Omni", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { OmniIconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Filled.Menu, "Open conversations")
                    } },
                    actions = {
                        OmniIconButton(onClick = viewModel::newSession) { Icon(Icons.Filled.Add, "New conversation") }
                        Box {
                            OmniIconButton(onClick = { showChatMenu = true }) { Icon(Icons.Filled.MoreVert, "Conversation options") }
                            androidx.compose.material3.DropdownMenu(showChatMenu, { showChatMenu = false }) {
                                if (!uiState.isGodModeEnabled) androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Choose project scope") }, leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                                    onClick = { showChatMenu = false; directoryPickerLauncher.launch(null) })
                                androidx.compose.material3.DropdownMenuItem(text = { Text("Open browser") }, leadingIcon = { Icon(Icons.Filled.Language, null) },
                                    onClick = { showChatMenu = false; onOpenBrowser() })
                                androidx.compose.material3.DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                    onClick = { showChatMenu = false; onNavigateToSettings() })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                AnimatedVisibility(visible = uiState.isProcessing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // Always visible and manually selectable. ChatViewModel checkpoints an active run
                // before a user-initiated mode change.
                ModeSelector(
                    activeMode = uiState.activeMode,
                    onModeSelected = { viewModel.setMode(it) },
                    enabled = true,
                    isProcessing = uiState.isProcessing
                )

                androidx.compose.material3.TextButton(
                    onClick = { if (uiState.isGodModeEnabled) onNavigateToSettings() else directoryPickerLauncher.launch(null) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Icon(Icons.Filled.FolderOpen, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (uiState.isGodModeEnabled) "Extended file access" else uiState.targetContextDisplayName ?: uiState.targetContext ?: "Choose project scope",
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                AnimatedVisibility(visible = uiState.isProcessing && uiState.consoleEntries.isNotEmpty()) {
                    AgentLiveConsole(
                        entries = remember(uiState.consoleEntries) {
                            AgentConsoleSerializer.compact(uiState.consoleEntries.map(ConsoleRedactor::entry))
                        },
                        isRunning = uiState.isProcessing,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        onOpenBrowser = onOpenBrowser
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        if (uiState.messages.isEmpty() && !uiState.isProcessing) item {
                            EmptyStateContent(uiState.activeMode) { prompt ->
                                viewModel.onInputChanged(prompt)
                                rememberedFocus.requestFocus()
                            }
                        }
                        items(uiState.messages, key = { it.messageId }, contentType = { it.role }) { message ->
                            val replyToMessage = message.replyToMessageId?.let { id ->
                                messagesById[id]
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
                                    ModeSwitchRequestCard(
                                        request = request,
                                        enabled = !uiState.isProcessing,
                                        onOnce = {
                                            viewModel.acceptExecutionMode(
                                                message.messageId,
                                                ModeSwitchPermissionStore.Approval.ONCE
                                            )
                                        },
                                        onAlwaysTransition = {
                                            viewModel.acceptExecutionMode(
                                                message.messageId,
                                                ModeSwitchPermissionStore.Approval.ALWAYS_THIS_TRANSITION
                                            )
                                        },
                                        onAllSession = {
                                            viewModel.acceptExecutionMode(
                                                message.messageId,
                                                ModeSwitchPermissionStore.Approval.ALL_THIS_SESSION
                                            )
                                        },
                                        onDeny = { viewModel.denyExecutionMode(message.messageId) }
                                    )
                                }
                            }
                        }
                        val streamingContent = uiState.streamingContent
                        if (uiState.isProcessing && streamingContent != null) {
                            item(key = "streaming", contentType = "streaming") { StreamingMessageBubble(content = streamingContent) }
                        }
                    }

                    if (!tailFollow.following) {
                        SmallFloatingActionButton(
                            onClick = { tailFollow.resume() },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Jump to latest message")
                        }
                    }
                }

                uiState.errorMessage?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
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
                    onUpdateChatSettings = { viewModel.updateChatSettings(it) },
                    focusRequester = rememberedFocus
                )
            }
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
