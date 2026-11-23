package com.omnidev.workspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.db.entities.ChatSessionEntity

/**
 * P13 inbox view built on the existing Room sessions. No duplicate message store,
 * implicit cross-channel account linking, or raw credentials are introduced.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniConnectInboxScreen(
    sessions: List<ChatSessionEntity>,
    onOpenSession: (Long) -> Unit,
    onNavigateBack: () -> Unit
) {
    val categories = listOf(
        "All" to null,
        "App" to ChatSessionEntity.SOURCE_APP,
        "Telegram" to ChatSessionEntity.SOURCE_TELEGRAM,
        "WhatsApp" to ChatSessionEntity.SOURCE_WHATSAPP_BRIDGE,
        "Discord" to ChatSessionEntity.SOURCE_DISCORD,
        "Connected apps" to ChatSessionEntity.SOURCE_EXTERNAL_APP
    )
    var selected by remember { mutableStateOf<String?>(null) }
    val filtered = remember(sessions, selected) {
        sessions.filter { selected == null || it.source == selected }
            .sortedWith(compareByDescending<ChatSessionEntity> { it.lastUpdated }.thenByDescending { it.id })
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("OmniConnect Inbox") },
            navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Back") } }
        )
    }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Saved conversations across connected channels. Source badges describe where a session came from; they do not verify a sender's real-world identity.",
                style = MaterialTheme.typography.bodySmall
            )
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories, key = { it.first }) { (title, source) ->
                    FilterChip(selected = selected == source, onClick = { selected = source },
                        label = { Text(title) })
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered, key = { it.id }) { session ->
                    Card(onClick = { onOpenSession(session.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(session.title, style = MaterialTheme.typography.titleSmall)
                            Text(channelName(session.source), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (filtered.isEmpty()) item {
                    Text("No saved conversations for this channel.")
                }
            }
        }
    }
}

private fun channelName(source: String): String = when (source) {
    ChatSessionEntity.SOURCE_APP -> "In-app"
    ChatSessionEntity.SOURCE_TELEGRAM -> "Telegram Bot"
    ChatSessionEntity.SOURCE_WHATSAPP_BRIDGE -> "WhatsApp bridge"
    ChatSessionEntity.SOURCE_DISCORD -> "Discord Bot"
    ChatSessionEntity.SOURCE_EXTERNAL_APP -> "Connected app"
    else -> "Other session"
}
