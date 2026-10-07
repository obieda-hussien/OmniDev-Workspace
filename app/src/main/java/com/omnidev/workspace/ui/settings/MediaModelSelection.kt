package com.omnidev.workspace.ui.settings

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.chatmedia.*
import com.omnidev.workspace.data.model.ModelProvider

@Composable
internal fun MediaAssignmentRow(kind: MediaKind, config: MediaConfig, connected: Boolean, saving: Boolean,
    onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    Surface(onClick = onOpen, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.testTag("media-assignment-${kind.action}")) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(when(kind) { MediaKind.IMAGE -> Icons.Default.Image; MediaKind.VIDEO -> Icons.Default.Movie; MediaKind.MUSIC -> Icons.Default.MusicNote }, null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(kind.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(if (!config.enabled) "Off · choose a model to enable" else config.model, style = MaterialTheme.typography.bodyMedium)
                Text(if (config.enabled && !connected) "Selected provider disconnected" else if (config.enabled) config.provider.displayName else "Creation is blocked in Chat and Agent",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(config.enabled, onCheckedChange = { if (it && !connected) onOpen() else onToggle(it) }, enabled = !saving)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaModelPickerSheet(kind: MediaKind, current: MediaConfig, options: List<MediaModelOption>,
    configured: Set<ModelProvider>, saving: Boolean, saveError: String?, onSave: (MediaConfig) -> Unit,
    onDismiss: () -> Unit, onRefresh: (ModelProvider) -> Unit, onProviders: () -> Unit) {
    var draft by remember(kind) { mutableStateOf(current) }
    var query by rememberSaveable(kind) { mutableStateOf("") }
    var advanced by rememberSaveable(kind) { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    val validation = remember(kind, draft) { runCatching { MediaRequestPolicy.validate(kind, draft) }.exceptionOrNull()?.message }
    val providerReady = draft.provider in configured
    val visible = remember(options, query) { options.filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) || it.provider.displayName.contains(query, true) }.take(150) }
    LaunchedEffect(current, saving, saveError, submitted) {
        if (submitted && !saving && saveError == null && current == draft) onDismiss()
    }
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).testTag("media-model-picker-${kind.action}")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${kind.title} model", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Your conversation model stays independent.", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { if (!saving) onDismiss() }) { Icon(Icons.Default.Close, "Close media settings") }
            }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    MediaToggle("Enable ${kind.title.lowercase()} generation", "Turning this off stops pending local work and blocks new requests.", draft.enabled, !saving) { if (!saving) draft = draft.copy(enabled = it) }
                    Text("Provider media access and usage charges are separate from your text model.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(query, { query = it }, label = { Text("Search connected media models") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                }
                if (visible.isEmpty()) item {
                    Text(if (query.isNotBlank()) "No matching generation models." else "No supported media models from connected providers. Connect a provider or refresh its catalog.")
                    TextButton(onClick = onProviders) { Text("Manage providers") }
                }
                visible.groupBy { it.provider }.forEach { (provider, models) ->
                    item(key = "provider:${provider.name}") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(provider.displayName, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            IconButton(onClick = { onRefresh(provider) }, enabled = !saving) { Icon(Icons.Default.Refresh, "Refresh ${provider.displayName}") }
                        }
                    }
                    items(models, key = { "${it.provider.name}:${it.id}" }) { option ->
                        val selected = option.id == draft.model && option.provider == draft.provider
                        Surface(onClick = { if (!saving) draft = MediaModelCatalog.select(kind, draft, option.provider, option.id) },
                            shape = RoundedCornerShape(16.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(option.name, style = MaterialTheme.typography.titleSmall)
                                    Text(option.note.ifBlank { option.id }, style = MaterialTheme.typography.bodySmall)
                                }
                                if (selected) Icon(Icons.Default.Check, "Selected model")
                            }
                        }
                    }
                }
                item {
                    // The OpenRouter catalog needs a refresh to distinguish output from input modalities.
                    if (kind == MediaKind.IMAGE && ModelProvider.OPEN_ROUTER in configured) {
                        TextButton(onClick = { onRefresh(ModelProvider.OPEN_ROUTER) }, enabled = !saving) { Text("Refresh OpenRouter image models") }
                    }
                    Text("Generation defaults", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${draft.provider.displayName} · ${draft.model}", style = MaterialTheme.typography.bodySmall)
                }
                item {
                    if (kind != MediaKind.MUSIC) MediaChoices("Aspect ratio", draft.aspect,
                        if (kind == MediaKind.VIDEO || draft.provider == ModelProvider.OPENAI) listOf("1:1", "16:9", "9:16").filter { kind != MediaKind.VIDEO || it != "1:1" }
                        else listOf("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "21:9")) { if (!saving) draft = draft.copy(aspect = it) }
                    when (kind) {
                        MediaKind.IMAGE -> {
                            if (draft.provider != ModelProvider.OPENAI) MediaChoices("Resolution", draft.resolution,
                                if (draft.model.startsWith("gemini-2.5")) listOf("1K") else if (draft.provider == ModelProvider.XAI) listOf("1K", "2K") else listOf("1K", "2K", "4K")) { if (!saving) draft = draft.copy(resolution = it) }
                            if (draft.provider in setOf(ModelProvider.OPENAI, ModelProvider.OPEN_ROUTER)) {
                                MediaChoices("Quality", draft.quality, listOf("auto", "low", "medium", "high")) { if (!saving) draft = draft.copy(quality = it) }
                                MediaChoices("File format", draft.format, listOf("png", "jpeg", "webp")) { if (!saving) draft = draft.copy(format = it, background = if (it == "jpeg") "opaque" else draft.background) }
                                MediaChoices("Background", draft.background, if (draft.format == "jpeg") listOf("auto", "opaque") else listOf("auto", "opaque", "transparent")) { if (!saving) draft = draft.copy(background = it) }
                                if (draft.format != "png") {
                                    Text("Compression quality: ${draft.compression}%", style = MaterialTheme.typography.labelLarge)
                                    Slider(draft.compression.toFloat(), { if (!saving) draft = draft.copy(compression = it.toInt()) }, valueRange = 0f..100f)
                                }
                            } else Text(if (draft.provider == ModelProvider.XAI) "xAI returns JPEG." else "Gemini returns its native image format.", style = MaterialTheme.typography.bodySmall)
                        }
                        MediaKind.VIDEO -> {
                            MediaChoices("Resolution", draft.resolution, if (draft.provider == ModelProvider.XAI) listOf("480p", "720p", "1080p") else listOf("720p", "1080p")) {
                                draft = draft.copy(resolution = it, durationSeconds = if (draft.provider == ModelProvider.GEMINI && it == "1080p") 8 else draft.durationSeconds)
                            }
                            if (draft.provider == ModelProvider.GEMINI) MediaChoices("Duration", "${draft.durationSeconds}s", if (draft.resolution == "1080p") listOf("8s") else listOf("4s", "6s", "8s")) { if (!saving) draft = draft.copy(durationSeconds = it.removeSuffix("s").toInt()) }
                            else {
                                Text("Duration: ${draft.durationSeconds} seconds", style = MaterialTheme.typography.labelLarge)
                                Slider(draft.durationSeconds.toFloat(), { if (!saving) draft = draft.copy(durationSeconds = it.toInt()) }, valueRange = 1f..15f, steps = 13)
                                MediaToggle("Generate video audio", "Include sound in the clip", draft.videoAudio, !saving) { if (!saving) draft = draft.copy(videoAudio = it) }
                            }
                        }
                        MediaKind.MUSIC -> {
                            if (draft.model == "lyria-3-clip-preview") Text("Lyria Clip creates a fixed 30-second MP3.")
                            else {
                                Text("Duration guidance: ${draft.durationSeconds} seconds", style = MaterialTheme.typography.labelLarge)
                                Slider(draft.durationSeconds.toFloat(), { if (!saving) draft = draft.copy(durationSeconds = it.toInt()) }, valueRange = 15f..180f)
                                Text("Song length and tempo are interpreted by the model; exact timing is not guaranteed.", style = MaterialTheme.typography.bodySmall)
                            }
                            if (draft.provider == ModelProvider.GEMINI && draft.model != "lyria-3-clip-preview") MediaChoices("Audio format", draft.format, listOf("mp3", "wav")) { if (!saving) draft = draft.copy(format = it) }
                            MediaToggle("Instrumental", "Create music without vocals", draft.instrumental, !saving) { if (!saving) draft = draft.copy(instrumental = it) }
                            MediaField("Genre", draft.genre, 200) { if (!saving) draft = draft.copy(genre = it) }
                            MediaField("Mood", draft.mood, 200) { if (!saving) draft = draft.copy(mood = it) }
                            MediaField("Instruments", draft.instruments, 200) { if (!saving) draft = draft.copy(instruments = it) }
                            if (!draft.instrumental) {
                                MediaField("Lyrics language", draft.language, 200) { if (!saving) draft = draft.copy(language = it) }
                                MediaField("Default lyrics (optional)", draft.lyrics, 3500, singleLine = false) { if (!saving) draft = draft.copy(lyrics = it) }
                            }
                            Text("Tempo: ${if (draft.tempoBpm == 0) "Model decides" else "${draft.tempoBpm} BPM"}", style = MaterialTheme.typography.labelLarge)
                            Slider(draft.tempoBpm.toFloat(), { if (!saving) draft = draft.copy(tempoBpm = if (it < 40) 0 else it.toInt()) }, valueRange = 0f..240f)
                        }
                    }
                }
                item {
                    MediaField("Default creative direction", draft.style, 2000, singleLine = false) { if (!saving) draft = draft.copy(style = it) }
                    MediaField("Avoid / negative prompt", draft.negativePrompt, 2000, singleLine = false) { if (!saving) draft = draft.copy(negativePrompt = it) }
                    MediaToggle("Allow settings in chat requests", "Let a request override format, timing and creative defaults. The provider/model always stay selected here.", draft.allowOverrides, !saving) { if (!saving) draft = draft.copy(allowOverrides = it) }
                    MediaToggle("Announce when ready", "Add a ready message to the original conversation after the file is saved.", draft.announceCompletion, !saving) { if (!saving) draft = draft.copy(announceCompletion = it) }
                    MediaToggle("Automatically save to device", "Save finished images/videos to the gallery and songs to Music/Omni.", draft.autoSaveToGallery, !saving && Build.VERSION.SDK_INT >= 29) { if (!saving) draft = draft.copy(autoSaveToGallery = it) }
                    TextButton(onClick = { advanced = !advanced }, enabled = !saving) { Text(if (advanced) "Hide advanced model ID" else "Advanced model ID") }
                    if (advanced) {
                        MediaField("Model ID on the selected provider", draft.model, 150) { if (!saving) draft = draft.copy(model = it) }
                        Text("Only the selected provider's supported generation family is accepted. Provider access and parameters can differ between models.", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { if (!saving) draft = MediaModelCatalog.select(kind, MediaConfig.defaults(kind), draft.provider, draft.model).copy(enabled = draft.enabled) }) { Text("Reset generation defaults") }
                    if (draft.enabled && !providerReady) Text("Connect the selected provider before enabling generation.", color = MaterialTheme.colorScheme.error)
                    validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    saveError?.takeIf { submitted }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            Button(onClick = { submitted = true; onSave(draft) }, enabled = !saving && validation == null && (!draft.enabled || providerReady),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding()) { Text(if (saving) "Saving…" else "Save ${kind.title.lowercase()} settings") }
        }
    }
}

@Composable
private fun MediaChoices(title: String, value: String, values: List<String>, onSelect: (String) -> Unit) {
    Text(title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { option -> FilterChip(value == option, { onSelect(option) }, label = { Text(option) }) }
    }
}

@Composable
private fun MediaField(title: String, value: String, limit: Int, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= limit) onChange(it) }, label = { Text(title) }, singleLine = singleLine,
        minLines = if (singleLine) 1 else 2, maxLines = if (singleLine) 1 else 6,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
}

@Composable
private fun MediaToggle(title: String, detail: String, checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = onToggle, enabled = enabled)
    }
}
