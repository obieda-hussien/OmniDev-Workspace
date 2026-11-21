package com.omnidev.workspace.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A session-scoped export surface. Archive reads and large string/file creation run off-main.
 * No contacts, external storage permission or cloud upload is required.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionExportSheet(
    sessionId: Long,
    sessionTitle: String,
    isRunning: Boolean,
    loadSnapshot: suspend (Long) -> SessionTranscriptSnapshot?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeConsole by rememberSaveable(sessionId) { mutableStateOf(true) }
    var markdown by rememberSaveable(sessionId) { mutableStateOf(false) }
    var snapshot by remember(sessionId) { mutableStateOf<SessionTranscriptSnapshot?>(null) }
    var loading by remember(sessionId) { mutableStateOf(true) }
    var busy by remember(sessionId) { mutableStateOf(false) }
    var feedback by remember(sessionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId, isRunning) {
        if (isRunning) {
            // The unfinished turn is not an archive yet. Reload once the run completes.
            snapshot = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        feedback = null
        try {
            snapshot = withContext(Dispatchers.IO) { loadSnapshot(sessionId) }
            if (snapshot == null) feedback = "This conversation is no longer available."
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            feedback = "Couldn't read the saved conversation. Try again."
        } finally {
            loading = false
        }
    }

    fun perform(share: Boolean) {
        val data = snapshot ?: return
        if (busy || isRunning) return
        busy = true
        feedback = null
        scope.launch {
            try {
                val format = if (markdown) SessionExportFormat.MARKDOWN else SessionExportFormat.TEXT
                val text = withContext(Dispatchers.Default) {
                    SessionTranscriptExporter.render(data, includeConsole, format)
                }
                if (share) {
                    val uri = withContext(Dispatchers.IO) {
                        val directory = File(context.cacheDir, "chat-share").apply { mkdirs() }
                        val file = File(directory, "omni-session-$sessionId-${System.currentTimeMillis()}.${format.extension}")
                        file.writeText(text, Charsets.UTF_8)
                        // Bound cache growth without deleting the file that is being shared.
                        directory.listFiles()?.filter { it.isFile && it.name.startsWith("omni-session-") }
                            ?.sortedByDescending { it.lastModified() }?.drop(12)?.forEach { it.delete() }
                        FileProvider.getUriForFile(context, "${context.packageName}.chatmedia", file)
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, data.title)
                        clipData = ClipData.newUri(context.contentResolver, "Omni conversation", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share conversation"))
                    feedback = "File prepared for sharing."
                } else {
                    // Android's clipboard uses Binder. Refuse oversized transfers rather than
                    // silently copying only part of a long session or crashing the app.
                    if (withContext(Dispatchers.Default) { text.toByteArray(Charsets.UTF_8).size } > 512_000) {
                        feedback = "Too large for reliable clipboard copying. Use Share file to keep the entire conversation."
                    } else {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            ?: error("Clipboard is unavailable")
                        clipboard.setPrimaryClip(ClipData.newPlainText(data.title, text))
                        feedback = "Entire saved conversation copied."
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                feedback = if (share) "Couldn't share this conversation. Try again." else "Couldn't copy this conversation. Try Share file."
            } finally {
                busy = false
            }
        }
    }

    val data = snapshot
    val eventCount = remember(data) { data?.turns?.sumOf { it.consoleEntries.size } ?: 0 }
    val canExport = !loading && !busy && !isRunning && data != null && data.turns.isNotEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.Share, null, Modifier.padding(14.dp).size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text("Export conversation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(data?.title ?: sessionTitle, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(if (loading) "Reading saved messages…" else
                            "${data?.turns?.size ?: 0} messages · $eventCount console events",
                            style = MaterialTheme.typography.titleSmall)
                        if (loading) {
                            Spacer(Modifier.weight(1f))
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Include Agent Console", style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium)
                            Text("Tool calls, results, phases and errors for every AI turn",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = includeConsole, onCheckedChange = { includeConsole = it },
                            enabled = !busy)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Format", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !markdown, onClick = { markdown = false }, label = { Text("Plain text · TXT") },
                        enabled = !busy)
                    FilterChip(selected = markdown, onClick = { markdown = true }, label = { Text("Markdown · MD") },
                        enabled = !busy)
                }
            }
            Text("Includes the full saved archive, not just visible messages. Check for personal details, secrets or private tool outputs before sharing.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isRunning) Text("Finish the current agent run before exporting this conversation.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { perform(false) }, enabled = canExport, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy all")
                }
                Button(onClick = { perform(true) }, enabled = canExport, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share file")
                }
            }
        }
    }
}
