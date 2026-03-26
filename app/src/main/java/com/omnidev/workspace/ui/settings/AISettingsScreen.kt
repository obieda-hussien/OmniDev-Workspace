package com.omnidev.workspace.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.omnidev.workspace.data.voice.VoiceAssistantService
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.registry.ModelRegistry
import com.omnidev.workspace.ui.overlay.OmniBubbleService

/**
 * AI Preferences Dashboard — Material 3 Expressive settings screen.
 *
 * Features:
 * - Granular model routing for 4 distinct roles (Chat, Agent, Orchestrator, Worker)
 * - ExposedDropdownMenus grouped by Provider
 * - Deep Thinking mode toggle
 * - "Manage API Keys" button navigating to ProvidersScreen
 * - Elevated cards with smooth transitions
 * - Large typography following M3 Expressive guidelines
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AISettingsScreen(
    viewModel: AISettingsViewModel,
    onNavigateBack: () -> Unit = {},
    onNavigateToProviders: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    onNavigateToSystemPrompt: () -> Unit = {},
    onNavigateToMemoryExplorer: () -> Unit = {},
    onNavigateToIntegrations: () -> Unit = {},
    onNavigateToLocalModels: () -> Unit = {},
    onNavigateToScheduledTasks: () -> Unit = {},
    onNavigateToToolRegistry: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToAnalytics: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show status messages as snackbar
    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AI Preferences",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Configure model routing & behavior",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── Section: Model Routing ──
            SectionHeader(
                icon = Icons.Filled.Hub,
                title = "Granular Model Routing",
                subtitle = "Assign specific models to each functional role"
            )

            // Model role cards
            ModelRole.entries.forEach { role ->
                ModelRoleCard(
                    role = role,
                    selectedModelId = uiState.modelAssignments[role] ?: "",
                    isExpanded = uiState.expandedDropdownRole == role,
                    onExpandToggle = { viewModel.toggleDropdown(role) },
                    onModelSelected = { modelId -> viewModel.selectModelForRole(role, modelId) },
                    onDismiss = { viewModel.dismissDropdown() }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Section: Providers & API Keys ──
            SectionHeader(
                icon = Icons.Filled.Key,
                title = "API Keys",
                subtitle = "Manage credentials for each AI provider"
            )

            ApiKeysCard(onNavigateToProviders = onNavigateToProviders)

            Spacer(modifier = Modifier.height(8.dp))

            // ── Section: Advanced ──
            SectionHeader(
                icon = Icons.Filled.Psychology,
                title = "Advanced",
                subtitle = "Deep reasoning and thinking capabilities"
            )

            // Deep Thinking toggle card
            DeepThinkingCard(
                enabled = uiState.deepThinkingEnabled,
                onToggle = { viewModel.toggleDeepThinking(it) }
            )

            // God Mode toggle card
            GodModeCard(
                enabled = uiState.godModeEnabled,
                onToggle = { viewModel.toggleGodMode(it) }
            )

            // Wake-word background listening toggle
            val wakeContext = LocalContext.current
            WakeListeningCard(
                enabled = uiState.wakeListeningEnabled,
                onToggle = { enabled ->
                    viewModel.toggleWakeListening(enabled)
                    // Start or stop VoiceAssistantService based on the toggle
                    if (enabled) {
                        ContextCompat.startForegroundService(
                            wakeContext,
                            Intent(wakeContext, VoiceAssistantService::class.java)
                        )
                    } else {
                        wakeContext.stopService(
                            Intent(wakeContext, VoiceAssistantService::class.java)
                        )
                    }
                }
            )

            // Debug console card
            DebugConsoleCard(onNavigateToDebug = onNavigateToDebug)

            // Omni-Bubble overlay card
            OmniBubbleCard()

            Spacer(modifier = Modifier.height(8.dp))

            // Accessibility Service card (Semantic UI)
            AccessibilityServiceCard()

            Spacer(modifier = Modifier.height(8.dp))

            // ── Section: AI Identity & Context Studio ──
            SectionHeader(
                icon = Icons.Filled.AutoAwesome,
                title = "AI Identity & Context",
                subtitle = "Customize system prompts and manage the knowledge base"
            )

            SettingsNavCard(
                title = "👤 الملف الشخصي",
                subtitle = "اسمك وبيانات عنك — أومني هيسلم عليك بالاسم ويتكيف مع أسلوبك",
                onClick = onNavigateToProfile
            )

            SettingsNavCard(
                title = "System Prompt Studio",
                subtitle = "Customize the AI's persona with templates or raw prompts",
                onClick = onNavigateToSystemPrompt
            )

            SettingsNavCard(
                title = "Knowledge Base Explorer",
                subtitle = "Browse, search, edit, and add permanent AI memories",
                onClick = onNavigateToMemoryExplorer
            )

            SettingsNavCard(
                title = "Integrations & Linked Accounts",
                subtitle = "Connect Telegram, GitHub, and other platforms",
                onClick = onNavigateToIntegrations
            )

            SettingsNavCard(
                title = "Local Edge Model (BYOM)",
                subtitle = "Run a quantized GGUF model on-device — no API key or internet required",
                onClick = onNavigateToLocalModels
            )

            SettingsNavCard(
                title = "Scheduler Dashboard",
                subtitle = "View, cancel, and manually create autonomous background AI tasks",
                onClick = onNavigateToScheduledTasks
            )

            SettingsNavCard(
                title = "Tool Arsenal",
                subtitle = "Browse all available agent tools and their descriptions",
                onClick = onNavigateToToolRegistry
            )

            SettingsNavCard(
                title = "📊 Analytics Dashboard",
                subtitle = "View token usage, cost breakdown, tool statistics, and agent run history",
                onClick = onNavigateToAnalytics
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Section header with icon, title, and subtitle.
 */
@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Elevated card for a single model role with an ExposedDropdownMenu grouped by provider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelRoleCard(
    role: ModelRole,
    selectedModelId: String,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    onModelSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedModel = ModelRegistry.findModelById(selectedModelId)
    val roleIcon = when (role) {
        ModelRole.CHAT -> Icons.Filled.QuestionAnswer
        ModelRole.AGENT -> Icons.Filled.SmartToy
        ModelRole.SWARM_ORCHESTRATOR -> Icons.Filled.Hub
        ModelRole.SWARM_WORKER -> Icons.Filled.Code
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
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
                    ModelRegistry.modelsByProvider.forEach { (provider, models) ->
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
                        models.forEach { model ->
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

                        // Divider between providers (except last)
                        if (provider != ModelRegistry.modelsByProvider.keys.last()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }

            // Model capability badges
            selectedModel?.let { model ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
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

/**
 * Deep Thinking mode toggle card with explanation.
 */
@Composable
private fun DeepThinkingCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Deep Thinking Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Enable extended chain-of-thought reasoning. Uses <thinking> blocks for step-by-step analysis before acting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

/**
 * God Mode toggle card — enables unrestricted filesystem access (bypass Target Context scope).
 * All actions still require explicit user confirmation via the ConfirmationGate.
 */
@Composable
private fun GodModeCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🔓",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "God Mode (All Files Access)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (enabled)
                        "⚠️ ACTIVE — AI can read/write anywhere on the filesystem. Every action requires your explicit confirmation."
                    else
                        "Bypass the Target Context restriction. Requires MANAGE_EXTERNAL_STORAGE. Every privileged action triggers a confirmation gate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.error,
                    checkedTrackColor = MaterialTheme.colorScheme.errorContainer
                )
            )
        }
    }
}

/**
 * Wake-word background listening card — pure UI that delegates service management to caller.
 */
@Composable
private fun WakeListeningCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🎙️",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "الاستماع الصوتي في الخلفية",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (enabled)
                        "✅ المساعد يستمع لنداء الاستيقاظ — قل \"استيقظ\" أو \"يا أومني\" لتفعيله."
                    else
                        "تفعيل لسماع نداء الاستيقاظ الصوتي في الخلفية (مثل: \"استيقظ يا أومني\").",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle
            )
        }
    }
}

/**
 * Omni-Bubble overlay card — shows permission status and start/stop controls.
 *
 * On Android M+ the overlay permission is a special one that needs to be granted
 * via [Settings.ACTION_MANAGE_OVERLAY_PERMISSION]. If permission is already granted,
 * tapping the toggle starts/stops [OmniBubbleService].
 */
@Composable
private fun OmniBubbleCard() {
    val context = LocalContext.current
    val hasOverlayPermission = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
        else true
    }

    // Reflect actual service running state; update optimistically on toggle
    var bubbleRunning by remember { mutableStateOf(OmniBubbleService.isRunning) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.BubbleChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Omni-Bubble Overlay",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when {
                        !hasOverlayPermission -> "Requires 'Display over other apps' permission. Tap to grant."
                        bubbleRunning -> "Floating bubble is active. Tap to dismiss."
                        else -> "Floating AI assistant visible over any app. Tap to launch."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (hasOverlayPermission) {
                // Permission granted — show start/stop toggle
                Switch(
                    checked = bubbleRunning,
                    onCheckedChange = { start ->
                        bubbleRunning = start
                        if (start) OmniBubbleService.start(context)
                        else OmniBubbleService.stop(context)
                    }
                )
            } else {
                // Permission not granted — open system settings
                Card(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Text(
                        text = "Grant",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

/**
 * Accessibility Service card — shows whether the OmniAccessibilityService is enabled
 * and provides a button to open Android's Accessibility Settings to toggle it.
 */
@Composable
private fun AccessibilityServiceCard() {
    val context = LocalContext.current
    val isConnected by com.omnidev.workspace.data.accessibility.AccessibilityStateManager
        .isServiceConnected.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Psychology,
                contentDescription = null,
                tint = if (isConnected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Semantic UI Engine",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isConnected)
                        "Active — AI can read and interact with any app's UI semantically."
                    else
                        "Disabled — Enable Accessibility Service for semantic UI control.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isConnected) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (isConnected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Active",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Card(
                    onClick = {
                        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = "Enable",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

/**
 * Card that navigates to the API key management screen.
 */
@Composable
private fun ApiKeysCard(onNavigateToProviders: () -> Unit) {
    Card(
        onClick = onNavigateToProviders,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Manage API Keys",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Add keys for Anthropic, OpenAI, Gemini, and GitHub Copilot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.FilledTonalIconButton(
                onClick = onNavigateToProviders
            ) {
                Icon(
                    imageVector = Icons.Filled.Key,
                    contentDescription = "Open API Keys"
                )
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
 * Card that navigates to the Debug Console screen.
 */
@Composable
private fun DebugConsoleCard(onNavigateToDebug: () -> Unit) {
    Card(
        onClick = onNavigateToDebug,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.BugReport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Debug Console",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "View crash reports, error logs, and device diagnostics.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.FilledTonalIconButton(
                onClick = onNavigateToDebug
            ) {
                Icon(
                    imageVector = Icons.Filled.BugReport,
                    contentDescription = "Open Debug Console",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
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

/**
 * Generic navigation card for settings sections.
 */
@Composable
private fun SettingsNavCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
