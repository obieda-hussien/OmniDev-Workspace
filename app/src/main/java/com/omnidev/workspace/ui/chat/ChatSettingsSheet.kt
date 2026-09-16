package com.omnidev.workspace.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.skills.ChatCapabilityStore
import com.omnidev.workspace.data.skills.SkillManager
import com.omnidev.workspace.domain.model.ChatSettings
import com.omnidev.workspace.domain.model.SkillAccessMode
import com.omnidev.workspace.domain.model.ToolAccessMode

/**
 * Per-chat capability picker opened from the composer `+` button.
 *
 * This surface is intentionally a single scrollable sheet. It controls both what
 * native tools are exposed and how Agent Skills enter model context.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsSheet(
    settings: ChatSettings,
    onDismiss: () -> Unit,
    onUpdate: (ChatSettings) -> Unit
) {
    val context = LocalContext.current
    val skillManager = remember(context) { SkillManager(context) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var policy by remember { mutableStateOf(ChatCapabilityStore.read(context)) }
    val installedSkills = remember(policy, settings) {
        skillManager.listSkills().filter { it.enabled }
    }

    fun updatePolicy(transform: (ChatCapabilityStore.Snapshot) -> ChatCapabilityStore.Snapshot) {
        policy = ChatCapabilityStore.update(context, transform)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item {
                SheetHeader(onDismiss)
                Text(
                    text = "Choose what Omni can use in this chat. These controls affect capability exposure, context loading, and skill invocation.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SectionLabel("Tools")
            }

            ToolAccessMode.entries.filter { it != ToolAccessMode.AUTO }.forEach { mode ->
                item(key = "tool-mode-${mode.name}") {
                    ChoiceRow(
                        title = mode.label,
                        subtitle = mode.subtitle,
                        selected = settings.toolAccessMode == mode,
                        icon = Icons.Filled.Build,
                        onClick = {
                            updatePolicy { it.copy(toolAccessMode = mode) }
                            onUpdate(settings.copy(toolAccessMode = mode))
                        }
                    )
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionLabel("Web & browser")
                CapabilityToggleRow(
                    icon = Icons.Filled.Search,
                    label = "Web search",
                    subtitle = "Allow normal web search tools",
                    checked = settings.webSearchEnabled,
                    enabled = settings.toolAccessMode != ToolAccessMode.DISABLED,
                    onCheckedChange = { enabled ->
                        onUpdate(
                            settings.copy(
                                webSearchEnabled = enabled,
                                deepResearchEnabled = if (enabled) settings.deepResearchEnabled else false
                            )
                        )
                    }
                )
                CapabilityToggleRow(
                    icon = Icons.Filled.Psychology,
                    label = "Deep research",
                    subtitle = "Allow deeper multi-source search",
                    checked = settings.deepResearchEnabled && settings.webSearchEnabled,
                    enabled = settings.toolAccessMode != ToolAccessMode.DISABLED && settings.webSearchEnabled,
                    onCheckedChange = { onUpdate(settings.copy(deepResearchEnabled = it)) }
                )
                CapabilityToggleRow(
                    icon = Icons.Filled.Language,
                    label = "Fetch & read pages",
                    subtitle = "Allow fetching pages and extracting readable content",
                    checked = settings.fetchPageEnabled,
                    enabled = settings.toolAccessMode != ToolAccessMode.DISABLED,
                    onCheckedChange = { onUpdate(settings.copy(fetchPageEnabled = it)) }
                )
                CapabilityToggleRow(
                    icon = Icons.Filled.Visibility,
                    label = "Headless browser",
                    subtitle = "Allow dynamic JS navigation and browser automation",
                    checked = policy.headlessBrowserEnabled,
                    enabled = settings.toolAccessMode != ToolAccessMode.DISABLED,
                    onCheckedChange = { value ->
                        updatePolicy { it.copy(headlessBrowserEnabled = value) }
                    }
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionLabel("Agent Skills")
            }

            SkillAccessMode.entries.forEach { mode ->
                item(key = "skill-mode-${mode.name}") {
                    ChoiceRow(
                        title = mode.label,
                        subtitle = mode.subtitle,
                        selected = policy.skillAccessMode == mode,
                        icon = Icons.Filled.SmartToy,
                        onClick = { updatePolicy { it.copy(skillAccessMode = mode) } }
                    )
                }
            }

            item {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                CapabilityToggleRow(
                    icon = Icons.Filled.SmartToy,
                    label = "All enabled skills",
                    subtitle = if (policy.useAllEnabledSkills) {
                        "Every globally enabled skill is eligible for this chat"
                    } else {
                        "Only checked skills below are eligible"
                    },
                    checked = policy.useAllEnabledSkills,
                    enabled = policy.skillAccessMode != SkillAccessMode.DISABLED,
                    onCheckedChange = { useAll ->
                        updatePolicy { current ->
                            current.copy(
                                useAllEnabledSkills = useAll,
                                selectedSkillNames = if (useAll) emptySet() else current.selectedSkillNames
                            )
                        }
                    }
                )
            }

            if (!policy.useAllEnabledSkills && policy.skillAccessMode != SkillAccessMode.DISABLED) {
                items(installedSkills, key = { "skill-${it.name}" }) { skill ->
                    val checked = skill.name in policy.selectedSkillNames
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                updatePolicy { current ->
                                    val next = current.selectedSkillNames.toMutableSet()
                                    if (checked) next.remove(skill.name) else next.add(skill.name)
                                    current.copy(selectedSkillNames = next)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { value ->
                                updatePolicy { current ->
                                    val next = current.selectedSkillNames.toMutableSet()
                                    if (value) next.add(skill.name) else next.remove(skill.name)
                                    current.copy(selectedSkillNames = next)
                                }
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(skill.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                skill.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                }
            }

            item {
                if (!policy.useAllEnabledSkills && installedSkills.isEmpty()) {
                    Text(
                        "No enabled skills are installed. Manage them from Settings → Agent Skills.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun SheetHeader(onDismiss: () -> Unit) {
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
            Icon(Icons.Filled.Close, contentDescription = "Close")
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (selected) Icons.Filled.Check else icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CapabilityToggleRow(
    icon: ImageVector,
    label: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f)
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
