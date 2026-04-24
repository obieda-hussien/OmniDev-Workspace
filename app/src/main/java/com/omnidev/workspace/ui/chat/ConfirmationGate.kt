package com.omnidev.workspace.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * @property diffContent When non-null, a unified-diff string rendered by [DiffViewer]
 *                       instead of the plain monospace preview block.
 * @property onApprove Callback invoked when the user taps "Execute".
 * @property onDeny Callback invoked when the user taps "Cancel".
 */
data class PendingConfirmation(
    val id: String,
    val type: ConfirmationType,
    val preview: String,
    val diffContent: String? = null,
    val onApprove: () -> Unit,
    val onDeny: () -> Unit
)

enum class ConfirmationType {
    SHIZUKU_COMMAND,
    ANDROID_INTENT,
    GOD_MODE_FILE_WRITE,
    GOD_MODE_FILE_DELETE,
    /** File modification with a visual Git-style diff shown to the user. */
    GOD_MODE_FILE_PATCH
}

private val ConfirmationType.title: String
    get() = when (this) {
        ConfirmationType.SHIZUKU_COMMAND -> "⚡ Execute Shell Command?"
        ConfirmationType.ANDROID_INTENT -> "📱 Launch Android Intent?"
        ConfirmationType.GOD_MODE_FILE_WRITE -> "🔓 God Mode — Write File?"
        ConfirmationType.GOD_MODE_FILE_DELETE -> "🔓 God Mode — Delete File?"
        ConfirmationType.GOD_MODE_FILE_PATCH -> "📝 Review File Change?"
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
        ConfirmationType.GOD_MODE_FILE_PATCH ->
            "The AI agent wants to modify the file below. Review the diff before approving."
    }

/**
 * Git-style syntax-highlighted diff viewer.
 *
 * Lines beginning with `-` are rendered on a faded red background (deletions).
 * Lines beginning with `+` are rendered on a faded green background (additions).
 * Lines beginning with `@@` are rendered as hunk headers in a muted accent colour.
 * All other lines are shown in the default monospace style.
 */
@Composable
fun DiffViewer(
    diffText: String,
    modifier: Modifier = Modifier
) {
    val deletionBg  = Color(0xFF3C1F1F)
    val additionBg  = Color(0xFF1F3C1F)
    val hunkBg      = Color(0xFF1F2A3C)
    val deletionFg  = Color(0xFFFF8A80)
    val additionFg  = Color(0xFF69F0AE)
    val hunkFg      = Color(0xFF82B1FF)
    val defaultFg   = Color(0xFFE0E0E0)

    // Cache the split to avoid re-splitting on every recomposition.
    // Truncate to prevent IllegalStateException (Size out of range) on massive diffs.
    val lines = remember(diffText) {
        val allLines = diffText.lines()
        if (allLines.size > 1000) {
            allLines.take(1000) + "@@ ... [Diff truncated, ${allLines.size - 1000} lines omitted] @@"
        } else {
            allLines
        }
    }

    SelectionContainer(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF121212))
                .padding(horizontal = 8.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            lines.forEach { line ->
                val bg = when {
                    line.startsWith("-") && !line.startsWith("---") -> deletionBg
                    line.startsWith("+") && !line.startsWith("+++") -> additionBg
                    line.startsWith("@@") -> hunkBg
                    else -> Color.Transparent
                }
                val fg = when {
                    line.startsWith("---") || line.startsWith("+++") -> hunkFg
                    line.startsWith("@@") -> hunkFg
                    line.startsWith("-") -> deletionFg
                    line.startsWith("+") -> additionFg
                    else -> defaultFg
                }
                val bold = line.startsWith("@@") || line.startsWith("---") || line.startsWith("+++")
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = fg,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                }
            }
        }
    }
}

/**
 * Modal confirmation dialog shown whenever the agent attempts a privileged action.
 *
 * When [PendingConfirmation.diffContent] is non-null the body shows a [DiffViewer]
 * with colour-coded additions/deletions instead of the plain monospace preview block.
 *
 * The user must explicitly tap **Apply** for the action to proceed.
 * Tapping **Cancel** or dismissing the dialog calls [PendingConfirmation.onDeny].
 */
@Composable
fun ConfirmationGateDialog(confirmation: PendingConfirmation) {
    val hasDiff = confirmation.diffContent != null
    AlertDialog(
        onDismissRequest = confirmation.onDeny,
        icon = {
            Icon(
                if (hasDiff) Icons.Filled.Difference else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (hasDiff) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
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

                if (hasDiff) {
                    // ── Diff mode: syntax-highlighted DiffViewer ──
                    Text(
                        text = confirmation.preview,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    DiffViewer(
                        diffText = confirmation.diffContent!!,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    // ── Plain mode: monospace command preview ──
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.inverseSurface)
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            val safePreview = remember(confirmation.preview) {
                                val pLines = confirmation.preview.lines()
                                if (pLines.size > 1000) {
                                    (pLines.take(1000) + "... [Preview truncated, ${pLines.size - 1000} lines omitted]").joinToString("\n")
                                } else {
                                    confirmation.preview
                                }
                            }
                            Text(
                                text = safePreview,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                    lineHeight = 20.sp
                                )
                            )
                        }
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
                    containerColor = if (hasDiff) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.error
                )
            ) {
                Text(if (hasDiff) "Apply" else "Execute", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = confirmation.onDeny) {
                Text("Cancel")
            }
        }
    )
}
