package com.omnidev.workspace.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.skills.AgentSkill
import com.omnidev.workspace.data.skills.ChatCapabilityStore
import com.omnidev.workspace.data.skills.SkillManager
import com.omnidev.workspace.domain.model.ChatSettings
import com.omnidev.workspace.domain.model.SkillAccessMode
import com.omnidev.workspace.domain.model.ToolAccessMode
import com.omnidev.workspace.ui.components.OmniSearchField
import com.omnidev.workspace.ui.motion.OmniIconButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsSheet(settings: ChatSettings, onDismiss: () -> Unit, onUpdate: (ChatSettings) -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { SkillManager(context) }
    val skills = remember(manager) { manager.listSkills().filter { it.enabled } }
    var policy by remember { mutableStateOf(ChatCapabilityStore.read(context)) }
    var draft by remember(settings) { mutableStateOf(settings) }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        ChatCapabilitiesContent(draft, policy, skills, onDismiss,
            onSettingsChange = { draft = it; onUpdate(it) },
            onPolicyChange = { next -> policy = ChatCapabilityStore.update(context) { next } })
    }
}

/** One list per tab, with immediate changes and the existing persisted capability policy. */
@Composable
internal fun ChatCapabilitiesContent(settings: ChatSettings, policy: ChatCapabilityStore.Snapshot,
    skills: List<AgentSkill>, onDismiss: () -> Unit,
    onSettingsChange: (ChatSettings) -> Unit, onPolicyChange: (ChatCapabilityStore.Snapshot) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val toolsList = rememberLazyListState()
    val skillsList = rememberLazyListState()
    val visibleSkills = remember(skills, query) {
        skills.filter { query.isBlank() || it.name.contains(query.trim(), true) || it.description.contains(query.trim(), true) }
    }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).testTag("chat-capabilities")) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tools & skills", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Choose what Omni can use across your conversations.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OmniIconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close tools and skills") }
        }
        TabRow(tab, containerColor = MaterialTheme.colorScheme.surface, divider = {}) {
            listOf("Tools", "Skills").forEachIndexed { index, title -> Tab(selected = tab == index,
                onClick = { tab = index }, text = { Text(title) }) }
        }
        // Switching tabs gives each list its own saved scroll position.
        key(tab) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = if (tab == 0) toolsList else skillsList, contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tab == 0) {
                    item(key = "tool-policy") {
                        GuidanceChoices("Tool guidance", settings.toolAccessMode.name,
                            listOf("DISABLED" to "Off", "ON_DEMAND" to "On demand", "ALWAYS_AVAILABLE" to "Always loaded"),
                            when (settings.toolAccessMode) {
                                ToolAccessMode.DISABLED -> "Omni replies without using tools."
                                ToolAccessMode.ALWAYS_AVAILABLE -> "Include detailed tool guidance with every request."
                                else -> "Load detailed guidance when needed. Recommended for most conversations."
                            }) { value ->
                            val mode = ToolAccessMode.valueOf(value)
                            onPolicyChange(policy.copy(toolAccessMode = mode))
                            onSettingsChange(settings.copy(toolAccessMode = mode))
                        }
                    }
                    val toolsEnabled = settings.toolAccessMode != ToolAccessMode.DISABLED
                    item(key = "web-search") { CapabilityToggle("Web search", "Find information on the web", Icons.Default.Search,
                        settings.webSearchEnabled, toolsEnabled) { enabled -> onSettingsChange(settings.copy(webSearchEnabled = enabled,
                            deepResearchEnabled = enabled && settings.deepResearchEnabled)) } }
                    item(key = "read-pages") { CapabilityToggle("Read webpages", "Open links and read their contents", Icons.Default.Language,
                        settings.fetchPageEnabled, toolsEnabled) { onSettingsChange(settings.copy(fetchPageEnabled = it)) } }
                    item(key = "research") { CapabilityToggle("Deep research",
                        if (settings.webSearchEnabled) "Explore a question across several sources" else "Turn on Web search to use this",
                        Icons.Default.Psychology, settings.deepResearchEnabled && settings.webSearchEnabled,
                        toolsEnabled && settings.webSearchEnabled) { onSettingsChange(settings.copy(deepResearchEnabled = it)) } }
                    item(key = "browser") { CapabilityToggle("Browser automation", "Interact with pages that need more than reading",
                        Icons.Default.Visibility, policy.headlessBrowserEnabled, toolsEnabled) {
                        onPolicyChange(policy.copy(headlessBrowserEnabled = it))
                    } }
                } else {
                    item(key = "skill-policy") {
                        GuidanceChoices("Skill instructions", policy.skillAccessMode.name,
                            listOf("DISABLED" to "Off", "ON_DEMAND" to "On demand", "ALWAYS_LOADED" to "Always loaded"),
                            when (policy.skillAccessMode) {
                                SkillAccessMode.DISABLED -> "Omni will not use agent skills."
                                SkillAccessMode.ON_DEMAND -> "Read the instructions for a matching skill when needed."
                                SkillAccessMode.ALWAYS_LOADED -> "Load selected skill instructions before responding. Uses more context."
                            }) { onPolicyChange(policy.copy(skillAccessMode = SkillAccessMode.valueOf(it))) }
                    }
                    if (policy.skillAccessMode != SkillAccessMode.DISABLED) {
                        item(key = "all-skills") { CapabilityToggle("All enabled skills", "${skills.size} installed and enabled", Icons.Default.SmartToy,
                            policy.useAllEnabledSkills) { useAll -> onPolicyChange(policy.copy(useAllEnabledSkills = useAll,
                            selectedSkillNames = if (useAll) emptySet() else policy.selectedSkillNames)) } }
                        if (!policy.useAllEnabledSkills) {
                            item(key = "skill-search") { OmniSearchField(query, { query = it }, "Search skills") }
                            if (visibleSkills.isEmpty()) item(key = "empty-skills") {
                                Text(if (skills.isEmpty()) "No enabled skills yet. Add them in Settings → Agent Skills."
                                    else "No skills match your search.", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            items(visibleSkills, key = { it.name }) { skill ->
                                val name = skill.name.trim().lowercase()
                                val checked = name in policy.selectedSkillNames
                                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) { enabled ->
                                        onPolicyChange(policy.copy(selectedSkillNames = if (enabled) policy.selectedSkillNames + name else policy.selectedSkillNames - name))
                                    }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(skill.name, style = MaterialTheme.typography.titleSmall)
                                            Text(skill.description, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                        Checkbox(checked, onCheckedChange = null)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuidanceChoices(title: String, selected: String, choices: List<Pair<String, String>>,
    description: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (value, label) ->
                Surface(shape = RoundedCornerShape(16.dp), color = if (value == selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp,
                    if (value == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    Text(label, Modifier.selectable(selected == value, role = Role.RadioButton, onClick = { onSelect(value) })
                        .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 14.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CapabilityToggle(title: String, description: String, icon: ImageVector,
    checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .4f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .5f))
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked, onCheckedChange = null, enabled = enabled)
        }
    }
}
