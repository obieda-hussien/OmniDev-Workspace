package com.omnidev.workspace.ui.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnidev.workspace.data.assistant.AssistantController
import com.omnidev.workspace.ui.motion.LocalOmniMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** Native assistant and Activity fallback share one presentation and the existing controller. */
@Composable
fun AssistantOverlay(
    controller: AssistantController, onDismiss: () -> Unit, onExpand: () -> Unit,
    onSetup: () -> Unit, onMicrophone: () -> Unit, onMinimize: () -> Unit,
    onAttach: () -> Unit, onSystemVoice: () -> Unit, onAccess: () -> Unit = onSetup
) {
    if (rememberDeviceLocked()) {
        LaunchedEffect(controller) { controller.clearScreen() }
        LockedAssistantPanel(onDismiss)
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val taskHub = remember(context) { com.omnidev.workspace.data.routines.RoutineLearningHub.get(context) }
    val localTask by taskHub.latestRun.collectAsStateWithLifecycle()
    val lesson by taskHub.teaching.collectAsStateWithLifecycle()
    val screen by controller.state.collectAsStateWithLifecycle()
    val chat by controller.chat.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val motion = LocalOmniMotion.current
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(screen.visible) { if (screen.visible) leaving = false }
    fun dismiss() {
        if (leaving) return
        leaving = true
        scope.launch { controller.hide(); delay(motion.navigationMillis.toLong()); onDismiss() }
    }
    if (screen.selecting && screen.screenshot != null) {
        ScreenRegionPicker(screen.screenshot!!, controller::select, controller::cancelSelection)
        return
    }
    val confirmation = chat.pendingConfirmation
    val reviewed = confirmation?.let { pending -> pending.copy(
        onApprove = { pending.onApprove(); controller.chat.clearConfirmation() },
        onDeny = { pending.onDeny(); controller.chat.clearConfirmation() }) }
    AssistantConversation(screen, chat.copy(pendingConfirmation = reviewed), controller.flavor,
        mentionLoader = controller.chat::loadMentionCandidates, onInputChanged = controller::input, onSend = { controller.send() }, onStop = { controller.chat.cancelCurrentRun() },
        onDismiss = ::dismiss, onExpand = onExpand, onMinimize = onMinimize,
        onMicrophone = onMicrophone, onAttach = onAttach, onSystemVoice = onSystemVoice,
        onAccess = onAccess, onSetup = onSetup, onScreen = controller::useScreen,
        onRemoveImage = controller::removeImage, onRemoveFile = controller::removeFile,
        onReply = controller.chat::setReplyingTo, onDismissReply = controller.chat::clearReplyingTo,
        onEditLastUser = controller.chat::editLastUserMessage, onRegenerateLast = controller.chat::regenerateLastResponse,
        onClearError = { controller.message(null); controller.chat.clearError() },
        onModeDecision = { id, approval ->
            if (approval == null) controller.chat.denyExecutionMode(id) else controller.chat.acceptExecutionMode(id, approval)
        }, extraContent = {
            if (lesson != null || localTask != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lesson?.let { title ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(title, style = MaterialTheme.typography.labelLarge)
                            Row {
                                TextButton(onClick = { taskHub.decision() }) { Text("Decision") }
                                TextButton(onClick = { taskHub.stopTeaching() }) { Text("Save lesson") }
                            }
                        }
                    }
                }
                localTask?.let { run ->
                    if (run.status == com.omnidev.workspace.data.routines.RoutineRunStatus.RUNNING ||
                        run.status == com.omnidev.workspace.data.routines.RoutineRunStatus.PAUSED) {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("Local task · step ${run.nextStep + 1}", style = MaterialTheme.typography.labelLarge)
                                if (run.status == com.omnidev.workspace.data.routines.RoutineRunStatus.RUNNING) {
                                    TextButton(onClick = { controller.chat.pauseLocalRoutine() }) { Text("Pause / take over") }
                                } else TextButton(enabled = !chat.isProcessing && !screen.saving && !screen.minimizing, onClick = {
                                    controller.input("Help with paused learned task ${run.routineId}, run ${run.id}, step ${run.nextStep}. " +
                                        "Inspect current state and solve only this step. Do not repeat earlier actions. " +
                                        "Use learned_routine inspect/status; resume only after its expected result is verified.")
                                }) { Text("Help with step") }
                            }
                        }
                    }
                }
            }
        })
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
