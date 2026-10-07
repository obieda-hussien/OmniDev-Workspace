package com.omnidev.workspace.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp

/** Inline editor also works inside VoiceInteractionSession, where dialog windows are unavailable. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun LastUserMessageEditor(
    text: String, onTextChanged: (String) -> Unit, enabled: Boolean, canSave: Boolean,
    attachmentCount: Int, onSave: () -> Unit, onCancel: () -> Unit
) {
    val focus = remember { FocusRequester() }
    val visibility = remember { BringIntoViewRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    LaunchedEffect(imeBottom) { if (imeBottom > 0) visibility.bringIntoView() }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(visibility).testTag("last-message-editor")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Edit your last message", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(text, onTextChanged, Modifier.fillMaxWidth().focusRequester(focus).testTag("last-message-text"),
                enabled = enabled, minLines = 2, maxLines = 8,
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                label = { Text("Message") }, shape = RoundedCornerShape(14.dp))
            Text(if (attachmentCount > 0) "$attachmentCount attached files will be kept. The previous response will be replaced."
                else "The previous response will be replaced.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Agent actions already completed remain applied; generating again can run tools again.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onCancel, enabled = enabled) { Text("Cancel") }
                Button(onSave, enabled = enabled && canSave, modifier = Modifier.testTag("save-regenerate")) { Text("Save & regenerate") }
            }
        }
    }
}
