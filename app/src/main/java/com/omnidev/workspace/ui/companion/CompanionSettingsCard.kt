package com.omnidev.workspace.ui.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun CompanionSettingsCard() {
    val preferences by rememberCompanionPreferences()
    val context = LocalContext.current
    var previewIndex by remember { mutableStateOf(0) }
    val previewMood = CompanionMood.entries[previewIndex]
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CompanionArtwork(Modifier.size(96.dp)) { CompanionPose(mood = previewMood, lookX = .25f) }
                Text("Meet little Omni", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("A tiny companion with a curious spark.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(previewMood.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { previewIndex = (previewIndex + 1) % CompanionMood.entries.size }) { Text("Next expression") }
            }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column {
                CompanionToggle("Show companion", "In chat and the floating assistant", preferences.enabled) {
                    CompanionPreferenceStore.write(context, preferences.copy(enabled = it))
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                CompanionToggle("Hopping & roaming", "Let Omni explore the message box, your messages and agent console", preferences.roaming, preferences.enabled) {
                    CompanionPreferenceStore.write(context, preferences.copy(roaming = it))
                }
            }
        }
        Text("Tap Omni for a playful tumble, or drag and throw it. Its eyes follow your touch and it jumps to a visible perch when you scroll. It rests during idle time and follows your device’s reduced motion setting.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CompanionToggle(title: String, subtitle: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onCheckedChange = null, enabled = enabled)
    }
}
