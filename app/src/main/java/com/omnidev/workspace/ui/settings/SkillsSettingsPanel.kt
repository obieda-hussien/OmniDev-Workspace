package com.omnidev.workspace.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.skills.AgentSkill
import com.omnidev.workspace.data.skills.SkillManager
import com.omnidev.workspace.data.skills.SkillOrigin
import com.omnidev.workspace.ui.components.SettingsDisclosure
import com.omnidev.workspace.ui.components.SettingsEmptyState
import com.omnidev.workspace.ui.components.SettingsSearchField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings surface for bundled + user-imported/agent-authored Agent Skills. */
@Composable
fun SkillsSettingsPanel(
    onCreateWithOmni: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val manager = remember(context) { SkillManager(context) }
    val scope = rememberCoroutineScope()
    var skills by remember { mutableStateOf(emptyList<AgentSkill>()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf("All") }
    var pendingDelete by remember { mutableStateOf<AgentSkill?>(null) }
    val filtered = remember(skills, query, selectedFilter) {
        skills.filter { skill ->
            (query.isBlank() || skill.name.contains(query.trim(), true) || skill.description.contains(query.trim(), true)) &&
                when (selectedFilter) {
                    "Enabled" -> skill.enabled
                    "Your skills" -> skill.origin == SkillOrigin.USER
                    "Built-in" -> skill.origin == SkillOrigin.BUILTIN
                    else -> true
                }
        }
    }

    fun refresh() {
        scope.launch {
            try {
                skills = withContext(Dispatchers.IO) { manager.listSkills() }
                loadError = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { loadError = "Could not load the skill library." }
            finally { loading = false }
        }
    }
    LaunchedEffect(manager) { refresh() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { manager.importSkill(uri) }
                status = result.fold(
                    onSuccess = { "Imported ${it.name} — enabled and ready for Omni." },
                    onFailure = { "Import failed: ${it.message ?: "invalid SKILL.md"}" }
                )
                refresh()
            }
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "summary") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading) CircularProgressIndicator()
                else if (loadError != null) {
                    Text(loadError!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { refresh() }) { Text("Retry") }
                } else Text("${skills.count { it.enabled }} enabled · ${skills.size} installed",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Reusable instructions Omni can load when a task needs them.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { showCreateDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Create with Omni") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/markdown", "text/plain", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth()) { Text("Import SKILL.md") }
                SettingsSearchField(query, { query = it }, "Search skills")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("All", "Enabled", "Your skills", "Built-in")) { filter ->
                        FilterChip(selected = selectedFilter == filter, onClick = { selectedFilter = filter }, label = { Text(filter) })
                    }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("Import failed") || it.startsWith("Could not")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            }
        }
        items(filtered, key = { "${it.origin}:${it.name}" }) { skill ->
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                SkillRow(
                    skill = skill,
                    onEnabledChanged = { enabled ->
                        manager.setEnabled(skill.name, enabled)
                        status = if (enabled) "Enabled ${skill.name}." else "Disabled ${skill.name}."
                        refresh()
                    },
                    onDelete = if (skill.origin == SkillOrigin.USER) ({ pendingDelete = skill }) else null
                )
            }
        }
        if (!loading && loadError == null && filtered.isEmpty()) {
            item(key = "empty") {
                SettingsEmptyState(if (skills.isEmpty()) "No skills installed" else "No matching skills",
                    if (skills.isEmpty()) "Import a skill or create one with Omni." else "Try another search or choose All.")
            }
        }
        item(key = "help") {
            var expanded by remember { mutableStateOf(false) }
            SettingsDisclosure("How skills work", "Availability and ownership", expanded, { expanded = !expanded }) {
                Text("Enabled skills are available to Agent and Swarm runs and loaded on demand. Built-in skills are read-only. Your imported or Omni-created skills can be enabled, disabled or deleted.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    pendingDelete?.let { skill ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Delete ${skill.name}?") },
            text = { Text("This removes the installed skill. Import it again to restore it.") },
            confirmButton = { TextButton(onClick = {
                pendingDelete = null
                scope.launch {
                    val deleted = withContext(Dispatchers.IO) { manager.deleteUserSkill(skill.name) }
                    status = if (deleted) "Deleted ${skill.name}" else "Could not delete ${skill.name}"
                    refresh()
                }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } })
    }

    if (showCreateDialog) {
        CreateSkillDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { goal ->
                showCreateDialog = false
                onCreateWithOmni(SkillManager.creationPrompt(goal))
            }
        )
    }
}

@Composable
private fun SkillRow(
    skill: AgentSkill,
    onEnabledChanged: (Boolean) -> Unit,
    onDelete: (() -> Unit)?
) {
    var expanded by rememberSaveable(skill.name) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Column {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (skill.origin == SkillOrigin.BUILTIN) "Built-in" else "Your skill",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = skill.description,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Less" else "Details") }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = skill.enabled, onCheckedChange = onEnabledChanged, modifier = Modifier.semantics { contentDescription = "Enable ${skill.name}" })
    }
}

@Composable
private fun CreateSkillDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var goal by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create a skill with Omni") },
        text = {
            Column {
                Text(
                    "Describe the reusable capability or workflow you want. Omni will draft a complete SKILL.md, validate it, install it into the same registry as imported skills, enable it, and verify that it can be invoked by name."
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 7,
                    label = { Text("What should this skill do?") },
                    placeholder = { Text("Example: diagnose Gradle/KSP build failures and verify the fix across all flavors") }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = goal.isNotBlank(),
                onClick = { onCreate(goal.trim()) }
            ) { Text("Send to Omni") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
