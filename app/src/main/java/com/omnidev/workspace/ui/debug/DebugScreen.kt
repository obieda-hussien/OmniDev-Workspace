package com.omnidev.workspace.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnidev.workspace.data.debug.DebugEntry

// ── Color palette for log levels ──────────────────────────────────────────
private val CrashColor  = Color(0xFFFF4444)
private val ErrorColor  = Color(0xFFFF8800)
private val WarnColor   = Color(0xFFFFDD00)
private val InfoColor   = Color(0xFF44AAFF)
private val SurfaceDark = Color(0xFF0D1117)
private val CodeBackground = Color(0xFF161B22)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    viewModel: DebugViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showConfirmClear by remember { mutableStateOf(false) }
    var deviceInfoExpanded by remember { mutableStateOf(false) }

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
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.BugReport,
                            contentDescription = null,
                            tint = CrashColor,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Debug Console",
                            fontWeight = FontWeight.Bold
                        )
                        if (state.entries.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Badge(
                                containerColor = CrashColor
                            ) {
                                Text(
                                    state.entries.size.toString(),
                                    color = Color.White,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Refresh
                    IconButton(onClick = { viewModel.loadLogs() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh logs")
                    }
                    // Share all
                    IconButton(
                        onClick = { viewModel.requestExport() },
                        enabled = state.entries.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Share all logs")
                    }
                    // Clear all
                    IconButton(
                        onClick = { showConfirmClear = true },
                        enabled = state.entries.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Clear all logs",
                            tint = if (state.entries.isNotEmpty()) CrashColor
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
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
                CircularProgressIndicator(color = CrashColor)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                    // ── Log entries ───────────────────────────────────────
                    items(state.entries, key = { it.filename }) { entry ->
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
            icon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = CrashColor) },
            title = { Text("Clear all logs?") },
            text = { Text("This will permanently delete all ${state.entries.size} log entries. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllLogs()
                        showConfirmClear = false
                    }
                ) {
                    Text("Clear", color = CrashColor)
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
        colors = CardDefaults.cardColors(containerColor = CodeBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.animateContentSize()) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1C2128))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.PhoneAndroid,
                    contentDescription = null,
                    tint = InfoColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Device Information",
                    fontWeight = FontWeight.SemiBold,
                    color = InfoColor,
                    modifier = Modifier.weight(1f)
                )
                // Copy
                IconButton(
                    onClick = {
                        copyToClipboard(context, "Device Info", deviceInfo)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy device info",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                }
                // Expand
                IconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                SelectionContainer {
                    Text(
                        text = deviceInfo,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFFADBBC4),
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
    var expanded by remember { mutableStateOf(entry.level == "CRASH") }
    val accentColor = levelColor(entry.level)
    val levelIcon = levelIcon(entry.level)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = accentColor.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = CodeBackground),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.animateContentSize()) {
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
                            color = Color.Gray,
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
                IconButton(
                    onClick = {
                        copyToClipboard(context, entry.title, entry.body)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy log entry",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                }
                // Expand/collapse
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Expanded body — full log text, selectable
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                SelectionContainer {
                    Text(
                        text = entry.body,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFFCDD9E5),
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
            fontSize = 9.sp,
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
            tint = Color.Gray.copy(alpha = 0.4f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No debug logs found",
            style = MaterialTheme.typography.titleMedium,
            color = Color.Gray
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Crashes and errors will appear here automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray.copy(alpha = 0.7f)
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun levelColor(level: String): Color = when (level.uppercase()) {
    "CRASH"   -> CrashColor
    "ERROR"   -> ErrorColor
    "WARNING" -> WarnColor
    else      -> InfoColor
}

private fun levelIcon(level: String): ImageVector = when (level.uppercase()) {
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
