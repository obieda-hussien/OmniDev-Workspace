package com.omnidev.workspace.ui.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class AssistantPanel { NONE, ATTACHMENTS, FILE_PATH }

/** VoiceInteractionSession has its own window; controls stay inside that window. */
@Composable
internal fun AssistantInlinePanel(
    panel: AssistantPanel,
    filePath: String,
    onFilePath: (String) -> Unit,
    onAddPath: () -> Unit,
    onChoosePath: () -> Unit,
    onAttach: () -> Unit,
    onSystemVoice: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (panel == AssistantPanel.NONE) return
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (panel == AssistantPanel.FILE_PATH) "File path" else "Add to this message",
                Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close attachment options") }
        }
        if (panel == AssistantPanel.FILE_PATH) {
            OutlinedTextField(filePath, onFilePath, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("/storage/emulated/0/…") }, maxLines = 2,
                supportingText = { Text("Use an accessible path for larger or unsupported files.") })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onClose) { Text("Cancel") }
                Button(onClick = onAddPath, enabled = filePath.isNotBlank()) { Text("Add path") }
            }
        } else {
            OutlinedButton(onClick = onAttach, modifier = Modifier.fillMaxWidth()) { Text("Files, photos & videos") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onChoosePath, modifier = Modifier.weight(1f)) { Text("File path") }
                TextButton(onClick = onSystemVoice, modifier = Modifier.weight(1f)) { Text("System voice input") }
            }
        }
    }
}
