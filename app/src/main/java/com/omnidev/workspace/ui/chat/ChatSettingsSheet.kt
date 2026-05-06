package com.omnidev.workspace.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.domain.model.ChatSettings
import com.omnidev.workspace.domain.model.ToolAccessMode

/**
 * Claude-style "Add to chat" bottom sheet.
 *
 * Shows:
 * - A toggle row for **Web search**
 * - A toggle row for **Deep research**
 * - A toggle row for **Fetch & read page**
 * - A navigation row for **Tool access** that opens [ToolAccessSheet].
 *
 * @param settings   The current [ChatSettings].
 * @param onDismiss  Called when the user taps the ✕ or swipes down.
 * @param onUpdate   Called every time the user changes a setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsSheet(
    settings: ChatSettings,
    onDismiss: () -> Unit,
    onUpdate: (ChatSettings) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showToolAccess by remember { mutableStateOf(false) }

    AnimatedContent(targetState = showToolAccess, label = "chat_settings_nav") { inToolAccess ->
        if (inToolAccess) {
            ToolAccessSheet(
                current = settings.toolAccessMode,
                onBack = { showToolAccess = false },
                onSelect = { mode ->
                    onUpdate(settings.copy(toolAccessMode = mode))
                    showToolAccess = false
                }
            )
        } else {
            ModalBottomSheet(
                onDismissRequest = onDismiss,
                sheetState = sheetState
            ) {
                AddToChatContent(
                    settings = settings,
                    onDismiss = onDismiss,
                    onToggleWebSearch = { onUpdate(settings.copy(webSearchEnabled = it)) },
                    onToggleDeepResearch = { onUpdate(settings.copy(deepResearchEnabled = it)) },
                    onToggleFetchPage = { onUpdate(settings.copy(fetchPageEnabled = it)) },
                    onOpenToolAccess = { showToolAccess = true }
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
//  "Add to chat" main content
// ──────────────────────────────────────────────

@Composable
private fun AddToChatContent(
    settings: ChatSettings,
    onDismiss: () -> Unit,
    onToggleWebSearch: (Boolean) -> Unit,
    onToggleDeepResearch: (Boolean) -> Unit,
    onToggleFetchPage: (Boolean) -> Unit,
    onOpenToolAccess: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // ── Header ──────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Add to chat",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close"
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── Toggles ─────────────────────────────
        ToolToggleRow(
            icon = Icons.Filled.Search,
            label = "Web search",
            checked = settings.webSearchEnabled,
            onCheckedChange = onToggleWebSearch
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        ToolToggleRow(
            icon = Icons.Filled.Psychology,
            label = "Deep research",
            checked = settings.deepResearchEnabled && settings.webSearchEnabled,
            enabled = settings.webSearchEnabled,
            onCheckedChange = { enabled ->
                // Deep research can only be enabled when web search is also active.
                onToggleDeepResearch(enabled && settings.webSearchEnabled)
            }
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        ToolToggleRow(
            icon = Icons.Filled.Language,
            label = "Fetch & read page",
            checked = settings.fetchPageEnabled,
            onCheckedChange = onToggleFetchPage
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── Tool access navigation row ───────────
        ToolNavRow(
            icon = Icons.Filled.Build,
            label = "Tool access",
            value = settings.toolAccessMode.label,
            onClick = onOpenToolAccess
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ──────────────────────────────────────────────
//  Tool access sub-screen
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolAccessSheet(
    current: ToolAccessMode,
    onBack: () -> Unit,
    onSelect: (ToolAccessMode) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onBack,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
                Text(
                    text = "Tool access",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            ToolAccessMode.entries.forEach { mode ->
                ToolAccessModeRow(
                    mode = mode,
                    selected = mode == current,
                    onClick = { onSelect(mode) }
                )
                if (mode != ToolAccessMode.entries.last()) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ──────────────────────────────────────────────
//  Row composables
// ──────────────────────────────────────────────

@Composable
private fun ToolToggleRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.size(width = 48.dp, height = 28.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled)
                MaterialTheme.colorScheme.onSurface
            else
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled)
                MaterialTheme.colorScheme.onSurfaceVariant
            else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun ToolNavRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun ToolAccessModeRow(
    mode: ToolAccessMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Checkmark column — fixed width keeps text aligned regardless of visibility
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = if (selected) "Selected" else null,
            tint = if (selected) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.surface, // invisible when not selected
            modifier = Modifier
                .size(22.dp)
                .padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = mode.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = mode.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
