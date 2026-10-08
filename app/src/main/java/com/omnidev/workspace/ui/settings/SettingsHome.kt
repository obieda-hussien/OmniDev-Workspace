package com.omnidev.workspace.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.ui.motion.OmniIconButton

@Composable
internal fun SettingsHome(
    query: String,
    onQueryChange: (String) -> Unit,
    entries: List<SettingsEntry>,
    summaries: Map<SettingsDestination, String>,
    listState: LazyListState,
    onOpen: (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val matches = remember(query, entries) { SettingsCatalog.search(query, entries) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search settings") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        OmniIconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                shape = RoundedCornerShape(18.dp)
            )
        }
        if (query.isNotBlank()) {
            item(key = "result_count") {
                Text(
                    if (matches.isEmpty()) "No settings found" else "${matches.size} matching settings",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (matches.isEmpty()) {
            item(key = "empty") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Search, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Try a feature name, like voice, models or permissions.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onQueryChange("") }) { Text("Show all settings") }
                }
            }
        }
        SettingsGroup.entries.forEach { group ->
            val groupEntries = matches.filter { it.group == group }
            if (groupEntries.isNotEmpty()) {
                item(key = group.name) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (query.isBlank()) Text(group.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column {
                                groupEntries.forEachIndexed { index, entry ->
                                    SettingsRow(entry, summaries[entry.destination], onClick = { onOpen(entry.destination) })
                                    if (index < groupEntries.lastIndex) {
                                        HorizontalDivider(Modifier.padding(start = 60.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(entry: SettingsEntry, summary: String?, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(entry.destination.icon(), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(summary ?: entry.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun SettingsDestination.icon(): ImageVector = when (this) {
    SettingsDestination.PROVIDERS -> Icons.Default.Key
    SettingsDestination.MODELS -> Icons.Default.Hub
    SettingsDestination.REASONING -> Icons.Default.AutoAwesome
    SettingsDestination.LOCAL_MODELS -> Icons.Default.PhoneAndroid
    SettingsDestination.ASSISTANT -> Icons.Default.SmartToy
    SettingsDestination.VOICE -> Icons.Default.Mic
    SettingsDestination.DEVICE_ACCESS -> Icons.Default.Security
    SettingsDestination.ACCESSIBILITY -> Icons.Default.AccessibilityNew
    SettingsDestination.FILE_ACCESS -> Icons.Default.FolderOpen
    SettingsDestination.COMPANION -> Icons.Default.AutoAwesome
    SettingsDestination.PROFILE -> Icons.Default.PersonOutline
    SettingsDestination.MEMORY -> Icons.Default.Bookmarks
    SettingsDestination.INTEGRATIONS -> Icons.Default.Link
    SettingsDestination.MCP -> Icons.Default.Dns
    SettingsDestination.SCHEDULE -> Icons.Default.Schedule
    SettingsDestination.SKILLS -> Icons.Default.Extension
    SettingsDestination.ANALYTICS -> Icons.Default.BarChart
    SettingsDestination.BRAIN -> Icons.Default.Psychology
    SettingsDestination.DEBUG -> Icons.Default.BugReport
}
