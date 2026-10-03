package com.omnidev.workspace.ui.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnidev.workspace.data.assistant.AssistantController
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.ui.chat.ConfirmationGateDialog
import com.omnidev.workspace.ui.motion.LocalOmniMotion
import kotlin.math.max
import kotlin.math.min

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.res.painterResource
import com.omnidev.workspace.R
import com.omnidev.workspace.ui.chat.ModeSwitchRequestCard
import com.omnidev.workspace.ui.chat.ModeSwitchPermissionStore
import com.omnidev.workspace.ui.chat.MessageBubble
import com.omnidev.workspace.ui.chat.StreamingMessageBubble
import com.omnidev.workspace.ui.chat.AgentLiveConsole
import com.omnidev.workspace.ui.chat.AgentConsoleEntry
import com.omnidev.workspace.ui.chat.ConsoleRedactor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Native assistant and Activity fallback use the same chat renderer and agent console. */
@Composable
fun AssistantOverlay(
    controller: AssistantController,
    onDismiss: () -> Unit,
    onExpand: () -> Unit,
    onSetup: () -> Unit,
    onMicrophone: () -> Unit,
    onMinimize: () -> Unit,
    onAttach: () -> Unit
) {
    val screen by controller.state.collectAsStateWithLifecycle()
    val chat by controller.chat.uiState.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val motion = LocalOmniMotion.current
    val busy = chat.isProcessing || screen.saving
    val reveal = remember { MutableTransitionState(false) }.apply { targetState = screen.visible }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var tracks by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var pathDialog by remember { mutableStateOf(false) }
    var filePath by remember { mutableStateOf("") }
    val entries = remember(chat.messageConsoleEntries, chat.consoleEntries) {
        (chat.messageConsoleEntries.values.flatten() + chat.consoleEntries).distinctBy { it.id }
            .sortedWith(compareBy<AgentConsoleEntry> { it.timestamp }.thenBy { it.id })
            .filter { it is AgentConsoleEntry.ToolEntry || it is AgentConsoleEntry.ResultEntry || it is AgentConsoleEntry.PhaseEntry || it is AgentConsoleEntry.ErrorEntry }
            .map(ConsoleRedactor::entry)
    }
    fun leave(action: () -> Unit) {
        scope.launch { controller.hide(); delay(motion.navigationMillis.toLong()); action() }
    }
    LaunchedEffect(chat.messages.size, chat.streamingContent, chat.consoleEntries.size) {
        if (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 2 } != false) {
            val count = list.layoutInfo.totalItemsCount
            if (count > 0) list.animateScrollToItem(count - 1)
        }
    }
    chat.pendingConfirmation?.let { confirmation ->
        ConfirmationGateDialog(confirmation.copy(
            onApprove = { confirmation.onApprove(); controller.chat.clearConfirmation() },
            onDeny = { confirmation.onDeny(); controller.chat.clearConfirmation() }
        ))
    }
    if (pathDialog) AlertDialog(onDismissRequest = { pathDialog = false }, title = { Text("File path") },
        text = { Column {
            Text("Add an accessible path. Omni will use its file tools when direct media input is unavailable.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(filePath, { filePath = it }, placeholder = { Text("/storage/emulated/0/…") }, maxLines = 3)
        } }, confirmButton = { TextButton(onClick = {
            controller.input(screen.input + "\n[User-provided file path: ${filePath.trim()}]")
            filePath = ""; pathDialog = false
        }, enabled = filePath.isNotBlank()) { Text("Add path") } }, dismissButton = { TextButton(onClick = { pathDialog = false }) { Text("Cancel") } })
    if (screen.selecting && screen.screenshot != null) {
        ScreenRegionPicker(screen.screenshot!!, controller::select, controller::cancelSelection)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .12f)).clickable { leave(onDismiss) })
        val availableHeight = maxHeight
        AnimatedVisibility(visibleState = reveal,
            enter = fadeIn(tween(motion.responseMillis)) + slideInVertically(tween(motion.navigationMillis)) { it / 3 },
            exit = fadeOut(tween(motion.responseMillis)) + slideOutVertically(tween(motion.navigationMillis)) { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(12.dp)) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = availableHeight * .86f).animateContentSize(animationSpec = tween(motion.navigationMillis)),
                shape = RoundedCornerShape(30.dp), color = colors.surface, tonalElevation = 4.dp, shadowElevation = 10.dp) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp).size(30.dp, 3.dp).clip(CircleShape).background(colors.outlineVariant))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_launcher_foreground), "OmniDev", Modifier.size(40.dp).clip(CircleShape).background(colors.primaryContainer))
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text("OmniDev", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(if (screen.listening) "Listening…" else if (busy) chat.agentStatus ?: "Working…" else "Agent · Here, with you",
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                        }
                        IconButton(onClick = { leave(onMinimize) }, enabled = !screen.saving) { Icon(Icons.Default.Remove, "Minimize to floating bubble") }
                        IconButton(onClick = onExpand, enabled = !screen.saving) { Icon(Icons.Default.OpenInFull, "Open full conversation") }
                        IconButton(onClick = { leave(onDismiss) }) { Icon(Icons.Default.Close, "Close and save conversation") }
                    }
                    LazyColumn(Modifier.weight(1f, fill = false), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (chat.messages.isEmpty() && !busy) item {
                            Text("What can I help you with?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
                            Text("Ask about your screen, attach a file, or tell me what to do.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        items(chat.messages, key = { it.messageId }) { message ->
                            MessageBubble(message = message,
                                replyToMessage = chat.messages.firstOrNull { it.messageId == message.replyToMessageId },
                                onReply = controller.chat::setReplyingTo)
                            message.executionRequest?.let { request ->
                                ModeSwitchRequestCard(request, !busy,
                                    onOnce = { controller.chat.acceptExecutionMode(message.messageId, ModeSwitchPermissionStore.Approval.ONCE) },
                                    onAlwaysTransition = { controller.chat.acceptExecutionMode(message.messageId, ModeSwitchPermissionStore.Approval.ALWAYS_THIS_TRANSITION) },
                                    onAllSession = { controller.chat.acceptExecutionMode(message.messageId, ModeSwitchPermissionStore.Approval.ALL_THIS_SESSION) },
                                    onDeny = { controller.chat.denyExecutionMode(message.messageId) })
                            }
                        }
                        chat.streamingContent?.takeIf { it.isNotBlank() }?.let { content -> item { StreamingMessageBubble(content) } }
                        if (chat.consoleEntries.isNotEmpty()) item {
                            AgentLiveConsole(chat.consoleEntries.map(ConsoleRedactor::entry), chat.isProcessing)
                        }
                        if (entries.isNotEmpty()) item {
                            TextButton(onClick = { tracks = !tracks }) {
                                Icon(Icons.Default.Timeline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                Text(if (tracks) "Hide activity" else "Activity · ${entries.size} events")
                            }
                        }
                        if (tracks) items(entries, key = { "track_${it.id}" }) { entry -> AssistantTrack(entry) }
                        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        (screen.message ?: chat.errorMessage)?.let { message -> item {
                            Text(message, style = MaterialTheme.typography.bodySmall, color = colors.error)
                            TextButton(onClick = onSetup) { Text("Assistant capabilities") }
                        } }
                        screen.attachment?.let { image -> item {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Image(image.asImageBitmap(), "Screen image ready to send", Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                                Text("Screen attached", Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.labelLarge)
                                IconButton(onClick = controller::removeImage, enabled = !busy) { Icon(Icons.Default.Close, "Remove screen image") }
                            }
                        } }
                        items(screen.files, key = { it.uri.toString() }) { file ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp))
                                Text(file.displayName, Modifier.weight(1f).padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                IconButton(onClick = { controller.removeFile(file.uri) }, enabled = !busy) { Icon(Icons.Default.Close, "Remove ${file.displayName}") }
                            }
                        }
                    }
                    chat.replyingTo?.let { reply ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Reply: ${reply.content.take(70)}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            IconButton(onClick = controller.chat::clearReplyingTo) { Icon(Icons.Default.Close, "Cancel reply") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { controller.useScreen(false) }, enabled = !busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
                            Icon(Icons.Default.Screenshot, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Screen")
                        }
                        OutlinedButton(onClick = { controller.useScreen(true) }, enabled = !busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
                            Icon(Icons.Default.CropFree, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Select area")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { attachMenu = true }, enabled = !busy) { Icon(Icons.Default.Add, "Attachments and capabilities") }
                            DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                                DropdownMenuItem(text = { Text("Files, photos & videos") }, onClick = { attachMenu = false; onAttach() })
                                DropdownMenuItem(text = { Text("File path") }, onClick = { attachMenu = false; pathDialog = true })
                                DropdownMenuItem(text = { Text("Assistant capabilities") }, onClick = { attachMenu = false; onSetup() })
                            }
                        }
                        OutlinedTextField(screen.input, controller::input, placeholder = { Text("Ask Omni…") }, shape = RoundedCornerShape(22.dp), modifier = Modifier.weight(1f), maxLines = 3, enabled = !busy)
                        IconButton(onClick = onMicrophone, enabled = !busy) { Icon(if (screen.listening) Icons.Default.MicOff else Icons.Default.Mic, if (screen.listening) "Stop listening" else "Speak your question") }
                        FilledIconButton(onClick = { if (busy) controller.chat.cancelCurrentRun() else controller.send() }, enabled = !screen.saving && (busy || screen.input.isNotBlank())) {
                            Icon(if (busy) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send, if (busy) "Stop request" else "Send question")
                        }
                    }
                }
            }
        }
    }
}

/** Only real operational events are shown here; no generated reasoning or invented progress. */
@Composable
private fun AssistantTrack(entry: AgentConsoleEntry) {
    val title = when (entry) {
        is AgentConsoleEntry.ToolEntry -> "Started · ${entry.toolName}"
        is AgentConsoleEntry.ResultEntry -> "${if (entry.isError) "Failed" else "Completed"} · ${entry.toolName} · ${entry.durationMs} ms"
        is AgentConsoleEntry.PhaseEntry -> entry.phase
        is AgentConsoleEntry.ErrorEntry -> "Error · ${entry.message}"
        else -> return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(28.dp).background(MaterialTheme.colorScheme.primary))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(remember(entry.timestamp) { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp)) }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ScreenRegionPicker(bitmap: android.graphics.Bitmap, onSelect: (ScreenSelection.Crop) -> Unit, onCancel: () -> Unit) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var start by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var end by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var hasSelection by remember(bitmap) { mutableStateOf(false) }
    LaunchedEffect(viewport) { hasSelection = false }
    val crop = if (hasSelection) ScreenSelection.crop(bitmap.width, bitmap.height,
        viewport.width.toFloat(), viewport.height.toFloat(), start.x, start.y, end.x, end.y) else null
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(bitmap.asImageBitmap(), "Frozen screen preview. Drag to select an area.",
            Modifier.fillMaxSize().onSizeChanged { viewport = it }.pointerInput(bitmap) {
                detectDragGestures(
                    onDragStart = { start = it; end = it; hasSelection = true },
                    onDrag = { change, delta -> change.consume(); end += delta },
                    onDragCancel = { hasSelection = false }
                )
            }, contentScale = ContentScale.Fit)
        Canvas(Modifier.fillMaxSize()) {
            if (hasSelection) {
                val left = min(start.x, end.x).coerceIn(0f, size.width)
                val right = max(start.x, end.x).coerceIn(0f, size.width)
                val top = min(start.y, end.y).coerceIn(0f, size.height)
                val bottom = max(start.y, end.y).coerceIn(0f, size.height)
                val shade = Color.Black.copy(alpha = .5f)
                drawRect(shade, size = Size(size.width, top))
                drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
                drawRect(shade, Offset(0f, top), Size(left, bottom - top))
                drawRect(shade, Offset(right, top), Size(size.width - right, bottom - top))
                drawRect(Color(0xFF8E8DE5), Offset(left, top), Size(right - left, bottom - top), style = Stroke(3.dp.toPx()))
            }
        }
        Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp), shape = RoundedCornerShape(20.dp)) {
            Text("Drag around what you want to ask about", Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
        }
        Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp), shape = RoundedCornerShape(24.dp)) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = { crop?.let(onSelect) }, enabled = crop != null) { Text("Use selection") }
            }
        }
    }
}
