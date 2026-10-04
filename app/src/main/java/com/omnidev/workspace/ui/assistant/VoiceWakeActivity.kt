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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnidev.workspace.data.voice.*
import com.omnidev.workspace.ui.theme.OmniDevTheme
import kotlinx.coroutines.*

/** User-operated voice enrollment. Backgrounding or locking cancels an in-flight recording. */
class VoiceWakeActivity : ComponentActivity() {
    private var enrollment: Job? = null
    private var refresh by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!TierPolicyHolder.current.allowAccessibility || !WakePreferences(this).userCanConfigure()) { finish(); return }
        setContent { OmniDevTheme(dynamicColor = false) {
            VoiceWakeScreen(refresh, { task -> enrollment?.cancel(); enrollment = task }, ::finish)
        } }
    }
    override fun onResume() {
        super.onResume()
        if (!WakePreferences(this).userCanConfigure()) finish()
        refresh++
    }
    override fun onPause() { enrollment?.cancel(); enrollment = null; super.onPause() }
}

@Composable
private fun VoiceWakeScreen(refresh: Int, track: (Job) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { WakePreferences(context) }
    val store = remember { WakeProfileStore(context) }
    val scope = rememberCoroutineScope()
    val samples = remember { mutableStateListOf<WakeFeatures.Sample>() }
    val status by LocalWakeService.status.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var enrolled by remember { mutableStateOf(store.exists()) }
    var personal by remember { mutableStateOf(prefs.personalVoice) }
    var locked by remember { mutableStateOf(prefs.lockScreen) }
    var dictation by remember { mutableStateOf(prefs.autoDictation) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var phrase by remember { mutableStateOf(prefs.phrase) }
    var phraseDraft by remember { mutableStateOf(prefs.phrase) }
    fun clearSamples() {
        samples.forEach { s -> s.frames.forEach { it.fill(0f) }; s.voice.fill(0f) }
        samples.clear()
    }
    DisposableEffect(Unit) { onDispose { clearSamples() } }
    LaunchedEffect(refresh, status) {
        selected = AssistantSettings.isSelected(context); enabled = prefs.enabled && LocalWakeService.running; enrolled = store.exists()
        if (!LocalWakeService.running && prefs.enabled) prefs.setEnabled(false)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        message = if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            "Microphone granted. Press Record or Start listening when ready." else "Microphone permission is needed."
    }
    fun permissionReady(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        permissions.launch(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            else arrayOf(Manifest.permission.RECORD_AUDIO))
        return false
    }
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Voice activation", style = MaterialTheme.typography.headlineMedium)
                    TextButton(onClick = close) { Text("Done") }
                }
                Text("Say ‘$phrase’ to open Omni's assistant panel. Choose your own phrase, train it below, then Start listening.")
                Text("Experimental acoustic learning: five examples teach the sound of your phrase, two other phrases teach rejection, and a fresh recording checks it. It does not transcribe or verify that you said these exact words.", style = MaterialTheme.typography.bodySmall)
            }
            item { Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (selected) "Default assistant: Omni" else "Select Omni as Android's default assistant", style = MaterialTheme.typography.titleMedium)
                    Text("Native Android voice sessions allow the panel to appear from the background. No root or Accessibility permission is needed for wake detection.")
                    OutlinedButton(onClick = { context.startActivity(AssistantSettings.intent(context)) }, enabled = !busy) { Text("Android assistant setup") }
                    Text("$status · ${if (enrolled) "Local model saved" else "No model yet"}")
                    Text("Listening uses a visible microphone notification and battery. Start it yourself after a restart or Android stops the service.", style = MaterialTheme.typography.bodySmall)
                }
            } }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            item { Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1 · Choose your wake phrase", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(phraseDraft, { phraseDraft = it.take(60) }, label = { Text("Wake phrase") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Text("Any language. Use a distinct short phrase you can say in under three seconds. Changing it stops listening and deletes the old acoustic profile; record new examples afterwards.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        val chosen = runCatching { WakePhrasePolicy.normalize(phraseDraft) }.getOrElse { message = it.message; return@OutlinedButton }
                        if (chosen == phrase) { phraseDraft = chosen; return@OutlinedButton }
                        busy = true
                        track(scope.launch {
                            try {
                                check(withContext(Dispatchers.IO) { prefs.setPhrase(chosen) }) { "Could not save the phrase." }
                                clearSamples(); phrase = prefs.phrase; phraseDraft = phrase; enrolled = store.exists(); enabled = false
                                message = "Phrase saved. Record five examples, two different phrases and one validation example below."
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { message = error.message ?: "Could not change the phrase." }
                            finally { busy = false }
                        })
                    }, enabled = !busy && phraseDraft != phrase) { Text("Save phrase and retrain") }
                }
            } }
            item { Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2 · Record and train your voice", style = MaterialTheme.typography.titleMedium)
                    Row {
                        Checkbox(consent, { consent = it }, enabled = !busy)
                        Text("I agree to record my voice and save an encrypted local acoustic profile. Raw recordings are discarded. I can delete the model at any time.", Modifier.weight(1f))
                    }
                    Text(when (samples.size) {
                        in 0..4 -> "${samples.size}/5 wake examples · Press Record, say ‘$phrase’ once naturally, then pause. Try small changes in distance and speaking speed."
                        5 -> "Negative 1/2 · Say a different short phrase, for example: hello today."
                        6 -> "Negative 2/2 · Say another phrase, for example: open the door. Do not say ‘$phrase’."
                        else -> "Validation · Say ‘$phrase’ again. This recording is kept out of training."
                    })
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(onClick = {
                        if (!consent || !permissionReady()) return@Button
                        LocalWakeService.stop(context); LocalVoiceSessionService.stop(context); enabled = false; busy = true; message = "Preparing microphone… wait for the speak prompt."
                        track(scope.launch {
                            try {
                                val sample = WakeAudio.sample(context) { message = "Recording… say the displayed phrase once, then pause. Maximum 12 seconds." }
                                check(prefs.userCanConfigure()) { "Unlock the device to continue enrollment." }
                                if (samples.size < 7) {
                                    samples.add(sample)
                                    message = "Example captured."
                                } else {
                                    val model = try {
                                        withContext(Dispatchers.Default) { PersonalWakeModel.train(samples.take(5), samples.drop(5).take(2)) }.also {
                                            check(it.match(sample).accepted) { "The fresh example did not match. Retry validation or restart enrollment in a quieter room." }
                                        }
                                    } finally { sample.frames.forEach { it.fill(0f) }; sample.voice.fill(0f) }
                                    withContext(Dispatchers.IO) { store.save(model) }
                                    clearSamples(); enrolled = true
                                    message = "Model trained, validated and encrypted locally. Press Start listening to enable ‘$phrase’."
                                }
                            } catch (cancelled: CancellationException) { message = "Recording cancelled. Tap Record to try again."; throw cancelled }
                            catch (error: Exception) { message = error.message ?: "Could not record or train. Try again." }
                            finally { busy = false }
                        })
                    }, enabled = consent && !busy) { Text(if (samples.size == 7) "Record validation and train" else "Record example") }
                    TextButton(onClick = { clearSamples(); message = "Enrollment restarted. Existing saved model is retained until a new model passes validation." }, enabled = !busy) { Text("Restart enrollment") }
                }
            } }
            item { Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("3 · Start listening", style = MaterialTheme.typography.titleMedium)
                    Row { Switch(personal, { personal = it; prefs.setPersonalVoice(it) }, enabled = !busy); Text("Prefer only my enrolled voice", Modifier.padding(start = 12.dp)) }
                    Text("The voice profile reduces accidental activation. Recordings or similar voices can still match. It never confirms your identity, unlocks Android or approves agent actions.", style = MaterialTheme.typography.bodySmall)
                    Row { Switch(locked, { locked = it; prefs.setLockScreen(it) }, enabled = !busy); Text("Listen while the screen is locked", Modifier.padding(start = 12.dp)) }
                    Text("Requires separate device permissions for waking the screen and showing the assistant on the lock screen. The locked panel hides chat history. Local voice conversation is a separate opt-in below.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { context.startActivity(Intent(context, DeviceAccessActivity::class.java)) }, enabled = !busy) { Text("Lock-screen permissions") }
                    Row { Switch(dictation, { dictation = it; prefs.setAutoDictation(it) }, enabled = !busy); Text("Local voice conversation after wake", Modifier.padding(start = 12.dp)) }
                    Text("Direct offline speech recognition, with no Google startup tones. The assistant speaks using an installed offline Android voice. Private spoken unlock requires its own authenticated permission in Device access. Voice matching is not identity verification.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        if (enabled) { LocalWakeService.stop(context); enabled = false }
                        else if (permissionReady()) {
                            enabled = LocalWakeService.start(context)
                            if (!enabled) message = LocalWakeService.status.value
                        }
                    }, enabled = !busy) { Text(if (enabled) "Stop listening" else "Start listening") }
                    if (!enrolled) Text("Not trained yet: complete Step 2 before listening. Enabling lock-screen access does not train the detector.", style = MaterialTheme.typography.bodySmall)
                    if (selected && !com.omnidev.workspace.data.assistant.OmniVoiceInteractionService.ready) Text("Omni's native voice service is not ready. Re-select Omni in Android's Digital assistant settings. If activation cannot open a session, use the wake notification to open Omni.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        if (!permissionReady()) return@OutlinedButton
                        LocalWakeService.stop(context); LocalVoiceSessionService.stop(context); enabled = false; busy = true
                        track(scope.launch {
                            var sample: WakeFeatures.Sample? = null
                            try {
                                sample = WakeAudio.sample(context) { message = "Test recording · say ‘$phrase’ once, then pause." }
                                val result = withContext(Dispatchers.Default) { store.load().match(sample!!, personal) }
                                message = if (result.accepted) "Phrase matched. Press Start listening, close the assistant panel, then say it again."
                                    else "No match. Try a quiet room and consistent pronunciation, disable Prefer only my enrolled voice to test the phrase alone, or retrain."
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { message = error.message ?: "Could not test the phrase." }
                            finally { sample?.let { it.frames.forEach { frame -> frame.fill(0f) }; it.voice.fill(0f) }; busy = false }
                        })
                    }, enabled = !busy && enrolled) { Text("Test my phrase now") }
                    OutlinedButton(onClick = {
                        LocalWakeService.stop(context); enabled = false
                        track(scope.launch {
                            busy = true
                            try { withContext(Dispatchers.IO) { store.delete() }; clearSamples(); enrolled = false; consent = false; message = "Local voice profile and model deleted." }
                            finally { busy = false }
                        })
                    }, enabled = !busy && enrolled) { Text("Delete my voice profile") }
                }
            } }
            item { Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Offline speech model", style = MaterialTheme.typography.titleMedium)
                    Text(if (OfflineVoiceModels(context).ready()) "Installed · ${OfflineVoiceModels(context).language()}" else "Install a model for commands and private codes. Wake-phrase training alone does not transcribe speech.")
                    for (preset in OfflineVoiceModels.Preset.entries) {
                        OutlinedButton(onClick = {
                            LocalWakeService.stop(context); com.omnidev.workspace.data.voice.LocalVoiceSessionService.stop(context)
                            enabled = false; busy = true
                            track(scope.launch {
                                try {
                                    var displayedMb = -1L
                                    val main = android.os.Handler(android.os.Looper.getMainLooper())
                                    OfflineVoiceModels(context).install(preset) { count ->
                                        val mb = count / (1024 * 1024)
                                        if (mb != displayedMb) {
                                            displayedMb = mb
                                            main.post { message = "Downloading model · $mb MB" }
                                        }
                                    }
                                    message = "Verified offline model installed. Enable local voice conversation, then Start listening."
                                } catch (cancelled: CancellationException) { message = "Model installation cancelled."; throw cancelled }
                                catch (error: Exception) { message = "Could not install the model. Check your connection and available storage." }
                                finally { busy = false }
                            })
                        }, enabled = !busy) { Text(preset.sizeLabel) }
                    }
                    Text("Only model downloads use the network. Speech audio stays on the device. The Arabic model uses more memory and does not guarantee Egyptian dialect accuracy. Passwords must be spelled exactly; do not rely on automatic punctuation or case.", style = MaterialTheme.typography.bodySmall)
                    Text("Examples: PIN ‘one two three four’; password ‘capital alpha bravo at five’; pattern ‘one two five eight’. Say confirm or تأكيد to submit one attempt, or cancel / إلغاء. Symbols include at, hash, underscore, dash, dot, star, plus and space.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                            .onFailure { message = "Open Android Settings → Text-to-speech and install an offline voice." }
                    }, enabled = !busy) { Text("Install offline spoken voice") }
                    TextButton(onClick = {
                        LocalWakeService.stop(context); com.omnidev.workspace.data.voice.LocalVoiceSessionService.stop(context)
                        busy = true
                        track(scope.launch { try { OfflineVoiceModels(context).delete(); message = "Offline speech model removed." } finally { busy = false } })
                    }, enabled = !busy && OfflineVoiceModels(context).ready()) { Text("Remove speech model") }
                }
            } }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
