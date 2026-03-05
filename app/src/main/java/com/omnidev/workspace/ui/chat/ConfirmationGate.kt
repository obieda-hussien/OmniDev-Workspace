package com.omnidev.workspace.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A pending privileged action awaiting user approval.
 *
 * @property id Unique ID for this confirmation request.
 * @property type The category of action (used for dialog title / icon).
 * @property preview Human-readable description of EXACTLY what will execute.
 * @property onApprove Callback invoked when the user taps "Execute".
 * @property onDeny Callback invoked when the user taps "Cancel".
 */
data class PendingConfirmation(
    val id: String,
    val type: ConfirmationType,
    val preview: String,
    val onApprove: () -> Unit,
    val onDeny: () -> Unit
)

enum class ConfirmationType {
    SHIZUKU_COMMAND,
    ANDROID_INTENT,
    GOD_MODE_FILE_WRITE,
    GOD_MODE_FILE_DELETE
}

private val ConfirmationType.title: String
    get() = when (this) {
        ConfirmationType.SHIZUKU_COMMAND -> "⚡ Execute Shell Command?"
        ConfirmationType.ANDROID_INTENT -> "📱 Launch Android Intent?"
        ConfirmationType.GOD_MODE_FILE_WRITE -> "🔓 God Mode — Write File?"
        ConfirmationType.GOD_MODE_FILE_DELETE -> "🔓 God Mode — Delete File?"
    }

private val ConfirmationType.subtitle: String
    get() = when (this) {
        ConfirmationType.SHIZUKU_COMMAND ->
            "The AI agent wants to run the following ADB/shell command with elevated privileges."
        ConfirmationType.ANDROID_INTENT ->
            "The AI agent wants to fire the following Android Intent."
        ConfirmationType.GOD_MODE_FILE_WRITE ->
            "The AI agent wants to write to a file OUTSIDE the Target Context scope."
        ConfirmationType.GOD_MODE_FILE_DELETE ->
            "The AI agent wants to delete a file OUTSIDE the Target Context scope."
    }

/**
 * Modal confirmation dialog shown whenever the agent attempts a privileged action.
 *
 * The user must explicitly tap **Execute** for the action to proceed.
 * Tapping **Cancel** or dismissing the dialog calls [PendingConfirmation.onDeny].
 */
@Composable
fun ConfirmationGateDialog(confirmation: PendingConfirmation) {
    AlertDialog(
        onDismissRequest = confirmation.onDeny,
        icon = {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = confirmation.type.title,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = confirmation.type.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(12.dp))
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.inverseSurface)
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = confirmation.preview,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                                lineHeight = 20.sp
                            )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text(
                        text = "Review carefully before executing",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = confirmation.onApprove,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Execute", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = confirmation.onDeny) {
                Text("Cancel")
            }
        }
    )
}
