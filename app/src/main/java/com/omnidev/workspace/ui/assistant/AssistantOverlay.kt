package com.omnidev.workspace.ui.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
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

/** The same compact surface is hosted by Android's assistant window and the ASSIST fallback. */
@Composable
fun AssistantOverlay(
    controller: AssistantController,
    onDismiss: () -> Unit,
    onExpand: () -> Unit,
    onSetup: () -> Unit,
    onMicrophone: () -> Unit
) {
    val screen by controller.state.collectAsStateWithLifecycle()
    val chat by controller.chat.uiState.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val motion = LocalOmniMotion.current
    val clipboard = LocalClipboardManager.current
    val response = chat.streamingContent ?: chat.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.content
    val modeRequest = chat.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.takeIf { it.executionRequest?.status == "pending" }
    val busy = chat.isProcessing || screen.saving
    val reveal = remember { MutableTransitionState(false) }.apply { targetState = screen.visible }

    chat.pendingConfirmation?.let { confirmation ->
        ConfirmationGateDialog(confirmation.copy(
            onApprove = { confirmation.onApprove(); controller.chat.clearConfirmation() },
            onDeny = { confirmation.onDeny(); controller.chat.clearConfirmation() }
        ))
    }

    if (screen.selecting && screen.screenshot != null) {
        ScreenRegionPicker(screen.screenshot!!, controller::select, controller::cancelSelection)
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .12f)).clickable(onClick = onDismiss))
        val availableHeight = maxHeight
        AnimatedVisibility(
            visibleState = reveal,
            enter = fadeIn(tween(motion.responseMillis)) + slideInVertically(tween(motion.navigationMillis)) { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(12.dp)
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = availableHeight * .86f),
                shape = RoundedCornerShape(30.dp), color = colors.surface,
                tonalElevation = 4.dp, shadowElevation = 10.dp
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp)
                        .size(30.dp, 3.dp).clip(CircleShape).background(colors.outlineVariant))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(32.dp)) {
                            drawCircle(Brush.linearGradient(listOf(Color(0xFF8E8DE5), Color(0xFF58D6D0))))
                            drawCircle(Color.White.copy(alpha = .9f), radius = size.minDimension * .16f)
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text("Omni", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(if (screen.listening) "Listening…" else if (busy) chat.agentStatus ?: "Working…" else "Here, with you",
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                        }
                        IconButton(onClick = onExpand, enabled = !screen.saving) { Icon(Icons.Default.OpenInFull, "Open full conversation") }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss assistant") }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (response.isNullOrBlank() && !busy) {
                            Text("What can I help you with?", style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(vertical = 10.dp))
                        }
                        if (!response.isNullOrBlank()) {
                            SelectionContainerCompat(response)
                            TextButton(onClick = { clipboard.setText(AnnotatedString(response)) }) {
                                Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Copy answer")
                            }
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        modeRequest?.let { message ->
                            Text("Omni needs permission to switch modes for this request.", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { controller.chat.acceptExecutionMode(message.messageId) }, enabled = !busy) { Text("Allow once") }
                                TextButton(onClick = { controller.chat.denyExecutionMode(message.messageId) }, enabled = !busy) { Text("Decline") }
                            }
                        }
                        (screen.message ?: chat.errorMessage)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error)
                            TextButton(onClick = onSetup) { Text("Assistant settings") }
                        }
                        screen.attachment?.let { image ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh)
                                .padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Image(image.asImageBitmap(), "Screen image ready to send", Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text("Screen attached", style = MaterialTheme.typography.labelLarge)
                                    Text("Sent only when you ask", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                }
                                IconButton(onClick = controller::removeImage, enabled = !busy) { Icon(Icons.Default.Close, "Remove screen image") }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                listOf("Explain", "Translate", "Summarize").forEach { action ->
                                    TextButton(onClick = { controller.input("$action this screen image.") }, enabled = !busy) { Text(action) }
                                }
                            }
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
                        OutlinedTextField(
                            value = screen.input, onValueChange = controller::input,
                            placeholder = { Text("Ask Omni…") }, shape = RoundedCornerShape(22.dp),
                            modifier = Modifier.weight(1f), maxLines = 3, enabled = !busy
                        )
                        IconButton(onClick = onMicrophone, enabled = !busy) {
                            Icon(if (screen.listening) Icons.Default.MicOff else Icons.Default.Mic, if (screen.listening) "Stop listening" else "Speak your question")
                        }
                        FilledIconButton(onClick = { if (busy) controller.chat.cancelCurrentRun() else controller.send() },
                            enabled = !screen.saving && (busy || screen.input.isNotBlank())) {
                            Icon(if (busy) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send, if (busy) "Stop request" else "Send question")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionContainerCompat(text: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
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
