package com.omnidev.workspace.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnidev.workspace.data.debug.DebugEntry
import com.omnidev.workspace.ui.components.SettingsEmptyState
import com.omnidev.workspace.ui.components.SettingsSearchField
import com.omnidev.workspace.ui.motion.OmniAnimatedVisibility as AnimatedVisibility
import com.omnidev.workspace.ui.motion.OmniIconButton
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    viewModel: DebugViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showConfirmClear by remember { mutableStateOf(false) }
    var deviceInfoExpanded by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedLevel by rememberSaveable { mutableStateOf("All") }
    val filteredEntries = remember(state.entries, query, selectedLevel) {
        state.entries.filter { entry ->
            (selectedLevel == "All" || entry.level.uppercase(Locale.ROOT) == selectedLevel) &&
                (query.isBlank() || entry.title.contains(query.trim(), true) || entry.body.contains(query.trim(), true) || entry.timestamp.contains(query.trim(), true))
        }
    }

    // Handle share/export
    LaunchedEffect(state.exportText) {
        val text = state.exportText ?: return@LaunchedEffect
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "OmniDev Debug Logs")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share debug logs"))
        viewModel.consumeExport()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug console", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { OmniIconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    OmniIconButton(onClick = { viewModel.loadLogs() }) { Icon(Icons.Default.Refresh, "Refresh logs") }
                    Box {
                        OmniIconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, "Debug actions") }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(text = { Text("Share all logs") }, enabled = state.entries.isNotEmpty(),
                                onClick = { menuExpanded = false; viewModel.requestExport() })
                            DropdownMenuItem(text = { Text("Clear all logs", color = MaterialTheme.colorScheme.error) }, enabled = state.entries.isNotEmpty(),
                                onClick = { menuExpanded = false; showConfirmClear = true })
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (state.isLoading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsSearchField(query, { query = it }, "Search logs")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf("All", "CRASH", "ERROR", "WARNING", "INFO")) { level ->
                                FilterChip(selected = selectedLevel == level, onClick = { selectedLevel = level },
                                    label = { Text(if (level == "All") level else level.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }) })
                            }
                        }
                        Text("${filteredEntries.size} shown · ${state.entries.size} saved logs", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // ── Device Info Card ───────────────────────────────────────
                item {
                    DeviceInfoCard(
                        deviceInfo = state.deviceInfo,
                        expanded = deviceInfoExpanded,
                        onToggle = { deviceInfoExpanded = !deviceInfoExpanded },
                        context = context
                    )
                }

                if (state.entries.isEmpty()) {
                    item {
                        EmptyLogsPlaceholder()
                    }
                } else {
                    if (filteredEntries.isEmpty()) item(key = "no_matches") {
                        SettingsEmptyState("No matching logs", "Try another level or search term.")
                    }
                    // ── Log entries ───────────────────────────────────────
                    items(filteredEntries, key = { it.filename }) { entry ->
                        DebugEntryCard(entry = entry, context = context)
                    }
                }

                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }

    // Confirm clear dialog
    if (showConfirmClear) {
        AlertDialog(
            onDismissRequest = { showConfirmClear = false },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Clear all logs?") },
            text = { Text("This will permanently delete all ${state.entries.size} log entries. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllLogs()
                        showConfirmClear = false
                    }
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmClear = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ── Device info card ────────────────────────────────────────────────────────

@Composable
private fun DeviceInfoCard(
    deviceInfo: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    context: Context
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Device Information",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                // Copy
                OmniIconButton(
                    onClick = {
                        copyToClipboard(context, "Device Info", deviceInfo)
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy device info",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                // Expand
                OmniIconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                SelectionContainer {
                    Text(
                        text = deviceInfo,
                        style = androidx.compose.material3.LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                }
            }
        }
    }
}

// ── Single log entry card ──────────────────────────────────────────────────

@Composable
private fun DebugEntryCard(entry: DebugEntry, context: Context) {
    var expanded by rememberSaveable(entry.filename) { mutableStateOf(false) }
    val accentColor = levelColor(entry.level)
    val levelIcon = levelIcon(entry.level)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = accentColor.copy(alpha = 0.35f),
                shape = RoundedCornerShape(20.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(accentColor.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    levelIcon,
                    contentDescription = entry.level,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LevelBadge(level = entry.level, color = accentColor)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            entry.timestamp,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        entry.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = accentColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Copy full body
                OmniIconButton(
                    onClick = {
                        copyToClipboard(context, entry.title, entry.body)
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy log entry",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                // Expand/collapse
                OmniIconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Expanded body — full log text, selectable
            AnimatedVisibility(visible = expanded) {
                SelectionContainer {
                    Text(
                        text = entry.body,
                        style = androidx.compose.material3.LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 17.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                }
            }
        }
    }
}

// ── Level badge ─────────────────────────────────────────────────────────────

@Composable
private fun LevelBadge(level: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.2f)
    ) {
        Text(
            text = level,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyLogsPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.BugReport,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No debug logs found",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Crashes and errors will appear here automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

@Composable
private fun levelColor(level: String): Color = when (level.uppercase(Locale.ROOT)) {
    "CRASH", "ERROR" -> MaterialTheme.colorScheme.error
    "WARNING" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

private fun levelIcon(level: String): ImageVector = when (level.uppercase(Locale.ROOT)) {
    "CRASH"   -> Icons.Filled.BugReport
    "ERROR"   -> Icons.Filled.Warning
    "WARNING" -> Icons.Filled.Warning
    else      -> Icons.Filled.Info
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}
