package com.omnidev.workspace.ui.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.ui.theme.OmniDevTheme
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.omnidev.workspace.data.assistant.*

/** Activity-result bridge for the system voice window; contains no second chat runtime. */
class AssistantInputActivity : ComponentActivity() {
    private val controller by lazy { AssistantRuntime.get(this) }
    private val files = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri -> runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        controller.addFiles(uris); resume()
    }
    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(controller::input)
        resume()
    }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) systemVoice() else { controller.message("Microphone permission was declined."); resume() }
    }
    private val settings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resume() }
    private val overlays = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) { AssistantBubbleService.show(this); finish() }
        else { controller.message("Allow display over other apps to use the floating bubble."); resume() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        when (intent.getStringExtra(ACTION)) {
            FILES -> files.launch(arrayOf("*/*"))
            MICROPHONE -> microphone.launch(Manifest.permission.RECORD_AUDIO)
            SETTINGS -> setContent {
                OmniDevTheme(dynamicColor = false) {
                    AlertDialog(onDismissRequest = ::resume, title = { Text("Assistant capabilities") }, text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Android controls access. Enable the capabilities you want to use.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { settings.launch(AssistantSettings.intent(this@AssistantInputActivity)) }) { Text("Default assistant & screen access") }
                            TextButton(onClick = { settings.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Read & interact with apps") }
                            TextButton(onClick = { settings.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }) { Text("Floating bubble") }
                            TextButton(onClick = { settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) { Text("Microphone & app permissions") }
                        }
                    }, confirmButton = { TextButton(onClick = ::resume) { Text("Done") } })
                }
            }
            BUBBLE -> {
                if (Settings.canDrawOverlays(this)) { AssistantBubbleService.show(this); finish() }
                else overlays.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            else -> systemVoice()
        }
    }
    private fun systemVoice() {
        runCatching { voice.launch(AssistantSpeechInput.intent()) }.onFailure {
            controller.message("No system voice input is installed. Install or enable a speech recognition service, or type your question."); resume()
        }
    }
    private fun resume() { OmniVoiceInteractionService.resume(this); finish() }
    companion object {
        const val ACTION = "assistant_input_action"
        const val FILES = "files"
        const val MICROPHONE = "microphone"
        const val BUBBLE = "bubble"
        const val SETTINGS = "settings"
        const val VOICE = "voice"
        fun intent(context: android.content.Context, action: String) = Intent(context, AssistantInputActivity::class.java).putExtra(ACTION, action)
    }
}
