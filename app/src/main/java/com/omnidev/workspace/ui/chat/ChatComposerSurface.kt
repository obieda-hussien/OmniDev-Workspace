package com.omnidev.workspace.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.TextFieldValue
import com.omnidev.workspace.ui.companion.rememberCompanionEditorGaze
import com.omnidev.workspace.ui.companion.rememberCompanionEditorFocus
import com.omnidev.workspace.ui.companion.CompanionAnchor
import com.omnidev.workspace.ui.companion.companionAnchor
import com.omnidev.workspace.ui.motion.LocalOmniMotion
import com.omnidev.workspace.ui.motion.OmniEasing
import com.omnidev.workspace.ui.motion.OmniIconButton

/** Shared editor for the main chat and assistant's existing native window. No popup or dialog. */
@Composable
internal fun ChatComposerSurface(
    inputText: String, onInputChanged: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit,
    isProcessing: Boolean, onTools: () -> Unit, sendEnabled: Boolean,
    focusRequester: FocusRequester = remember { FocusRequester() }, compact: Boolean = false,
    editorEnabled: Boolean = true, toolsEnabled: Boolean = !isProcessing, actionEnabled: Boolean = true,
    toolsDescription: String = "Conversation tools", editorDescription: String = "Message Omni",
    sendDescription: String = "Send", stopDescription: String = "Stop agent",
    allowSteering: Boolean = false,
    onFocusChanged: (Boolean) -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val companionFocus = rememberCompanionEditorFocus()
    val companionGaze = rememberCompanionEditorGaze()
    var fieldState by remember { mutableStateOf(TextFieldValue(inputText, TextRange(inputText.length))) }
    // Keep IME composition/selection locally while preserving the shared String draft API.
    val fieldValue = if (fieldState.text == inputText) fieldState else TextFieldValue(inputText, TextRange(inputText.length))
    val editorScroll = rememberScrollState()
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val maxTextHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() } * if (compact) 3 else 5
    val maxTextHeightPx = with(LocalDensity.current) { maxTextHeight.toPx() }
    // Own the vertical viewport so the caret's root position includes the actual scroll offset.
    LaunchedEffect(fieldValue.text, fieldValue.selection, textLayout, maxTextHeightPx) {
        val layout = textLayout?.takeIf { it.layoutInput.text.text == fieldValue.text } ?: return@LaunchedEffect
        val cursor = layout.getCursorRect(fieldValue.selection.end)
        val scroll = editorScroll.value
        if (cursor.bottom > scroll + maxTextHeightPx) editorScroll.scrollTo((cursor.bottom - maxTextHeightPx).toInt())
        else if (cursor.top < scroll) editorScroll.scrollTo(cursor.top.toInt().coerceAtLeast(0))
    }
    SideEffect {
        if (fieldState != fieldValue) fieldState = fieldValue
        companionGaze.value = fieldValue
        companionGaze.publish()
        companionFocus(focused)
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val motion = LocalOmniMotion.current
    val borderColor by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f),
        animationSpec = tween(motion.responseMillis, easing = OmniEasing), label = "composer focus")
    Surface(modifier = Modifier.companionAnchor(CompanionAnchor.COMPOSER), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, borderColor)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.Bottom) {
            OmniIconButton(onClick = { focus.clearFocus(); keyboard?.hide(); onTools() }, enabled = toolsEnabled) {
                Icon(Icons.Default.Add, toolsDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BasicTextField(value = fieldValue, onValueChange = { next ->
                val changed = fieldState.text != next.text
                val moved = fieldState.selection != next.selection
                fieldState = next
                companionGaze.value = next
                companionGaze.publish(edited = changed || moved)
                if (changed) onInputChanged(next.text)
            }, onTextLayout = {
                textLayout = it
                companionGaze.layout = it
                companionGaze.publish()
            }, enabled = editorEnabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused; onFocusChanged(it.isFocused); companionFocus(it.isFocused) }
                    .semantics { contentDescription = editorDescription },
                minLines = 1, maxLines = Int.MAX_VALUE,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, textDirection = TextDirection.Content),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { field ->
                    Box(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                        if (inputText.isEmpty()) Text(if (isProcessing && allowSteering) "Correct or add an instruction…" else if (isProcessing) "Write your next message…" else "Message Omni…",
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.fillMaxWidth().heightIn(max = maxTextHeight).verticalScroll(editorScroll)
                            .onGloballyPositioned { companionGaze.coordinates = it; companionGaze.publish() }) { field() }
                    }
                })
            if (isProcessing && allowSteering) {
                OmniIconButton(onClick = onStop, enabled = actionEnabled) {
                    Icon(Icons.Default.Stop, stopDescription, tint = MaterialTheme.colorScheme.error)
                }
            }
            val stopping = isProcessing && !allowSteering
            FilledIconButton(onClick = {
                if (stopping) onStop() else { keyboard?.hide(); focus.clearFocus(); onSend() }
            }, enabled = actionEnabled && (stopping || sendEnabled),
                modifier = Modifier.size(48.dp).semantics { contentDescription = if (stopping) stopDescription else if (isProcessing) "Send follow-up" else sendDescription },
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (stopping) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary,
                    contentColor = if (stopping) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimary)) {
                ChatControlTransition(stopping, "send or stop") { processing ->
                    Icon(if (processing) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send, null, Modifier.size(22.dp))
                }
            }
        }
    }
}
