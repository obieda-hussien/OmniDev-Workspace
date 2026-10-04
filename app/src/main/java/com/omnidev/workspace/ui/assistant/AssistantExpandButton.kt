package com.omnidev.workspace.ui.assistant

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable

@Composable
internal fun AssistantExpandButton(
    isSaving: Boolean,
    isMinimizing: Boolean,
    isProcessing: Boolean,
    hasPendingConfirmation: Boolean,
    onExpand: () -> Unit
) {
    val enabled = !isSaving && !isMinimizing && !isProcessing && !hasPendingConfirmation
    IconButton(onClick = onExpand, enabled = enabled) {
        Icon(Icons.Default.OpenInFull, "Open full conversation")
    }
}
