package com.omnidev.workspace.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import com.omnidev.workspace.ui.components.SettingsEmptyState
import com.omnidev.workspace.ui.components.SettingsSearchField
import com.omnidev.workspace.ui.motion.OmniIconButton
import com.omnidev.workspace.ui.motion.LocalOmniMotion
import com.omnidev.workspace.ui.motion.OmniEasing
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class HistoryFilter(val label: String) {
    ALL("All"), PINNED("Pinned"), APP("App"), TELEGRAM("Telegram"), DISCORD("Discord"),
    WHATSAPP("WhatsApp"), EXTERNAL("Connected apps")
}
private enum class HistorySort(val label: String) { RECENT("Recent"), OLDEST("Oldest"), TITLE("A–Z") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatHistoryDrawer(
    sessions: List<ChatSessionEntity>, currentSessionId: Long?, onNewSession: () -> Unit,
    onSessionClick: (Long) -> Unit, onTogglePin: (Long) -> Unit, onRenameSession: (Long, String) -> Unit,
    onDeleteSession: (Long) -> Unit, onDeleteAllSessions: () -> Unit, onDeleteSelectedSessions: (Set<Long>) -> Unit,
    onCloseDrawer: () -> Unit, isOpen: Boolean = true, onSettings: () -> Unit = {}, onBrowser: () -> Unit = {}
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(HistorySort.RECENT) }
    var selection by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var menu by remember { mutableStateOf(false) }
    var sourcesMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<Long?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val motion = LocalOmniMotion.current
    LaunchedEffect(sessions) {
        val existingIds = sessions.mapTo(hashSetOf()) { it.id }
        selectedIds = selectedIds.filter { it in existingIds }
    }
    LaunchedEffect(isOpen) { if (!isOpen) { selection = false; selectedIds = emptyList() } }
    BackHandler(isOpen && selection) { selection = false; selectedIds = emptyList() }
    val locale = Locale.getDefault()
    val visible = remember(sessions, query, filter, sort, locale) {
        sessions.filter {
            (query.isBlank() || it.title.contains(query.trim(), true) || sessionSourceLabel(it).contains(query.trim(), true)) && when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.PINNED -> it.isPinned
                HistoryFilter.APP -> it.source == ChatSessionEntity.SOURCE_APP
                HistoryFilter.TELEGRAM -> it.source == ChatSessionEntity.SOURCE_TELEGRAM
                HistoryFilter.DISCORD -> it.source == ChatSessionEntity.SOURCE_DISCORD
                HistoryFilter.WHATSAPP -> it.source == ChatSessionEntity.SOURCE_WHATSAPP_BRIDGE
                HistoryFilter.EXTERNAL -> it.source == ChatSessionEntity.SOURCE_EXTERNAL_APP
            }
        }.let { rows -> when (sort) {
            HistorySort.RECENT -> rows.sortedByDescending { it.lastUpdated }
            HistorySort.OLDEST -> rows.sortedBy { it.lastUpdated }
            HistorySort.TITLE -> rows.sortedBy { it.title.lowercase(locale) }
        } }
    }
    val dayKey = Calendar.getInstance().let { it.get(Calendar.YEAR) to it.get(Calendar.DAY_OF_YEAR) }
    val sections = remember(visible, sort, dayKey, locale) {
        val (pinned, others) = visible.partition { it.isPinned }
        linkedMapOf<String, List<ChatSessionEntity>>().apply {
            if (pinned.isNotEmpty()) put("Pinned", pinned)
            putAll(if (sort == HistorySort.RECENT) groupHistorySessionsByDate(others) else linkedMapOf("Conversations" to others))
        }
    }
    val visibleIds = remember(visible) { visible.map { it.id } }
    val allSelected = visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)
    fun toggle(id: Long) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    sessions.firstOrNull { it.id == renameId }?.let { target ->
        AlertDialog(onDismissRequest = { renameId = null }, title = { Text("Rename conversation") },
            text = { OutlinedTextField(renameText, { renameText = it }, label = { Text("Title") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRenameSession(target.id, renameText.trim()); renameId = null }, enabled = renameText.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renameId = null }) { Text("Cancel") } })
    }
    sessions.firstOrNull { it.id == deleteId }?.let { target ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Delete conversation?") },
            text = { Text("“${target.title}” and its messages will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { onDeleteSession(target.id); deleteId = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } })
    }
    deleteGroup?.let { group ->
        val count = if (group == "all") sessions.size else selectedIds.size
        AlertDialog(onDismissRequest = { deleteGroup = null }, title = { Text(if (group == "all") "Delete all conversations?" else "Delete selected conversations?") },
            text = { Text("$count conversations and their messages will be permanently deleted.") },
            confirmButton = { TextButton(enabled = count > 0, onClick = {
                if (group == "all") onDeleteAllSessions() else onDeleteSelectedSessions(selectedIds.toSet())
                deleteGroup = null; selection = false; selectedIds = emptyList()
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteGroup = null }) { Text("Cancel") } })
    }
    Column(Modifier.fillMaxHeight().imePadding().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (selection) "${selectedIds.size} selected" else "Conversations", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (!selection) Text("${sessions.size} saved chats", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selection) {
                OmniIconButton(onClick = { deleteGroup = "selected" }, enabled = selectedIds.isNotEmpty()) { Icon(Icons.Default.Delete, "Delete selected conversations") }
                OmniIconButton(onClick = { selection = false; selectedIds = emptyList() }) { Icon(Icons.Default.Close, "Cancel selection") }
            } else OmniIconButton(onClick = onCloseDrawer) { Icon(Icons.Default.Close, "Close conversations") }
        }
        if (!selection) {
            Button(onClick = { onNewSession(); onCloseDrawer() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("New conversation")
            }
            SettingsSearchField(query, { query = it }, "Search conversations", Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(filter == HistoryFilter.ALL, { filter = HistoryFilter.ALL }, label = { Text("All") })
                FilterChip(filter == HistoryFilter.PINNED, { filter = HistoryFilter.PINNED }, label = { Text("Pinned") })
                Box {
                    FilterChip(filter !in listOf(HistoryFilter.ALL, HistoryFilter.PINNED), { sourcesMenu = true },
                        label = { Text(if (filter in listOf(HistoryFilter.ALL, HistoryFilter.PINNED)) "Sources" else filter.label) }, trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) })
                    DropdownMenu(sourcesMenu, { sourcesMenu = false }) {
                        HistoryFilter.entries.filter { it !in listOf(HistoryFilter.ALL, HistoryFilter.PINNED) }.forEach { source ->
                            DropdownMenuItem(text = { Text(source.label) }, onClick = { filter = source; sourcesMenu = false })
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selection) TextButton(onClick = {
                selectedIds = if (allSelected) selectedIds - visibleIds.toSet() else (selectedIds + visibleIds).distinct()
            }, enabled = visibleIds.isNotEmpty()) { Text(if (allSelected) "Deselect visible" else "Select visible") }
            else Box {
                TextButton(onClick = { sortMenu = true }) { Text(sort.label); Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp)) }
                DropdownMenu(sortMenu, { sortMenu = false }) { HistorySort.entries.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice.label) }, onClick = { sort = choice; sortMenu = false })
                } }
            }
            Spacer(Modifier.weight(1f))
            Text("${visible.size} visible", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!selection) Box {
                OmniIconButton(onClick = { menu = true }, enabled = sessions.isNotEmpty()) { Icon(Icons.Default.MoreVert, "Manage conversations") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Select conversations") }, onClick = { selection = true; selectedIds = emptyList(); menu = false })
                    DropdownMenuItem(text = { Text("Delete all", color = MaterialTheme.colorScheme.error) }, onClick = { deleteGroup = "all"; menu = false })
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (visible.isEmpty()) item {
                SettingsEmptyState(if (sessions.isEmpty()) "Your conversations start here" else "No matching chats",
                    if (sessions.isEmpty()) "Create a conversation to get started." else "Try another search or source.")
                if (sessions.isNotEmpty()) TextButton(onClick = { query = ""; filter = HistoryFilter.ALL }) { Text("Reset filters") }
            }
            sections.forEach { (label, rows) ->
                if (rows.isNotEmpty()) item(key = "section:$label") {
                    Text(label, Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(rows, key = { it.id }, contentType = { "conversation" }) { session ->
                    HistorySessionItem(session, session.id == currentSessionId, selection, session.id in selectedIds,
                        onClick = { if (selection) toggle(session.id) else { onSessionClick(session.id); onCloseDrawer() } },
                        onLongClick = { selection = true; if (session.id !in selectedIds) selectedIds = selectedIds + session.id },
                        onPin = { onTogglePin(session.id) }, onRename = { renameText = session.title; renameId = session.id },
                        onDelete = { deleteId = session.id }, modifier = if (motion.reduced || motion.compact) Modifier else Modifier.animateItem(
                            fadeInSpec = null, fadeOutSpec = null, placementSpec = tween(motion.responseMillis, easing = OmniEasing)))
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onSettings, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Settings") }
            TextButton(onClick = onBrowser, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Language, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Browser") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistorySessionItem(session: ChatSessionEntity, active: Boolean, selection: Boolean, checked: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onPin: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(if (checked) MaterialTheme.colorScheme.primaryContainer else if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
        .semantics { selected = if (selection) checked else active }
        .combinedClickable(onClick = onClick, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() })
        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selection) Checkbox(checked, null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(session.title.ifBlank { "Untitled conversation" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            Text("${sessionSourceLabel(session)} · ${relativeSessionTime(session.lastUpdated)}", maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!selection) Box {
            OmniIconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Actions for ${session.title}") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(if (session.isPinned) "Unpin" else "Pin") }, onClick = { menu = false; onPin() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

private fun sessionSourceLabel(session: ChatSessionEntity): String = when (session.source) {
    ChatSessionEntity.SOURCE_TELEGRAM -> "Telegram"
    ChatSessionEntity.SOURCE_DISCORD -> "Discord"
    ChatSessionEntity.SOURCE_WHATSAPP_BRIDGE -> "WhatsApp"
    ChatSessionEntity.SOURCE_EXTERNAL_APP -> session.sourceAppName.ifBlank {
        session.sourceAppPackage.ifBlank { "Connected app" }
    }
    else -> "App"
}

private fun relativeSessionTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - timestamp).coerceAtLeast(0L)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour

    return when {
        diff < minute -> "Just now"
        diff < hour -> "${diff / minute}m ago"
        diff < day -> "${diff / hour}h ago"
        diff < 2 * day -> "Yesterday"
        diff < 7 * day -> "${diff / day}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun groupHistorySessionsByDate(
    sessions: List<ChatSessionEntity>
): Map<String, List<ChatSessionEntity>> {
    val now = Calendar.getInstance()
    val todayStart = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val yesterdayStart = todayStart - 86_400_000L
    val sevenDaysStart = todayStart - 6 * 86_400_000L

    val grouped = linkedMapOf<String, MutableList<ChatSessionEntity>>()
    sessions.forEach { session ->
        val label = when {
            session.lastUpdated >= todayStart -> "Today"
            session.lastUpdated >= yesterdayStart -> "Yesterday"
            session.lastUpdated >= sevenDaysStart -> "Previous 7 days"
            else -> SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(session.lastUpdated))
        }
        grouped.getOrPut(label) { mutableListOf() }.add(session)
    }
    return grouped
}
