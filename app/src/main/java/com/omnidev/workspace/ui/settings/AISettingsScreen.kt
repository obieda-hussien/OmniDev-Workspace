package com.omnidev.workspace.ui.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.registry.ModelRegistry
import com.omnidev.workspace.ui.motion.OmniIconButton
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
                if (entry.provider !in setOf(ModelProvider.GITHUB_MODELS, ModelProvider.LOCAL_EDGE) &&
                    providersUiState?.catalogs?.get(entry.provider)?.isFetching != true &&
                    providersUiState?.catalogs?.get(entry.provider)?.lastFetchedAt == null
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
            } else if (page == SettingsDestination.MODELS) {
                ModelSelectionContent(uiState, providersUiState?.catalogs ?: emptyMap(),
                    providersUiState?.configuredProviders?.map { it.provider }?.toSet() ?: emptySet(),
                    ModelRegistry.modelsByProvider, viewModel::toggleDropdown, viewModel::dismissDropdown,
                    viewModel::selectModelForRole, { providersViewModel?.refreshModels(it) },
                    onNavigateToProviders, onNavigateToIntegrations, onNavigateToLocalModels,
                    modifier = Modifier.padding(padding).consumeWindowInsets(padding), onMediaSave = viewModel::saveMediaConfig)
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
