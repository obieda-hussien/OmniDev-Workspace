package com.omnidev.workspace.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.localllm.LlamaCppInferenceEngine
import com.omnidev.workspace.data.localllm.LocalEngineHolder
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class SuggestedModel(
    val name: String,
    val size: String,
    val description: String,
    val huggingFaceUrl: String,
    val isBitNet: Boolean = false
)

private val SUGGESTED_MODELS = listOf(
    SuggestedModel(
        name = "Llama-3.2-1B-Instruct (Q4_K_M)",
        size = "~0.8 GB",
        description = "Ultra-compact, fast on-device chat. Best for simple Q&A.",
        huggingFaceUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF"
    ),
    SuggestedModel(
        name = "Llama-3.2-3B-Instruct (Q4_K_M)",
        size = "~2.0 GB",
        description = "Balanced quality/speed. Good general coding assistant.",
        huggingFaceUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF"
    ),
    SuggestedModel(
        name = "DeepSeek-Coder-1.3B (Q4_K_M)",
        size = "~1.0 GB",
        description = "Specialized for code generation with very low memory.",
        huggingFaceUrl = "https://huggingface.co/TheBloke/deepseek-coder-1.3b-instruct-GGUF"
    ),
    SuggestedModel(
        name = "Phi-3.5-mini-instruct (Q4_K_M)",
        size = "~2.2 GB",
        description = "Microsoft's efficient model. Excellent reasoning per parameter.",
        huggingFaceUrl = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF"
    ),
    SuggestedModel(
        name = "BitNet-b1.58-2B-4T (i2_s)",
        size = "~0.4 GB",
        description = "1-bit Microsoft model. ~70% less RAM vs float16. Compatible with llama.cpp.",
        huggingFaceUrl = "https://huggingface.co/microsoft/bitnet_b1_58-2B-4T-gguf",
        isBitNet = true
    ),
    SuggestedModel(
        name = "BitNet-b1.58-Large (i2_s)",
        size = "~0.7 GB",
        description = "Larger 1-bit model with stronger reasoning. Compatible with llama.cpp.",
        huggingFaceUrl = "https://huggingface.co/1bitLLM/bitnet_b1_58-large",
        isBitNet = true
    )
)

/**
 * Screen for managing on-device local LLM models (Bring Your Own Model).
 *
 * Provides:
 * - Section A: Suggested lightweight models with external HuggingFace links
 *   (includes standard GGUF models and BitNet i2_s GGUF models — all run via llama.cpp)
 * - Section B: File picker to select a local .gguf file with persistable URI permission
 * - Status display showing loaded model name and inference readiness
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelManagerScreen(
    settingsRepository: SettingsRepository,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loadedModelName by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loadedModelName = settingsRepository.observeLocalModelName().first()
        val savedUri = settingsRepository.observeLocalModelUri().first()
        if (savedUri != null && !LocalEngineHolder.engine.isLoaded) {
            val uri = Uri.parse(savedUri)
            val result = LocalEngineHolder.engine.loadModel(context, uri)
            loadedModelName = result.getOrNull()
        }
    }

    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = uri.lastPathSegment?.lowercase() ?: ""
            if (!fileName.endsWith(".gguf") && !fileName.contains(".gguf")) {
                statusMessage = "❌ Selected file does not appear to be a .gguf model. Please select a valid GGUF file."
                return@rememberLauncherForActivityResult
            }

            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                statusMessage = "⚠️ Could not take persistent permission: ${e.message}"
            }

            scope.launch {
                isLoading = true
                statusMessage = "Loading model…"
                val result = LocalEngineHolder.engine.loadModel(context, uri)
                isLoading = false
                if (result.isSuccess) {
                    val name = result.getOrThrow()
                    loadedModelName = name
                    statusMessage = "✅ Model loaded: $name"
                    settingsRepository.setLocalModelUri(uri.toString())
                    settingsRepository.setLocalModelName(name)
                } else {
                    statusMessage = "❌ Failed to load model: ${result.exceptionOrNull()?.message}"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Memory, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Local Edge Model (BYOM)")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Stub-build notice ──
            if (!LlamaCppInferenceEngine.isNativeAvailable) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "⚠️ llama.cpp Not Compiled",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "This build does not include the llama.cpp native library. " +
                                "Model loading will fail until the app is rebuilt with NDK support.\n\n" +
                                "To build with real on-device inference:\n" +
                                "  1. git submodule update --init --recursive\n" +
                                "  2. ./gradlew assembleDebug  (NDK required)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // ── Status Card ──
            val currentStatus = when {
                isLoading -> "⏳ Loading model…"
                LocalEngineHolder.engine.isLoaded -> "🟢 llama.cpp — ${loadedModelName ?: "Unknown"}"
                else -> "⚫ No model loaded"
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (LocalEngineHolder.engine.isLoaded)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Engine Status",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = currentStatus,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    statusMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (LocalEngineHolder.engine.isLoaded) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = {
                                LocalEngineHolder.engine.unloadModel()
                                loadedModelName = null
                                statusMessage = "Model unloaded."
                                scope.launch {
                                    settingsRepository.setLocalModelUri(null)
                                    settingsRepository.setLocalModelName(null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Unload Model")
                        }
                    }
                }
            }

            HorizontalDivider()

            // ── Section A: Suggested Models ──
            Text(
                "🤗 Suggested Models",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "All models run via llama.cpp — including BitNet i2_s quantized GGUF models. " +
                    "Download from HuggingFace, then pick the file below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SUGGESTED_MODELS.forEach { model ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(model.name, style = MaterialTheme.typography.bodyMedium)
                                if (model.isBitNet) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "BitNet",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                            Text(
                                "${model.size} · ${model.description}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(model.huggingFaceUrl))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = "Download ${model.name}")
                        }
                    }
                }
            }

            HorizontalDivider()

            // ── Section B: Model Picker ──
            Text(
                "📂 Load Model File",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Select a downloaded .gguf file from your device storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = { modelPickerLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isLoading) "Loading…" else "Select .gguf Model File")
            }

            // ── Note on routing ──
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "💡 Once a model is loaded, select \"Local Edge Model\" in the Providers screen to route Agent/Chat requests to this offline engine. No internet or API key required.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

