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
    fun clearSamples() {
        samples.forEach { s -> s.frames.forEach { it.fill(0f) }; s.voice.fill(0f) }
        samples.clear()
    }
    DisposableEffect(Unit) { onDispose { clearSamples() } }
    LaunchedEffect(refresh, status) { selected = AssistantSettings.isSelected(context); enabled = prefs.enabled; enrolled = store.exists() }
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
                    Text("Hi Omni", style = MaterialTheme.typography.headlineMedium)
                    TextButton(onClick = close) { Text("Done") }
                }
                Text("A personal wake model trained and used entirely on this device. Say Hi Omni, then Omni's assistant panel appears over your screen.")
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
                    Text("Enroll and train your voice", style = MaterialTheme.typography.titleMedium)
                    Row {
                        Checkbox(consent, { consent = it }, enabled = !busy)
                        Text("I agree to record my voice and save an encrypted local acoustic profile. Raw recordings are discarded. I can delete the model at any time.", Modifier.weight(1f))
                    }
                    Text(when (samples.size) {
                        in 0..4 -> "${samples.size}/5 wake examples · Press Record, say Hi Omni once naturally, then pause. Try small changes in distance and speaking speed."
                        5 -> "Negative 1/2 · Say a different short phrase, for example: hello today."
                        6 -> "Negative 2/2 · Say another phrase, for example: open the door. Do not say Hi Omni."
                        else -> "Validation · Say Hi Omni again. This recording is kept out of training."
                    })
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(onClick = {
                        if (!consent || !permissionReady()) return@Button
                        LocalWakeService.stop(context); enabled = false; busy = true; message = "Preparing microphone… wait for the speak prompt."
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
                                    message = "Model trained, validated and encrypted locally. Press Start listening to enable Hi Omni."
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
                    Text("Listening permissions", style = MaterialTheme.typography.titleMedium)
                    Row { Switch(personal, { personal = it; prefs.setPersonalVoice(it) }, enabled = !busy); Text("Prefer only my enrolled voice", Modifier.padding(start = 12.dp)) }
                    Text("The voice profile reduces accidental activation. Recordings or similar voices can still match. It never confirms your identity, unlocks Android or approves agent actions.", style = MaterialTheme.typography.bodySmall)
                    Row { Switch(locked, { locked = it; prefs.setLockScreen(it) }, enabled = !busy); Text("Listen while the screen is locked", Modifier.padding(start = 12.dp)) }
                    Text("Requires separate device permissions for waking the screen and showing the assistant on the lock screen. No chat history or automatic dictation is exposed there.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { context.startActivity(Intent(context, DeviceAccessActivity::class.java)) }, enabled = !busy) { Text("Lock-screen permissions") }
                    Row { Switch(dictation, { dictation = it; prefs.setAutoDictation(it) }, enabled = !busy); Text("Listen for my request after waking", Modifier.padding(start = 12.dp)) }
                    Text("Optional command dictation uses your installed Android speech service and may send speech to its provider. Hi Omni detection and voice training remain local. Disabled on the lock screen.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        if (enabled) { LocalWakeService.stop(context); enabled = false }
                        else if (permissionReady()) {
                            enabled = LocalWakeService.start(context)
                            if (!enabled) message = "Unlock the device, select Omni as the default assistant and enroll a model first."
                        }
                    }, enabled = !busy && (enabled || (enrolled && selected))) { Text(if (enabled) "Stop listening" else "Start listening") }
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
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
