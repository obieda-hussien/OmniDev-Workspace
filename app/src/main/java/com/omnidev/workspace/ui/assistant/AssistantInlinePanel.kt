package com.omnidev.workspace.ui.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.ui.motion.OmniIconButton

internal enum class AssistantPanel { NONE, ATTACHMENTS, FILE_PATH }

/** Keep controls in the assistant's original window; VoiceInteractionSession has no Activity. */
@Composable
internal fun AssistantInlinePanel(
    panel: AssistantPanel, filePath: String, onFilePath: (String) -> Unit,
    onAddPath: () -> Unit, onChoosePath: () -> Unit, onAttach: () -> Unit,
    onSystemVoice: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
    onMicrophone: (() -> Unit)? = null, onScreen: (() -> Unit)? = null, onSelectArea: (() -> Unit)? = null, onAccess: (() -> Unit)? = null
) {
    if (panel == AssistantPanel.NONE) return
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (panel == AssistantPanel.FILE_PATH) "File path" else "Tools for this message",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (panel != AssistantPanel.FILE_PATH) Text("Choose what to share with Omni.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OmniIconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close attachment options") }
        }
        if (panel == AssistantPanel.FILE_PATH) {
            OutlinedTextField(filePath, onFilePath, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                placeholder = { Text("/storage/emulated/0/…") }, maxLines = 3,
                supportingText = { Text("Use an accessible path for larger or unsupported files.") })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onClose) { Text("Cancel") }
                FilledTonalButton(onClick = onAddPath, enabled = filePath.isNotBlank()) { Text("Add path") }
            }
        } else {
            onScreen?.let { AssistantToolRow("Current screen", "Attach the screen captured when you opened Omni", Icons.Default.Screenshot, it) }
            onSelectArea?.let { AssistantToolRow("Select an area", "Choose just the part you want to share", Icons.Default.CropFree, it) }
            AssistantToolRow("Files, photos & videos", "Choose from your device", Icons.Default.AttachFile, onAttach)
            AssistantToolRow("File path", "Reference a larger or unsupported file", Icons.Default.FolderOpen, onChoosePath)
            onMicrophone?.let { AssistantToolRow("Voice dictation", "Speak your question to Omni", Icons.Default.Mic, it) }
            AssistantToolRow("System voice input", "Use Android's voice input", Icons.Default.Mic, onSystemVoice)
            onAccess?.let { AssistantToolRow("Device access and permissions", "Review what Omni can access", Icons.Default.Security, it) }
        }
    }
}

@Composable
private fun AssistantToolRow(title: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
