package com.omnidev.workspace.ui.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.registry.ModelRegistry
import com.omnidev.workspace.ui.motion.OmniIconButton
import com.omnidev.workspace.ui.motion.omniAnimateContentSize
import com.omnidev.workspace.ui.providers.ProvidersViewModel
import kotlinx.coroutines.launch

/** Settings hub: one searchable directory, with focused pages for inline preferences. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AISettingsScreen(
    viewModel: AISettingsViewModel,
    onNavigateBack: () -> Unit = {},
    providersViewModel: ProvidersViewModel? = null,
    onNavigateToProviders: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    onNavigateToMemoryExplorer: () -> Unit = {},
    onNavigateToIntegrations: () -> Unit = {},
    onNavigateToLocalModels: () -> Unit = {},
    onNavigateToScheduledTasks: () -> Unit = {},
    onNavigateToToolRegistry: () -> Unit = {},
    onNavigateToMcpSettings: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToAnalytics: () -> Unit = {},
    onNavigateToAgentBrain: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val providersUiState by providersViewModel?.uiState?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    var activePage by rememberSaveable { mutableStateOf<SettingsDestination?>(null) }
    val homeListState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val allowVoice = com.omnidev.workspace.core.policy.TierPolicyHolder.current.allowAccessibility
    val entries = remember(allowVoice) {
        SettingsCatalog.entries.filter { allowVoice || it.destination != SettingsDestination.VOICE }
    }
    val accessibilityConnected by com.omnidev.workspace.data.accessibility.AccessibilityStateManager
        .isServiceConnected.collectAsStateWithLifecycle()

    fun backToHub() {
        viewModel.dismissDropdown()
        activePage = null
    }
    BackHandler(enabled = activePage != null) { backToHub() }

    // Fetch only when model selection is opened; browsing settings needs no catalogs.
    LaunchedEffect(activePage, providersUiState?.configuredProviders) {
        if (activePage == SettingsDestination.MODELS) {
            providersUiState?.configuredProviders?.forEach { entry ->
                if (entry.provider != ModelProvider.GITHUB_COPILOT &&
                    providersUiState?.catalogs?.get(entry.provider)?.isFetching != true &&
                    providersUiState?.catalogs?.get(entry.provider)?.models?.isEmpty() != false
                ) {
                    providersViewModel?.refreshModels(entry.provider)
                }
            }
        }
    }
    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearStatusMessage()
        }
    }

    fun open(destination: SettingsDestination) {
        keyboard?.hide()
        when (destination) {
            SettingsDestination.PROVIDERS -> onNavigateToProviders()
            SettingsDestination.LOCAL_MODELS -> onNavigateToLocalModels()
            SettingsDestination.PROFILE -> onNavigateToProfile()
            SettingsDestination.MEMORY -> onNavigateToMemoryExplorer()
            SettingsDestination.INTEGRATIONS -> onNavigateToIntegrations()
            SettingsDestination.MCP -> onNavigateToMcpSettings()
            SettingsDestination.SCHEDULE -> onNavigateToScheduledTasks()
            SettingsDestination.SKILLS -> onNavigateToToolRegistry()
            SettingsDestination.ANALYTICS -> onNavigateToAnalytics()
            SettingsDestination.BRAIN -> onNavigateToAgentBrain()
            SettingsDestination.DEBUG -> onNavigateToDebug()
            SettingsDestination.DEVICE_ACCESS, SettingsDestination.VOICE -> {
                val activity = if (destination == SettingsDestination.DEVICE_ACCESS)
                    com.omnidev.workspace.ui.assistant.DeviceAccessActivity::class.java
                else com.omnidev.workspace.ui.assistant.VoiceWakeActivity::class.java
                runCatching { context.startActivity(Intent(context, activity)) }
                    .onFailure { viewModel.showStatusMessage("Could not open ${entries.first { it.destination == destination }.title}.") }
            }
            else -> activePage = destination
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        activePage?.let { page -> entries.first { it.destination == page }.title } ?: "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    OmniIconButton(onClick = { if (activePage != null) backToHub() else onNavigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (activePage != null) "Back to settings" else "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        val summaries = mapOf(
            SettingsDestination.REASONING to if (uiState.deepThinkingEnabled) "Enabled" else "Off · use more reasoning when needed",
            SettingsDestination.FILE_ACCESS to if (uiState.godModeEnabled) "Enabled · Android access rules still apply" else "Limited to the selected project",
            SettingsDestination.ACCESSIBILITY to if (accessibilityConnected) "Connected · screen control available" else "Not connected · set up screen control"
        )
        val pageContent: @Composable (SettingsDestination?) -> Unit = { page ->
            if (page == null) {
                SettingsHome(
                    query = query,
                    onQueryChange = {
                        query = it
                        scope.launch { searchListState.scrollToItem(0) }
                    },
                    entries = entries,
                    summaries = summaries,
                    listState = if (query.isBlank()) homeListState else searchListState,
                    onOpen = ::open,
                    modifier = Modifier.padding(padding)
                )
            } else {
                key(page) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            entries.first { it.destination == page }.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        when (page) {
                            SettingsDestination.MODELS -> {
                                ModelRole.entries.forEach { role ->
                                    ModelRoleCard(
                                        catalogs = providersUiState?.catalogs ?: emptyMap(),
                                        configuredProviders = providersUiState?.configuredProviders ?: emptyList(),
                                        role = role,
                                        selectedModelId = uiState.modelAssignments[role] ?: "",
                                        isSaving = uiState.isSaving,
                                        isExpanded = uiState.expandedDropdownRole == role,
                                        onExpandToggle = { viewModel.toggleDropdown(role) },
                                        onModelSelected = { viewModel.selectModelForRole(role, it) },
                                        onDismiss = { viewModel.dismissDropdown() }
                                    )
                                }
                                TextButton(onClick = onNavigateToProviders, modifier = Modifier.fillMaxWidth()) {
                                    Text("Manage providers & API keys")
                                }
                            }
                            SettingsDestination.REASONING -> DeepThinkingCard(uiState.deepThinkingEnabled, viewModel::toggleDeepThinking)
                            SettingsDestination.FILE_ACCESS -> GodModeCard(uiState.godModeEnabled, viewModel::toggleGodMode)
                            SettingsDestination.ACCESSIBILITY -> AccessibilityServiceCard(viewModel::showStatusMessage)
                            SettingsDestination.ASSISTANT -> com.omnidev.workspace.ui.assistant.AssistantSettingsCard(showAccessLinks = false)
                            else -> Unit
                        }
                    }
                }
            }
        }
        val motion = com.omnidev.workspace.ui.motion.LocalOmniMotion.current
        if (motion.reduced) {
            pageContent(activePage)
        } else {
            androidx.compose.animation.Crossfade(
                targetState = activePage,
                animationSpec = androidx.compose.animation.core.tween(motion.navigationMillis),
                label = "Settings navigation"
            ) { pageContent(it) }
        }
    }
}

/** Role assignments stay compact; a focused searchable picker handles large provider catalogs. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelRoleCard(
    catalogs: Map<ModelProvider, com.omnidev.workspace.ui.providers.ProviderModelCatalog>,
    configuredProviders: List<com.omnidev.workspace.ui.providers.ProviderEntry>,
    role: ModelRole,
    selectedModelId: String,
    isSaving: Boolean,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    onModelSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val configured = configuredProviders.map { it.provider }.toSet()
    val modelsByProvider = ModelProvider.entries.associateWith { provider ->
        catalogs[provider]?.models?.takeIf { it.isNotEmpty() } ?: ModelRegistry.modelsByProvider[provider].orEmpty()
    }.filter { (provider, models) ->
        models.isNotEmpty() && (provider in configured || provider == ModelProvider.LOCAL_EDGE || provider == ModelProvider.GITHUB_COPILOT)
    }
    val selected = catalogs.values.flatMap { it.models }.find { it.id == selectedModelId }
        ?: ModelRegistry.findModelById(selectedModelId)
    var details by rememberSaveable(role.name) { mutableStateOf(false) }
    var search by rememberSaveable(role.name) { mutableStateOf("") }
    var providerFilter by rememberSaveable(role.name) { mutableStateOf<ModelProvider?>(null) }
    val roleIcon = when (role) {
        ModelRole.CHAT -> Icons.Filled.QuestionAnswer
        ModelRole.AGENT -> Icons.Filled.SmartToy
        ModelRole.SWARM_ORCHESTRATOR -> Icons.Filled.Hub
        ModelRole.SWARM_WORKER -> Icons.Filled.Code
    }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().omniAnimateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(roleIcon, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(role.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(role.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(onClick = onExpandToggle, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(selected?.displayName ?: selectedModelId.ifBlank { "Choose a model" }, style = MaterialTheme.typography.titleSmall)
                    Text(selected?.provider?.displayName ?: "Select from your available providers", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Change model", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            selected?.let { model ->
                Text("${formatContextWindow(model.contextWindow)} context${if (model.supportsVision) " · Vision" else ""}${if (model.supportsThinking) " · Thinking" else ""}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { details = !details }) { Text(if (details) "Hide model details" else "Model details") }
                com.omnidev.workspace.ui.motion.OmniAnimatedVisibility(details) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(model.id, style = MaterialTheme.typography.labelSmall)
                        Text(model.shortDescription.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CapabilityBadge(model.tier.displayName)
                            if (model.supportsVideo) CapabilityBadge("Video")
                            model.speedTokensPerSecond?.let { CapabilityBadge("$it tokens/s") }
                        }
                        if (model.costPer1MInputTokens != null && model.costPer1MOutputTokens != null) {
                            Text("Catalog pricing / 1M tokens: \$${formatCost(model.costPer1MInputTokens)} input · \$${formatCost(model.costPer1MOutputTokens)} output",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (isExpanded) androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.padding(16.dp).widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 640.dp),
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Model for ${role.displayName}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                if (isSaving) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                com.omnidev.workspace.ui.components.SettingsSearchField(search, { search = it }, "Search models or providers")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(selected = providerFilter == null || providerFilter !in modelsByProvider,
                        onClick = { providerFilter = null }, label = { Text("All providers") })
                    modelsByProvider.keys.forEach { provider ->
                        androidx.compose.material3.FilterChip(selected = providerFilter == provider, onClick = { providerFilter = provider },
                            label = { Text(provider.displayName) })
                    }
                }
                val query = search.trim()
                val visible = modelsByProvider.filterKeys { providerFilter == null || providerFilter !in modelsByProvider || it == providerFilter }
                    .mapValues { (provider, models) -> models.filter {
                        query.isBlank() || it.displayName.contains(query, true) || it.id.contains(query, true) || provider.displayName.contains(query, true)
                    } }.filterValues { it.isNotEmpty() }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (visible.isEmpty()) item {
                        com.omnidev.workspace.ui.components.SettingsEmptyState("No matching models", "Try another search or connect a provider in settings.")
                    }
                    visible.forEach { (provider, models) ->
                        item(key = "provider:${provider.name}") {
                            Column {
                                Text("${provider.displayName} · ${models.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                if (catalogs[provider]?.isFetching == true) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                                else if (catalogs[provider]?.error != null) Text("Showing cached catalog. Refresh this provider in Providers & API keys.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(models.size, key = { index -> "${provider.name}:${models[index].id}:$index" }) { index ->
                            val model = models[index]
                            val chosen = model.id == selectedModelId
                            Surface(onClick = { onModelSelected(model.id) }, enabled = !isSaving, shape = RoundedCornerShape(12.dp),
                                color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(model.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                                        Text(buildModelCapabilityString(model), style = MaterialTheme.typography.bodySmall,
                                            color = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (chosen) Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }
                }
                Text("Selecting a model saves the assignment immediately.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Compact badge chip showing a model capability.
 */
@Composable
private fun CapabilityBadge(text: String, containerAlpha: Float = 0.5f) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = containerAlpha))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun DeepThinkingCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    SettingsToggleCard(
        icon = Icons.Filled.AutoAwesome,
        title = "Deep thinking",
        description = "Spend more time reasoning before answering or acting.",
        enabled = enabled,
        onToggle = onToggle
    )
    Text(
        "Available on supported models. Extended reasoning can increase response time and token use.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun GodModeCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    SettingsToggleCard(
        icon = Icons.Filled.FolderOpen,
        title = "Extended file access",
        description = "Allow file tools outside the selected project.",
        enabled = enabled,
        onToggle = onToggle
    )
    Text(
        "Android permissions still apply. Protected paths need a supported Shizuku or root backend; this setting does not grant root access.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SettingsToggleCard(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}

@Composable
private fun AccessibilityServiceCard(onError: (String) -> Unit) {
    val context = LocalContext.current
    val isConnected by com.omnidev.workspace.data.accessibility.AccessibilityStateManager
        .isServiceConnected.collectAsStateWithLifecycle()
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Psychology, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(
                    if (isConnected) "Connected" else "Not connected",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "Enable OmniDev in Android's accessibility settings to read and interact with app screens.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.material3.FilledTonalButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { onError("Could not open Android accessibility settings.") }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isConnected) "Manage in Android settings" else "Open Android settings")
            }
        }
    }
}

/**
 * Formats a cost value for display (e.g., 0.14 → "0.14", 15.0 → "15.00").
 */
private fun formatCost(cost: Double): String = if (cost < 1.0) {
    String.format("%.2f", cost)
} else {
    String.format("%.0f", cost)
}

/**
 * Formats context window size for display (e.g., 200000 → "200K").
 */
private fun formatContextWindow(tokens: Int): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
    tokens >= 1_000 -> "${tokens / 1_000}K"
    else -> "$tokens"
}

/**
 * Builds a capability summary string for a model (used in dropdown items).
 */
private fun buildModelCapabilityString(model: AIModel): String = buildString {
    append("${model.tier.badge} ${formatContextWindow(model.contextWindow)} ctx")
    if (model.supportsVision) append(" · Vision")
    if (model.supportsVideo) append(" · Video")
    if (model.supportsThinking) append(" · Thinking")
    model.speedTokensPerSecond?.let { append(" · ${it}t/s") }
    model.costPer1MInputTokens?.let { append(" · \$${formatCost(it)}/M") }
}
