package com.omnidev.workspace.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnidev.workspace.data.mcp.McpConfigWrapper
import com.omnidev.workspace.ui.components.SettingsDisclosure
import com.omnidev.workspace.ui.components.SettingsEmptyState
import com.omnidev.workspace.ui.components.SettingsPageTabs
import com.omnidev.workspace.ui.motion.OmniIconButton
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpSettingsScreen(viewModel: McpSettingsViewModel, onNavigateBack: () -> Unit) {
    val draft by viewModel.jsonConfigState.collectAsStateWithLifecycle()
    val saved by viewModel.savedJson.collectAsStateWithLifecycle()
    val error by viewModel.errorMessage.collectAsStateWithLifecycle()
    val success by viewModel.isSaved.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var help by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val dirty = saved != null && draft != saved
    val json = remember { Json { ignoreUnknownKeys = true } }
    val preview = remember(draft) { runCatching { json.decodeFromString<McpConfigWrapper>(draft) }.getOrNull() }
    fun leave() { if (busy) return; if (dirty) confirm = "leave" else onNavigateBack() }
    BackHandler { leave() }
    confirm?.let { action ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Discard configuration changes?") },
            text = { Text("Your current JSON edits have not been saved.") },
            confirmButton = { TextButton(onClick = {
                confirm = null
                if (action == "reload") viewModel.loadConfig() else onNavigateBack()
            }) { Text("Discard") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep editing") } })
    }
    Scaffold(topBar = { TopAppBar(title = { Text("MCP services") }, navigationIcon = {
        OmniIconButton(onClick = { leave() }, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }, actions = {
        OmniIconButton(onClick = { if (dirty) confirm = "reload" else viewModel.loadConfig() }, enabled = !busy) {
            Icon(Icons.Default.Refresh, "Reload saved configuration")
        }
    }) }, bottomBar = {
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (success) Text("Configuration saved", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                Button(onClick = viewModel::saveConfig, enabled = dirty && !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Working…" else if (saved == null) "Configuration unavailable" else if (dirty) "Save configuration" else "Configuration saved")
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SettingsPageTabs(listOf("Services", "JSON editor"), tab, { tab = it })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (tab == 0) LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text("Tools beyond Omni", style = MaterialTheme.typography.headlineSmall)
                    Text("Connect external tool servers. This preview shows ${if (dirty) "your unsaved draft" else "your saved configuration"}; it does not test connectivity.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (saved == null && !busy) item { SettingsEmptyState("Configuration unavailable", "Reload to try again.") }
                else if (preview == null && saved != null) item {
                    SettingsEmptyState("Draft needs attention", "Open the JSON editor to correct the configuration.")
                    TextButton(onClick = { tab = 1 }) { Text("Open editor") }
                }
                else if (preview?.mcpServers?.isEmpty() == true) item {
                    SettingsEmptyState("No services configured", "Add your server's endpoint in the JSON editor.")
                    Button(onClick = { tab = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Configure a service") }
                }
                items(preview?.mcpServers?.entries?.toList().orEmpty(), key = { it.key }) { (name, config) ->
                    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            val host = runCatching { java.net.URI(config.url).host }.getOrNull()
                            Text("${config.type.uppercase()}${host?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                            Text(if (config.tools.isEmpty() || "*" in config.tools) "All tools allowed" else "${config.tools.size} tools allowed", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { SettingsDisclosure("Connection guide", "Endpoint types and JSON structure", help, { help = !help }) {
                    Text("Use http for a Streamable HTTP MCP endpoint, typically ending in /mcp. Use rest for legacy /tools/list and /tools/call adapters.", style = MaterialTheme.typography.bodyMedium)
                    Text("Each entry in mcpServers has type, url, tools and optional env values. Changes apply when you save.", style = MaterialTheme.typography.bodySmall)
                } }
            } else Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Edit server configuration", style = MaterialTheme.typography.titleMedium)
                Text("Save validates JSON before replacing your configured services.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(draft, viewModel::updateJsonConfig, modifier = Modifier.fillMaxWidth().weight(1f),
                        enabled = saved != null && !busy, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        isError = error != null, shape = RoundedCornerShape(16.dp),
                        label = { Text("mcpServers JSON") })
                }
            }
        }
    }
}
