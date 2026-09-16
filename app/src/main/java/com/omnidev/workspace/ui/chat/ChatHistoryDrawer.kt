package com.omnidev.workspace.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class HistoryFilter(val label: String) {
    ALL("All"),
    PINNED("Pinned"),
    APP("App"),
    TELEGRAM("Telegram"),
    DISCORD("Discord"),
    WHATSAPP("WhatsApp")
}

private enum class HistorySort(val label: String) {
    RECENT("Recently updated"),
    OLDEST("Oldest updated"),
    TITLE("Title A–Z")
}

/**
 * Rich conversation manager used by the chat navigation drawer.
 *
 * The drawer intentionally keeps filtering and selection as ephemeral UI state while all
 * persisted session mutations remain owned by [ChatViewModel]. This avoids creating a second
 * source of truth for chat history while still making large histories easy to navigate.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatHistoryDrawer(
    sessions: List<ChatSessionEntity>,
    currentSessionId: Long?,
    onNewSession: () -> Unit,
    onSessionClick: (Long) -> Unit,
    onTogglePin: (Long) -> Unit,
    onRenameSession: (Long, String) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onDeleteAllSessions: () -> Unit,
    onDeleteSelectedSessions: (Set<Long>) -> Unit,
    onCloseDrawer: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(HistoryFilter.ALL) }
    var activeSort by remember { mutableStateOf(HistorySort.RECENT) }
    var showSortMenu by remember { mutableStateOf(false) }

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }

    var renameTarget by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    val searched = sessions.filter { session ->
        searchQuery.isBlank() ||
            session.title.contains(searchQuery, ignoreCase = true) ||
            sessionSourceLabel(session).contains(searchQuery, ignoreCase = true)
    }
    val filtered = searched.filter { session ->
        when (activeFilter) {
            HistoryFilter.ALL -> true
            HistoryFilter.PINNED -> session.isPinned
            HistoryFilter.APP -> session.source == ChatSessionEntity.SOURCE_APP
            HistoryFilter.TELEGRAM -> session.source == ChatSessionEntity.SOURCE_TELEGRAM
            HistoryFilter.DISCORD -> session.source == ChatSessionEntity.SOURCE_DISCORD
            HistoryFilter.WHATSAPP -> session.source == ChatSessionEntity.SOURCE_WHATSAPP_BRIDGE
        }
    }
    val visibleSessions = when (activeSort) {
        HistorySort.RECENT -> filtered.sortedByDescending { it.lastUpdated }
        HistorySort.OLDEST -> filtered.sortedBy { it.lastUpdated }
        HistorySort.TITLE -> filtered.sortedBy { it.title.lowercase(Locale.getDefault()) }
    }
    val visibleIds = visibleSessions.mapTo(linkedSetOf()) { it.id }
    val allVisibleSelected = visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)

    val pinnedCount = sessions.count { it.isPinned }
    val linkedCount = sessions.count { it.source != ChatSessionEntity.SOURCE_APP }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename conversation") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Conversation title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        onRenameSession(target.id, renameText.trim())
                        renameTarget = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete conversation?") },
            text = {
                Text("\"${target.title}\" and all messages in it will be permanently deleted.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSession(target.id)
                        selectedIds = selectedIds - target.id
                        deleteTarget = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Delete all conversations?") },
            text = {
                Text(
                    "This permanently deletes all ${sessions.size} saved conversations and their messages. " +
                        "This action cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAllSessions()
                        showDeleteAllDialog = false
                        isSelectionMode = false
                        selectedIds = emptySet()
                    }
                ) {
                    Text("Delete all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text("Delete selected conversations?") },
            text = {
                Text("${selectedIds.size} selected conversation(s) and their messages will be permanently deleted.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSelectedSessions(selectedIds)
                        showDeleteSelectedDialog = false
                        isSelectionMode = false
                        selectedIds = emptySet()
                    }
                ) {
                    Text("Delete selected", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        HistoryHeader(
            sessionCount = sessions.size,
            selectedCount = selectedIds.size,
            isSelectionMode = isSelectionMode,
            canDeleteSelected = selectedIds.isNotEmpty(),
            hasSessions = sessions.isNotEmpty(),
            onDeleteSelected = { showDeleteSelectedDialog = true },
            onDeleteAll = { showDeleteAllDialog = true },
            onCancelSelection = {
                isSelectionMode = false
                selectedIds = emptySet()
            },
            onCloseDrawer = onCloseDrawer
        )

        Button(
            onClick = {
                onNewSession()
                onCloseDrawer()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("New conversation", fontWeight = FontWeight.SemiBold)
        }

        if (sessions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HistoryStatCard(
                    value = sessions.size.toString(),
                    label = "Total",
                    modifier = Modifier.weight(1f)
                )
                HistoryStatCard(
                    value = pinnedCount.toString(),
                    label = "Pinned",
                    modifier = Modifier.weight(1f)
                )
                HistoryStatCard(
                    value = linkedCount.toString(),
                    label = "Linked",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search conversations or sources") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HistoryFilter.entries.forEach { filter ->
                InputChip(
                    selected = activeFilter == filter,
                    onClick = { activeFilter = filter },
                    label = { Text(filter.label) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                TextButton(onClick = { showSortMenu = true }) {
                    Text("Sort: ${activeSort.label}")
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    HistorySort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (activeSort == sort) "✓ ${sort.label}" else sort.label
                                )
                            },
                            onClick = {
                                activeSort = sort
                                showSortMenu = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (sessions.isNotEmpty()) {
                TextButton(
                    onClick = {
                        if (!isSelectionMode) {
                            isSelectionMode = true
                            selectedIds = emptySet()
                        } else {
                            selectedIds = if (allVisibleSelected) {
                                selectedIds - visibleIds
                            } else {
                                selectedIds + visibleIds
                            }
                        }
                    }
                ) {
                    Text(
                        when {
                            !isSelectionMode -> "Select"
                            allVisibleSelected -> "Deselect visible"
                            else -> "Select visible"
                        }
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 2.dp))

        if (visibleSessions.isEmpty()) {
            HistoryEmptyState(
                hasAnySessions = sessions.isNotEmpty(),
                searchQuery = searchQuery,
                activeFilter = activeFilter,
                onReset = {
                    searchQuery = ""
                    activeFilter = HistoryFilter.ALL
                    activeSort = HistorySort.RECENT
                }
            )
        } else {
            val pinned = visibleSessions.filter { it.isPinned }
            val regular = visibleSessions.filterNot { it.isPinned }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 6.dp,
                    bottom = 20.dp
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (pinned.isNotEmpty()) {
                    item(key = "section_pinned") {
                        HistorySectionHeader("Pinned", pinned.size)
                    }
                    items(pinned, key = { "pinned_${it.id}" }) { session ->
                        HistorySessionItem(
                            session = session,
                            isActive = session.id == currentSessionId,
                            isSelectionMode = isSelectionMode,
                            isSelected = session.id in selectedIds,
                            onClick = {
                                if (isSelectionMode) {
                                    selectedIds = toggleSelection(selectedIds, session.id)
                                } else {
                                    onSessionClick(session.id)
                                    onCloseDrawer()
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                    selectedIds = setOf(session.id)
                                }
                            },
                            onPin = { onTogglePin(session.id) },
                            onRename = {
                                renameText = session.title
                                renameTarget = session
                            },
                            onDelete = { deleteTarget = session }
                        )
                    }
                    if (regular.isNotEmpty()) {
                        item(key = "pinned_divider") {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }

                val grouped = if (activeSort == HistorySort.RECENT) {
                    groupHistorySessionsByDate(regular)
                } else {
                    linkedMapOf("Conversations" to regular)
                }

                grouped.forEach { (label, itemsInSection) ->
                    if (itemsInSection.isNotEmpty()) {
                        item(key = "section_$label") {
                            HistorySectionHeader(label, itemsInSection.size)
                        }
                        items(itemsInSection, key = { "session_${it.id}" }) { session ->
                            HistorySessionItem(
                                session = session,
                                isActive = session.id == currentSessionId,
                                isSelectionMode = isSelectionMode,
                                isSelected = session.id in selectedIds,
                                onClick = {
                                    if (isSelectionMode) {
                                        selectedIds = toggleSelection(selectedIds, session.id)
                                    } else {
                                        onSessionClick(session.id)
                                        onCloseDrawer()
                                    }
                                },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        isSelectionMode = true
                                        selectedIds = setOf(session.id)
                                    }
                                },
                                onPin = { onTogglePin(session.id) },
                                onRename = {
                                    renameText = session.title
                                    renameTarget = session
                                },
                                onDelete = { deleteTarget = session }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryHeader(
    sessionCount: Int,
    selectedCount: Int,
    isSelectionMode: Boolean,
    canDeleteSelected: Boolean,
    hasSessions: Boolean,
    onDeleteSelected: () -> Unit,
    onDeleteAll: () -> Unit,
    onCancelSelection: () -> Unit,
    onCloseDrawer: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isSelectionMode) "$selectedCount selected" else "Chat history",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isSelectionMode) {
                    "Choose conversations to manage"
                } else {
                    "$sessionCount saved conversation${if (sessionCount == 1) "" else "s"}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isSelectionMode) {
            IconButton(onClick = onDeleteSelected, enabled = canDeleteSelected) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete selected conversations",
                    tint = if (canDeleteSelected) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
            IconButton(onClick = onCancelSelection) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
            }
        } else {
            if (hasSessions) {
                IconButton(onClick = onDeleteAll) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = "Delete all conversations",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            IconButton(onClick = onCloseDrawer) {
                Icon(Icons.Filled.Close, contentDescription = "Close chat history")
            }
        }
    }
}

@Composable
private fun HistoryStatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistorySectionHeader(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistorySessionItem(
    session: ChatSessionEntity,
    isActive: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    when {
                        isSelected -> MaterialTheme.colorScheme.primaryContainer
                        isActive -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surface
                    }
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                )
                .padding(start = 10.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(sessionSourceIcon(session), fontSize = 17.sp)
                    }
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.title.ifBlank { "Untitled conversation" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    if (isActive) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = "Current",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append(sessionSourceLabel(session))
                        append(" • ")
                        append(relativeSessionTime(session.lastUpdated))
                        if (session.isPinned) append(" • Pinned")
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isSelectionMode) {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Conversation actions",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            DropdownMenuItem(
                text = { Text(if (session.isPinned) "Unpin" else "Pin to top") },
                onClick = {
                    onPin()
                    showMenu = false
                }
            )
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    onRename()
                    showMenu = false
                }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    onDelete()
                    showMenu = false
                }
            )
        }
    }
}

@Composable
private fun HistoryEmptyState(
    hasAnySessions: Boolean,
    searchQuery: String,
    activeFilter: HistoryFilter,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (hasAnySessions) Icons.Filled.Search else Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (hasAnySessions) "No conversations found" else "No conversations yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                !hasAnySessions -> "Start a new conversation and it will appear here."
                searchQuery.isNotBlank() -> "Try another search term or clear the current filters."
                activeFilter != HistoryFilter.ALL -> "There are no conversations in this filter yet."
                else -> "Try changing your history filters."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (hasAnySessions) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onReset) { Text("Reset filters") }
        }
    }
}

private fun toggleSelection(selectedIds: Set<Long>, id: Long): Set<Long> =
    if (id in selectedIds) selectedIds - id else selectedIds + id

private fun sessionSourceLabel(session: ChatSessionEntity): String = when (session.source) {
    ChatSessionEntity.SOURCE_TELEGRAM -> "Telegram"
    ChatSessionEntity.SOURCE_DISCORD -> "Discord"
    ChatSessionEntity.SOURCE_WHATSAPP_BRIDGE -> "WhatsApp"
    else -> "App"
}

private fun sessionSourceIcon(session: ChatSessionEntity): String = when (session.source) {
    ChatSessionEntity.SOURCE_TELEGRAM -> "✈️"
    ChatSessionEntity.SOURCE_DISCORD -> "🎮"
    ChatSessionEntity.SOURCE_WHATSAPP_BRIDGE -> "📱"
    else -> "💬"
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
