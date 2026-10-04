package com.omnidev.workspace.ui.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
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

/**
 * Elevated card for a single model role with an ExposedDropdownMenu grouped by provider.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelRoleCard(
    catalogs: Map<ModelProvider, com.omnidev.workspace.ui.providers.ProviderModelCatalog>,
    configuredProviders: List<com.omnidev.workspace.ui.providers.ProviderEntry>,
    role: ModelRole,
    selectedModelId: String,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    onModelSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val allDynamicModels = catalogs.values.flatMap { it.models }
    val selectedModel = allDynamicModels.find { it.id == selectedModelId }
        ?: ModelRegistry.findModelById(selectedModelId)

    var expandedProviders by remember { mutableStateOf(mapOf<ModelProvider, Boolean>()) }
    val roleIcon = when (role) {
        ModelRole.CHAT -> Icons.Filled.QuestionAnswer
        ModelRole.AGENT -> Icons.Filled.SmartToy
        ModelRole.SWARM_ORCHESTRATOR -> Icons.Filled.Hub
        ModelRole.SWARM_WORKER -> Icons.Filled.Code
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .omniAnimateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Role header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = roleIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = role.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = role.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Model dropdown
            ExposedDropdownMenuBox(
                expanded = isExpanded,
                onExpandedChange = { onExpandToggle() }
            ) {
                OutlinedTextField(
                    value = selectedModel?.let { "${it.displayName} (${it.provider.displayName})" } ?: "Select model",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Selected Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    shape = RoundedCornerShape(12.dp)
                )

                ExposedDropdownMenu(
                    expanded = isExpanded,
                    onDismissRequest = onDismiss
                ) {
                    // Group models by provider
                    val configuredProvidersSet = configuredProviders.map { it.provider }.toSet()
                    val mergedModelsByProvider = ModelProvider.entries.associateWith { provider ->
                        val staticModels = ModelRegistry.modelsByProvider[provider] ?: emptyList()
                        val dynamicModels = catalogs[provider]?.models
                        if (dynamicModels.isNullOrEmpty()) staticModels else dynamicModels
                    }.filter { (provider, models) ->
                        (provider == ModelProvider.LOCAL_EDGE || provider == ModelProvider.GITHUB_COPILOT || provider in configuredProvidersSet) && models.isNotEmpty()
                    }
                    mergedModelsByProvider.forEach { (provider, models) ->
                        // Provider header
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = provider.displayName,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            },
                            onClick = {},
                            enabled = false
                        )

                        // Models under this provider
                        val isProviderExpanded = expandedProviders[provider] ?: false
                        val visibleModels = if (isProviderExpanded) models else models.take(10)
                        visibleModels.forEach { model ->
                            val isSelected = model.id == selectedModelId
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = model.displayName,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                                if (model.isLatest) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "LATEST",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                            Text(
                                                text = buildModelCapabilityString(model),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = "Selected",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = { onModelSelected(model.id) },
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }

                        if (!isProviderExpanded && models.size > 10) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Show all ${models.size} models...",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                onClick = { expandedProviders = expandedProviders + (provider to true) },
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }

                        // Divider between providers (except last)
                        if (provider != mergedModelsByProvider.keys.last()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }

            // Model capability badges
            selectedModel?.let { model ->
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Tier badge
                    CapabilityBadge(
                        text = "${model.tier.badge} ${model.tier.displayName}",
                        containerAlpha = 0.7f
                    )
                    CapabilityBadge("${formatContextWindow(model.contextWindow)} ctx")
                    if (model.isLatest) CapabilityBadge("✨ Latest")
                }
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (model.supportsVision) CapabilityBadge("👁 Vision")
                    if (model.supportsVideo) CapabilityBadge("🎥 Video")
                    if (model.supportsThinking) CapabilityBadge("🧠 Thinking")
                    model.speedTokensPerSecond?.let { CapabilityBadge("⚡ ${it}t/s") }
                }
                // Pricing row
                val inputCost = model.costPer1MInputTokens
                val outputCost = model.costPer1MOutputTokens
                if (inputCost != null && outputCost != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "\$${formatCost(inputCost)} in / \$${formatCost(outputCost)} out per 1M tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Short description
                model.shortDescription?.let { desc ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
