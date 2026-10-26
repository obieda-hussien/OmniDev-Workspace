package com.omnidev.workspace.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omnidev.workspace.data.localllm.LlamaCppInferenceEngine
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.ui.motion.OmniIconButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelManagerScreen(settingsRepository: SettingsRepository, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val model: LocalModelViewModel = viewModel(factory = LocalModelViewModel.factory(context, settingsRepository))
    val state by model.state.collectAsStateWithLifecycle()
    var advanced by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::select) }
    val enabled = !state.busy && !state.initializing
    val nativeAvailable = LlamaCppInferenceEngine.isNativeAvailable
    Scaffold(topBar = {
        TopAppBar(title = { Text("Local Edge Model") }, navigationIcon = {
            OmniIconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Your model. On your device.", style = MaterialTheme.typography.headlineSmall)
                Text("Offline conversations from a GGUF file. Models load only when you choose.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Memory, null)
                        Text(when {
                            state.initializing -> "Reading settings…"
                            state.busy -> "Working…"
                            state.loadedName != null -> "Ready on device"
                            else -> "Not loaded"
                        }, style = MaterialTheme.typography.titleLarge)
                        Text(state.loadedName ?: "Load a model to start using local chat.")
                        if (state.availableRamMb > 0) Text("${state.availableRamMb} MB available RAM at page open",
                            style = MaterialTheme.typography.labelMedium)
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (state.loadedName != null) OutlinedButton(onClick = model::unload, enabled = enabled) {
                            Text("Unload · free memory")
                        }
                    }
                }
            }
            if (!nativeAvailable) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text("Local inference is unavailable in this app build. Install a build with the native engine included.", Modifier.padding(16.dp))
                }
            }
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Model file", style = MaterialTheme.typography.titleMedium)
                        Text(state.selectedName ?: "No GGUF file selected", style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.selectedUri == null) "Choose GGUF file" else "Choose another file")
                        }
                        Button(onClick = model::load, enabled = enabled && nativeAvailable && state.selectedUri != null, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.loadedName == null) "Load model" else "Reload selected model")
                        }
                        Text("Larger models and longer context use more memory. Start with Auto settings.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Structured decision probe", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Optional experiment: compare the loaded GGUF model with Omni's local heuristic router. The model output is never used to run tools, grant permissions, or change routing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = state.decisionRequest,
                            onValueChange = model::editDecisionRequest,
                            label = { Text("Request to classify") },
                            minLines = 2,
                            maxLines = 5,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = model::probeDecision,
                            enabled = enabled && nativeAvailable && state.loadedName != null && state.decisionRequest.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Run local decision probe")
                        }
                        state.decisionProbe?.let { probe ->
                            val modelFileSummary = if (probe.modelFileBytes >= 0L) {
                                "${probe.modelFileBytes / 1_048_576L} MiB"
                            } else "not measured"
                            val freeRamSummary = if (probe.availableRamBeforeMb >= 0L) {
                                "${probe.availableRamBeforeMb} MiB"
                            } else "not measured"
                            val processPssSummary = if (probe.processPssBeforeMb >= 0L) {
                                "${probe.processPssBeforeMb} / ${probe.processPssPeakMb} / ${probe.processPssPeakDeltaMb} MiB"
                            } else "not measured"
                            val contextSummary = if (probe.activeContextTokens >= 0) {
                                "${probe.activeContextTokens} tokens"
                            } else "unknown"
                            Text("Status: " + probe.outcome.name.replace('_', ' '), style = MaterialTheme.typography.labelLarge)
                            Text("Omni heuristic: " + probe.heuristicMode.name)
                            Text("Local model candidate: " + (probe.candidate?.mode?.name ?: "not accepted"))
                            probe.candidate?.let { candidate ->
                                Text(
                                    "Hints: code=" + candidate.needsCode +
                                        ", device=" + candidate.needsDevice +
                                        ", research=" + candidate.needsResearch +
                                        ", parallelizable=" + candidate.parallelizable,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text("Model reason: " + candidate.reason, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Model file: " + modelFileSummary, style = MaterialTheme.typography.bodySmall)
                            Text("Free RAM before: " + freeRamSummary, style = MaterialTheme.typography.bodySmall)
                            Text("Process PSS before / peak / extra: " + processPssSummary, style = MaterialTheme.typography.bodySmall)
                            Text("Active model context: " + contextSummary, style = MaterialTheme.typography.bodySmall)
                            Text("Elapsed: " + probe.elapsedMs + " ms", style = MaterialTheme.typography.bodySmall)
                            Text(
                                probe.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (probe.modelAccepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "Context-aware input/output budgets reserve room for the fixed prompt and generated JSON. Other safeguards (not hard guarantees): model file up to 400 MiB, at least 384 MiB free RAM, a 15-second inference timeout, and PSS sampled every 100 ms. A memory spike can occur between samples, and native cancellation may take time. Invalid JSON is rejected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            state.error?.let { error -> item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(error, Modifier.fillMaxWidth().padding(16.dp))
                }
            } }
            state.message?.let { message -> item { Text(message, style = MaterialTheme.typography.bodyMedium) } }
            item {
                OutlinedButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (advanced) "Hide inference settings" else "Inference settings")
                }
            }
            if (advanced) item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Tune for your device", style = MaterialTheme.typography.titleMedium)
                        Text("Leave context and threads blank for Auto. Save explicitly; reload to apply context and threads.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = state.contextText, onValueChange = model::editContext,
                            label = { Text("Context tokens · Auto if blank") }, singleLine = true, enabled = enabled,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = state.threadsText, onValueChange = model::editThreads,
                            label = { Text("CPU threads · Auto if blank") }, singleLine = true, enabled = enabled,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        Text("Temperature: ${"%.1f".format(state.temperature)}")
                        Slider(value = state.temperature, onValueChange = model::editTemperature, valueRange = 0f..2f, enabled = enabled)
                        Text("Lower values give more predictable replies; higher values add variety.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = model::save, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Save settings") }
                    }
                }
            }
        }
    }
}
