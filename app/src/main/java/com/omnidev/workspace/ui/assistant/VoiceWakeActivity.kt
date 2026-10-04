package com.omnidev.workspace.ui.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnidev.workspace.data.voice.*
import com.omnidev.workspace.ui.theme.OmniDevTheme

/** Records only while foreground. Non-sensitive language downloads continue across pause/rotation. */
class VoiceWakeActivity : ComponentActivity() {
    private val model: VoiceWakeViewModel by viewModels()
    private var refresh by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!TierPolicyHolder.current.allowAccessibility || !WakePreferences(this).userCanConfigure()) { finish(); return }
        setContent { OmniDevTheme(dynamicColor = false) { VoiceWakeScreen(model, refresh, ::finish) } }
    }
    override fun onResume() {
        super.onResume()
        if (!WakePreferences(this).userCanConfigure()) finish()
        refresh++
    }
    override fun onPause() { if (TierPolicyHolder.current.allowAccessibility) model.pauseRecording(); super.onPause() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun VoiceWakeScreen(model: VoiceWakeViewModel, refresh: Int, close: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by model.state.collectAsState()
    val status by LocalWakeService.status.collectAsState()
    var options by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var editingPhrase by rememberSaveable { mutableStateOf(false) }
    var phraseDraft by rememberSaveable(state.phrase) { mutableStateOf(state.phrase) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.notify(if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            "Microphone allowed. Tap Record or Start listening when ready." else "Allow microphone access to continue.")
    }
    fun permissionReady(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        permissions.launch(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            else arrayOf(Manifest.permission.RECORD_AUDIO))
        return false
    }
    LaunchedEffect(refresh, status) { model.refresh() }
    if (confirmDelete != null) {
        val deletion = confirmDelete!!
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text(if (deletion == "profile") "Delete wake profile?" else "Remove speech language?") },
            text = { Text(if (deletion == "profile") "You will need to train your wake phrase again. Downloaded speech languages will stay installed."
                else "This language will need to be downloaded again. Your wake profile and other languages will stay installed.") },
            confirmButton = { TextButton(onClick = {
                confirmDelete = null
                if (deletion == "profile") model.deleteProfile()
                else model.deleteLanguage(OfflineVoiceModels.Preset.entries.first { it.language == deletion })
            }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Voice activation") }, navigationIcon = {
        IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Say ‘${state.phrase}’", style = MaterialTheme.typography.headlineMedium)
                Text("Train your wake phrase once, then use it to open Omni hands-free.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!state.loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.message != null || state.busy) item {
                Card(colors = CardDefaults.cardColors(containerColor = if (state.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(state.progress.ifBlank { "Updating voice settings…" }, style = MaterialTheme.typography.bodyMedium)
                            if (state.operation == VoiceWakeViewModel.Operation.DOWNLOAD) {
                                Text("You can lock the screen or switch apps. If interrupted, tap Download again to resume.", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = model::cancelDownload) { Text("Pause download") }
                            } else if (state.operation in setOf(VoiceWakeViewModel.Operation.RECORD, VoiceWakeViewModel.Operation.TEST)) {
                                TextButton(onClick = model::pauseRecording) { Text("Cancel recording") }
                            }
                        } else state.message?.let { Text(it) }
                    }
                }
            }
            if (!state.assistantSelected) item { VoiceCard("1 · Connect Android assistant") {
                Text("Select Omni as your default digital assistant so the wake phrase can open it.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { context.startActivity(AssistantSettings.intent(context)) }, enabled = !state.busy) { Text("Choose Omni") }
            } }
            item { VoiceCard(if (state.phase == WakeEnrollment.Phase.COMPLETE) "Wake phrase ready" else "${if (state.assistantSelected) "1" else "2"} · Train your wake phrase") {
                if (state.phase == WakeEnrollment.Phase.COMPLETE) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Column { Text(state.phrase, style = MaterialTheme.typography.titleLarge); Text("Trained, checked and saved on this device.", style = MaterialTheme.typography.bodySmall) }
                    }
                    TextButton(onClick = model::restart, enabled = !state.busy) { Text("Retrain my voice") }
                } else {
                    val completed = state.count
                    val recordingIndex = state.recordingIndex
                    val replacing = completed == 7 && state.phase in setOf(WakeEnrollment.Phase.EXAMPLES, WakeEnrollment.Phase.CONTRAST)
                    Text(when (state.phase) {
                        WakeEnrollment.Phase.EXAMPLES -> "${if (replacing) "Replace wake recording" else "Wake recording"} ${recordingIndex + 1} of 5"
                        WakeEnrollment.Phase.CONTRAST -> "${if (replacing) "Replace different phrase" else "Different phrase"} ${recordingIndex - 4} of 2"
                        WakeEnrollment.Phase.VALIDATION -> "Final check · ${state.attempts + 1} of 3"
                        WakeEnrollment.Phase.TRAINING_FAILED -> "Training examples need a change"
                        WakeEnrollment.Phase.VALIDATION_FAILED -> "Final check paused"
                        WakeEnrollment.Phase.COMPLETE -> "Ready"
                    }, style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { completed / 8f }, modifier = Modifier.fillMaxWidth())
                    val contrast = state.phase == WakeEnrollment.Phase.CONTRAST
                    if (state.phase in setOf(WakeEnrollment.Phase.EXAMPLES, WakeEnrollment.Phase.CONTRAST, WakeEnrollment.Phase.VALIDATION)) {
                        val contrastPhrases = listOf("hello today", "open the door", "purple coffee").filterNot { it.equals(state.phrase, ignoreCase = true) }
                        Text(if (contrast) "Say: ‘${contrastPhrases[(recordingIndex - 5).coerceIn(0, 1)]}’" else "Say: ‘${state.phrase}’", style = MaterialTheme.typography.headlineSmall)
                        Text(if (contrast) "Use different words from your wake phrase, in any language. These examples prevent accidental activation."
                            else if (state.phase == WakeEnrollment.Phase.VALIDATION) "One fresh recording checks the trained profile. Wait for Recording, say it once, then pause."
                            else "Wait for Recording, say the phrase naturally once, then pause. Keep a similar microphone distance.", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!state.consent) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(state.consent, model::consent, enabled = !state.busy)
                        Text("Allow voice recordings for an encrypted local profile. Raw audio is discarded.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                    when (state.phase) {
                        WakeEnrollment.Phase.TRAINING_FAILED -> {
                            Text(if (state.trainingIssue == PersonalWakeModel.TrainingIssue.INCONSISTENT_WAKE)
                                "One wake recording differs from the others. Re-record the indicated example; keep the other six."
                                else "One different phrase is too similar to your wake examples. Replace it with different words and rhythm; keep the other six.", style = MaterialTheme.typography.bodyMedium)
                            if (state.problemExample != null) Button(onClick = model::replaceProblemExample, enabled = !state.busy) {
                                val index = state.problemExample!!
                                Text(if (index < 5) "Replace wake recording ${index + 1}" else "Replace different phrase ${index - 4}")
                            }
                            TextButton(onClick = model::redoContrast, enabled = !state.busy) { Text("Replace both different phrases") }
                        }
                        WakeEnrollment.Phase.VALIDATION_FAILED -> {
                            Text("Your seven examples are retained. Check your pronunciation and microphone distance before trying again.", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = model::retryValidation, enabled = !state.busy) { Text("Try final check again") }
                        }
                        else -> Button(onClick = { if (permissionReady()) model.record() }, enabled = state.loaded && state.consent && !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp))
                            Text(if (state.phase == WakeEnrollment.Phase.VALIDATION) "Record final check" else "Record ${if (contrast) "different phrase" else "wake phrase"}")
                        }
                    }
                    if (completed > 0) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = model::undo, enabled = !state.busy) { Text("Redo last example") }
                            TextButton(onClick = model::restart, enabled = !state.busy) { Text("Restart training") }
                        }
                    }
                }
                TextButton(onClick = { editingPhrase = !editingPhrase }, enabled = !state.busy) { Text(if (editingPhrase) "Keep current phrase" else "Change wake phrase") }
                AnimatedVisibility(editingPhrase) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(phraseDraft, { phraseDraft = it.take(60) }, label = { Text("Wake phrase") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                    Text("Changing the phrase requires new training.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        val chosen = runCatching { WakePhrasePolicy.normalize(phraseDraft) }.getOrElse { model.notify(it.message ?: "Choose a short phrase.", true); return@OutlinedButton }
                        model.changePhrase(chosen); editingPhrase = false
                    }, enabled = !state.busy && phraseDraft.trim() != state.phrase) { Text("Save new phrase") }
                } }
            } }
            item { VoiceCard("Listening") {
                Text(if (state.listening) status else "Stopped · tap Start when you are ready", style = MaterialTheme.typography.bodyMedium)
                if (!state.enrolled) Text("Finish wake training first.", style = MaterialTheme.typography.bodySmall)
                else if (!state.assistantSelected) Text("Choose Omni as Android's assistant first.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { if (state.listening || permissionReady()) model.toggleListening() }, enabled = state.loaded && !state.busy && (state.listening || state.enrolled && state.assistantSelected), modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.listening) "Stop listening" else "Start listening")
                }
                if (state.enrolled) TextButton(onClick = { if (permissionReady()) model.test() }, enabled = !state.busy) { Text("Test saved wake phrase") }
                Text("A visible microphone notification stays on while listening. Start again after a restart or if Android stops it.", style = MaterialTheme.typography.bodySmall)
            } }
            item { VoiceCard("Speech languages") {
                Text("Optional · for spoken commands after wake. Wake training works without a language download.", style = MaterialTheme.typography.bodyMedium)
                for (preset in OfflineVoiceModels.Preset.entries) {
                    val installed = preset.language in state.installed
                    val active = installed && state.language == preset.language
                    HorizontalDivider()
                    Text(preset.sizeLabel, style = MaterialTheme.typography.titleSmall)
                    if (installed) {
                        Text(if (active) "Installed · selected" else "Installed · ready to use", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!active) OutlinedButton(onClick = { model.select(preset) }, enabled = !state.busy) { Text("Use this language") }
                            TextButton(onClick = { confirmDelete = preset.language }, enabled = !state.busy) { Text("Remove") }
                        }
                    } else OutlinedButton(onClick = { model.install(preset) }, enabled = state.loaded && !state.busy) { Text("Download ${preset.name.lowercase().replaceFirstChar { it.uppercase() }}") }
                }
                Text("Both languages stay installed when you switch. Arabic needs more storage and memory; dialect accuracy varies.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                            .onFailure { model.notify("Open Android Settings → Text-to-speech and install an offline voice.") }
                    }, enabled = !state.busy) { Text("Set up spoken replies") }
                    TextButton(onClick = model::testReply, enabled = state.loaded && !state.busy) { Text("Test spoken reply") }
                }
            } }
            item { VoiceCard("Listening options") {
                TextButton(onClick = { options = !options }) { Text(if (options) "Hide options" else "Voice preference, lock screen and conversation") }
                AnimatedVisibility(options) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { context.startActivity(AssistantSettings.intent(context)) }, enabled = !state.busy) { Text("Android assistant settings") }
                    VoiceSwitch("Prefer my enrolled voice", "Reduces accidental matches. Similar voices or recordings can still activate Omni.", state.personal, !state.busy, model::personal)
                    VoiceSwitch("Listen on the lock screen", "Requires wake-screen and lock-screen assistant permissions.", state.locked, !state.busy, model::locked)
                    if (state.locked) TextButton(onClick = { context.startActivity(Intent(context, DeviceAccessActivity::class.java)) }, enabled = !state.busy) { Text("Set up lock-screen access") }
                    VoiceSwitch("Voice conversation after wake", "Uses the selected speech language and an offline Android voice.", state.conversation, !state.busy && state.installed.isNotEmpty(), model::conversation)
                    if (state.installed.isEmpty()) Text("Download a speech language to enable conversation.", style = MaterialTheme.typography.bodySmall)
                    if (state.enrolled) TextButton(onClick = { confirmDelete = "profile" }, enabled = !state.busy) { Text("Delete wake profile") }
                } }
            } }
            item {
                TextButton(onClick = { details = !details }) { Text(if (details) "Hide how it works" else "Privacy and how it works") }
                AnimatedVisibility(details) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Wake learning matches the sound of your examples; it does not verify the exact words. Five wake examples and two different phrases train the profile. The final recording is checked separately and never used for training.", style = MaterialTheme.typography.bodySmall)
                    Text("Voice audio stays on this device. Only language downloads use the network. The encrypted wake profile and speech languages are stored separately. Voice matching does not verify identity or approve agent actions.", style = MaterialTheme.typography.bodySmall)
                    Text("Private spoken unlock needs separate permission in Device access. Spell passwords precisely; don't rely on automatic punctuation or case. Voice matching is not a substitute for device authentication.", style = MaterialTheme.typography.bodySmall)
                } }
            }
        }
    }
}

@Composable
private fun VoiceCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium); content()
    } }
}

@Composable
private fun VoiceSwitch(title: String, subtitle: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, change, enabled = enabled)
    }
}
