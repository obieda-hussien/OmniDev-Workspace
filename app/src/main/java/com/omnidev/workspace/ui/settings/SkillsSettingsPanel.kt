package com.omnidev.workspace.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.skills.AgentSkill
import com.omnidev.workspace.data.skills.SkillManager
import com.omnidev.workspace.data.skills.SkillOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings surface for bundled + user-imported Agent Skills. */
@Composable
fun SkillsSettingsPanel(
    onCreateWithOmni: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val manager = remember(context) { SkillManager(context) }
    val scope = rememberCoroutineScope()
    var skills by remember { mutableStateOf(manager.listSkills()) }
    var status by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    fun refresh() {
        skills = manager.listSkills()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { manager.importSkill(uri) }
                status = result.fold(
                    onSuccess = { "Imported ${it.name}" },
                    onFailure = { "Import failed: ${it.message ?: "invalid SKILL.md"}" }
                )
                refresh()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Agent Skills",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Bundled OmniDev operating skills plus your own SKILL.md files. Enabled skills are injected into Agent and Swarm context.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { importLauncher.launch(arrayOf("text/markdown", "text/plain", "application/octet-stream")) }
            ) {
                Text("Import SKILL.md")
            }
            Button(
                modifier = Modifier.weight(1f),
                onClick = { showCreateDialog = true }
            ) {
                Text("Create with Omni")
            }
        }

        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Import failed")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column {
                skills.forEachIndexed { index, skill ->
                    SkillRow(
                        skill = skill,
                        onEnabledChanged = { enabled ->
                            manager.setEnabled(skill.name, enabled)
                            refresh()
                        },
                        onDelete = if (skill.origin == SkillOrigin.USER) {
                            {
                                scope.launch {
                                    val deleted = withContext(Dispatchers.IO) {
                                        manager.deleteUserSkill(skill.name)
                                    }
                                    status = if (deleted) "Deleted ${skill.name}" else "Could not delete ${skill.name}"
                                    refresh()
                                }
                            }
                        } else null
                    )
                    if (index < skills.lastIndex) HorizontalDivider()
                }
                if (skills.isEmpty()) {
                    Text(
                        text = "No skills found.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text(
            text = "Built-in skills are read-only. Imported skills can be disabled or deleted. A user skill cannot replace a built-in skill with the same name.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = skill.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (skill.origin == SkillOrigin.BUILTIN) "BUILT-IN" else "USER",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = skill.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = skill.enabled, onCheckedChange = onEnabledChanged)
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
                    "Describe the reusable capability or workflow you want. Omni will create a standards-compliant SKILL.md in the active Target Context."
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
