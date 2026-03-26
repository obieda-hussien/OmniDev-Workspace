package com.omnidev.workspace.ui.providers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.model.ModelProvider

/**
 * API Key Management screen — "Add AI Provider".
 *
 * Displays the list of providers that currently have an API key saved, and provides
 * an FAB-launched dialog for securely entering a new key.
 *
 * ### Security note
 * Keys are stored in a dedicated `omnidev_api_keys` DataStore (app-private storage).
 * For production hardening, replace with `EncryptedSharedPreferences` backed by
 * Android Keystore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    viewModel: ProvidersViewModel,
    onNavigateBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Show snackbar messages
    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            text = "API Keys",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Manage provider credentials",
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.showAddDialog() },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add AI Provider") }
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        }
    ) { padding ->
        if (uiState.configuredProviders.isEmpty()) {
            EmptyProvidersState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            LazyColumn(
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                items(uiState.configuredProviders, key = { it.provider.name }) { entry ->
                    ProviderKeyCard(
                        entry = entry,
                        onRemove = { viewModel.removeApiKey(entry.provider) }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) } // FAB clearance
            }
        }
    }

    // Add Provider Dialog
    if (uiState.showAddDialog) {
        AddProviderDialog(
            uiState = uiState,
            onDismiss = { viewModel.dismissAddDialog() },
            onProviderSelected = { viewModel.onDialogProviderSelected(it) },
            onApiKeyChanged = { viewModel.onDialogApiKeyChanged(it) },
            onToggleVisibility = { viewModel.toggleKeyVisibility() },
            onConfirm = { viewModel.saveApiKey() }
        )
    }
}

// ──────────────────────────────────────────────
//  Sub-components
// ──────────────────────────────────────────────

@Composable
private fun ProviderKeyCard(
    entry: ProviderEntry,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.provider.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = entry.maskedKey,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
            // GitHub Copilot hint badge — token comes from Device Flow (OAuth), not a PAT
            if (entry.provider == ModelProvider.GITHUB_COPILOT) {
                Text(
                    text = "OAuth",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Remove key",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProviderDialog(
    uiState: ProvidersUiState,
    onDismiss: () -> Unit,
    onProviderSelected: (ModelProvider) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    onConfirm: () -> Unit
) {
    var providerMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Add AI Provider",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                // Provider selector
                ExposedDropdownMenuBox(
                    expanded = providerMenuExpanded,
                    onExpandedChange = { providerMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = uiState.dialogProvider.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Provider") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = providerMenuExpanded,
                        onDismissRequest = { providerMenuExpanded = false }
                    ) {
                        ModelProvider.entries.forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.displayName) },
                                onClick = {
                                    onProviderSelected(provider)
                                    providerMenuExpanded = false
                                }
                            )
                            if (provider != ModelProvider.entries.last()) {
                                HorizontalDivider()
                            }
                        }
                    }
                }

                // API key input
                OutlinedTextField(
                    value = uiState.dialogApiKey,
                    onValueChange = onApiKeyChanged,
                    label = { Text(apiKeyLabel(uiState.dialogProvider)) },
                    placeholder = { Text(apiKeyHint(uiState.dialogProvider)) },
                    singleLine = true,
                    visualTransformation = if (uiState.dialogKeyVisible)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = onToggleVisibility) {
                            Icon(
                                imageVector = if (uiState.dialogKeyVisible)
                                    Icons.Filled.VisibilityOff
                                else
                                    Icons.Filled.Visibility,
                                contentDescription = "Toggle key visibility"
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Provider-specific hint
                providerHintText(uiState.dialogProvider)?.let { hint ->
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !uiState.isSaving
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                } else {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun EmptyProvidersState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = "No API keys configured",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Tap \"Add AI Provider\" to save your API keys. Keys are stored securely in app-private storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

// ──────────────────────────────────────────────
//  Provider-specific copy helpers
// ──────────────────────────────────────────────

private fun apiKeyLabel(provider: ModelProvider): String = when (provider) {
    ModelProvider.GITHUB_COPILOT -> "GitHub OAuth Token (Device Flow)"
    else -> "API Key"
}

private fun apiKeyHint(provider: ModelProvider): String = when (provider) {
    ModelProvider.ANTHROPIC -> "sk-ant-..."
    ModelProvider.OPENAI -> "sk-..."
    ModelProvider.GEMINI -> "AIza..."
    ModelProvider.XAI -> "xai-..."
    ModelProvider.DEEPSEEK -> "sk-..."
    ModelProvider.MISTRAL -> "..."
    ModelProvider.GROQ -> "gsk_..."
    ModelProvider.CEREBRAS -> "csk-..."
    ModelProvider.GITHUB_COPILOT -> "gho_... (use Device Flow, not manual PAT)"
    ModelProvider.OPEN_ROUTER -> "sk-or-v1-..."
    else -> "Enter your API key"
}

private fun providerHintText(provider: ModelProvider): String? = when (provider) {
    ModelProvider.GITHUB_COPILOT ->
        "⚠️ GitHub Copilot requires the Device Flow to authorize — do NOT paste a manual PAT here.\n\n" +
            "Go to Settings → Integrations → GitHub, tap 'Connect via GitHub', and choose " +
            "'GitHub Copilot' sub-mode. This generates a gho_ OAuth token that can be " +
            "exchanged for a Copilot session token.\n\n" +
            "Requirement: an active GitHub Copilot subscription (Individual, Business, or Enterprise) " +
            "is needed to access models at api.githubcopilot.com."
    ModelProvider.OPEN_ROUTER ->
        "Get your key at openrouter.ai/keys — routes to 100+ models with a single key."
    ModelProvider.GROQ ->
        "Free tier available at console.groq.com — world's fastest public inference."
    ModelProvider.CEREBRAS ->
        "Sign up at inference.cerebras.ai for wafer-scale 2000+ t/s inference."
    else -> null
}
