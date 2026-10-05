package com.omnidev.workspace.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.ui.components.OmniSearchField
import com.omnidev.workspace.ui.components.SettingsEmptyState
import com.omnidev.workspace.ui.motion.OmniIconButton
import com.omnidev.workspace.ui.providers.ProviderModelCatalog

private fun roleTitle(role: ModelRole) = when (role) {
    ModelRole.CHAT -> "Chat"
    ModelRole.AGENT -> "Agent"
    ModelRole.SWARM_ORCHESTRATOR -> "Coordinator"
    ModelRole.SWARM_WORKER -> "Worker"
}
private fun roleHelp(role: ModelRole) = when (role) {
    ModelRole.CHAT -> "Questions, ideas and everyday conversations"
    ModelRole.AGENT -> "Plans and carries out a task"
    ModelRole.SWARM_ORCHESTRATOR -> "Plans the team's work and combines results"
    ModelRole.SWARM_WORKER -> "Handles individual tasks for the team"
}

@Composable
internal fun ModelSelectionContent(state: AISettingsUiState, catalogs: Map<ModelProvider, ProviderModelCatalog>,
    configured: Set<ModelProvider>, registry: Map<ModelProvider, List<AIModel>>,
    onOpen: (ModelRole) -> Unit, onDismiss: () -> Unit, onSelect: (ModelRole, String) -> Unit,
    onRefresh: (ModelProvider) -> Unit, onProviders: () -> Unit, onConnections: () -> Unit,
    onLocalModels: () -> Unit, modifier: Modifier = Modifier) {
    val available = remember(configured, catalogs, registry, state.localModelConfigured) {
        availableModelCatalogs(configured, catalogs, registry, state.localModelConfigured)
    }
    fun selected(role: ModelRole): AIModel? {
        val id = state.modelAssignments[role]
        return catalogs.values.asSequence().flatMap { it.models.asSequence() }.firstOrNull { it.id == id }
            ?: registry.values.asSequence().flatMap { it.asSequence() }.firstOrNull { it.id == id }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("A model for each way you work.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("Choose from your connected providers and configured local model.", Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { ModelSectionTitle("Conversations") }
        items(listOf(ModelRole.CHAT, ModelRole.AGENT), key = { it.name }) { role ->
            ModelAssignmentRow(role, selected(role), state.modelAssignments[role].orEmpty(),
                selected(role)?.provider in available, onClick = { onOpen(role) })
        }
        item { ModelSectionTitle("Multi-agent") }
        items(listOf(ModelRole.SWARM_ORCHESTRATOR, ModelRole.SWARM_WORKER), key = { it.name }) { role ->
            ModelAssignmentRow(role, selected(role), state.modelAssignments[role].orEmpty(),
                selected(role)?.provider in available, onClick = { onOpen(role) })
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    TextButton(onClick = onProviders, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Key, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Manage providers")
                    }
                    TextButton(onClick = onLocalModels, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Memory, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Set up a local model")
                    }
                }
            }
        }
    }
    state.expandedDropdownRole?.let { role ->
        ModelPickerSheet(role, state.modelAssignments[role].orEmpty(), available, catalogs, state.isSaving,
            state.modelSaveError, onDismiss, { onSelect(role, it) }, onRefresh, onProviders, onConnections, onLocalModels)
    }
}

@Composable
private fun ModelSectionTitle(title: String) {
    Text(title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ModelAssignmentRow(role: ModelRole, model: AIModel?, id: String, connected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(roleTitle(role), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(model?.displayName ?: id.ifBlank { "Choose a model" }, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (model != null && connected) model.provider.displayName else "Connect a provider or set up a local model",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Change ${roleTitle(role)} model",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(role: ModelRole, selectedId: String, available: Map<ModelProvider, List<AIModel>>,
    catalogs: Map<ModelProvider, ProviderModelCatalog>, saving: Boolean, error: String?, onDismiss: () -> Unit,
    onSelect: (String) -> Unit, onRefresh: (ModelProvider) -> Unit, onProviders: () -> Unit,
    onConnections: () -> Unit, onLocalModels: () -> Unit) {
    var query by rememberSaveable(role) { mutableStateOf("") }
    var filter by rememberSaveable(role) { mutableStateOf<ModelProvider?>(null) }
    var detail by remember { mutableStateOf<AIModel?>(null) }
    val activeFilter = filter?.takeIf { it in available }
    val visible = remember(available, query, activeFilter) {
        available.filterKeys { activeFilter == null || it == activeFilter }.mapValues { (provider, models) ->
            models.filter { query.isBlank() || it.displayName.contains(query.trim(), true) || it.id.contains(query.trim(), true)
                || provider.displayName.contains(query.trim(), true) }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).testTag("model-picker")) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${roleTitle(role)} model", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(roleHelp(role), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OmniIconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close model picker") }
            }
            OmniSearchField(query, { query = it }, "Search models", Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            if (available.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(activeFilter == null, { filter = null }, label = { Text("All providers") })
                available.keys.forEach { provider -> FilterChip(activeFilter == provider, { filter = provider },
                    label = { Text(provider.displayName) }) }
            }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            error?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("model-options"), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (available.isEmpty()) item(key = "no-providers") {
                    SettingsEmptyState("Connect your first provider", "Add an AI connection or configure a local model to get started.")
                    TextButton(onClick = onProviders, modifier = Modifier.fillMaxWidth()) { Text("Connect a provider") }
                    TextButton(onClick = onLocalModels, modifier = Modifier.fillMaxWidth()) { Text("Set up a local model") }
                } else if (query.isNotBlank() && visible.values.all { it.isEmpty() }) item(key = "no-matches") {
                    SettingsEmptyState("No matching models", "Try another name or change the provider filter.")
                }
                visible.forEach { (provider, models) ->
                    if (query.isBlank() || models.isNotEmpty()) {
                        item(key = "provider:${provider.name}") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(provider.displayName, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary)
                                if (provider != ModelProvider.LOCAL_EDGE) {
                                    if (provider == ModelProvider.GITHUB_MODELS) TextButton(onClick = onConnections) { Text("Manage") }
                                    else OmniIconButton(onClick = { onRefresh(provider) }, enabled = catalogs[provider]?.isFetching != true && !saving) {
                                        Icon(Icons.Default.Refresh, "Refresh ${provider.displayName} models")
                                    }
                                }
                            }
                            if (catalogs[provider]?.isFetching == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else if (catalogs[provider]?.error != null) Text(
                                if (models.isEmpty()) "Couldn't load models. Check your connection and retry." else "Couldn't refresh. Your saved models are still available.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            else if (models.isEmpty() && query.isBlank()) Text("No models returned. Refresh or check your provider connection.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(models, key = { "${provider.name}:${it.id}" }) { model ->
                            val selected = model.id == selectedId
                            Surface(shape = RoundedCornerShape(20.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f)
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                                border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).selectable(selected, enabled = !saving, role = Role.RadioButton,
                                        onClick = { onSelect(model.id) }).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(model.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                            if (selected) Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                        }
                                        Text(modelSummary(model), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    OmniIconButton(onClick = { detail = model }) { Icon(Icons.Default.Info, "Details for ${model.displayName}") }
                                }
                            }
                        }
                    }
                }
            }
            Text("Tap a model to save your choice.", Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    detail?.let { model -> AlertDialog(onDismissRequest = { detail = null },
        title = { Text(model.displayName) }, text = {
            SelectionContainer {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(model.provider.displayName)
                    model.shortDescription?.let { Text(it) }
                    Text("Context: ${contextSize(model.contextWindow)} tokens\nMaximum output: ${contextSize(model.maxOutputTokens)} tokens")
                    Text("Tool calls: ${if (model.supportsFunctionCalling) "Supported" else "Not supported"}\nVision: ${if (model.supportsVision) "Supported" else "Not supported"}\nReasoning: ${if (model.supportsThinking) "Supported" else "Not listed"}")
                    if (model.costPer1MInputTokens != null && model.costPer1MOutputTokens != null) {
                        Text("Catalog pricing per million tokens: \$${model.costPer1MInputTokens} input · \$${model.costPer1MOutputTokens} output")
                    }
                    Text(model.id, style = MaterialTheme.typography.labelSmall)
                }
            }
        }, confirmButton = { TextButton(onClick = { detail = null }) { Text("Done") } }) }
}

private fun contextSize(value: Int) = when {
    value >= 1_000_000 -> "${value / 1_000_000}M"
    value >= 1_000 -> "${value / 1_000}K"
    else -> value.toString()
}
private fun modelSummary(model: AIModel) = buildList {
    if (model.contextWindow > 0) add("${contextSize(model.contextWindow)} context")
    if (model.supportsFunctionCalling) add("Tools")
    if (model.supportsVision) add("Vision")
    if (model.supportsThinking) add("Reasoning")
}.joinToString(" · ").ifBlank { "Model details available" }
