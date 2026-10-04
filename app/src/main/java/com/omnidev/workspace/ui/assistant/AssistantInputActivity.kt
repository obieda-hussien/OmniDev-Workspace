package com.omnidev.workspace.ui.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import com.omnidev.workspace.data.assistant.*

/** Activity-result bridge for the system voice window; contains no second chat runtime. */
class AssistantInputActivity : ComponentActivity() {
    private val controller by lazy { AssistantRuntime.get(this) }
    private val generation by lazy { intent.getIntExtra(GENERATION, -1) }
    private fun isCurrent() = generation == controller.sessionGeneration

    private val files = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!isCurrent()) { finish(); return@registerForActivityResult }
        uris.forEach { uri -> runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        if (uris.isNotEmpty()) controller.addFiles(uris)
        resume()
    }
    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!isCurrent()) { finish(); return@registerForActivityResult }
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(controller::input)
        resume()
    }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!isCurrent()) { finish(); return@registerForActivityResult }
        if (granted) systemVoice() else { controller.message("Microphone permission was declined."); resume() }
    }
    private val settings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (isCurrent()) result.data?.getStringExtra(AssistantSetupActivity.ERROR)?.let(controller::message)
        resume()
    }
    private val overlays = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!isCurrent()) { finish(); return@registerForActivityResult }
        if (Settings.canDrawOverlays(this)) minimize()
        else { controller.message("Allow display over other apps to use the floating bubble."); resume() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isCurrent()) { finish(); return }
        if (savedInstanceState != null) return
        when (intent.getStringExtra(ACTION)) {
            FILES -> external("The Android file picker could not open. Use File path, or enable a document provider.") { files.launch(arrayOf("*/*")) }
            MICROPHONE -> external("Microphone permission could not be requested. You can still type your question.") { microphone.launch(Manifest.permission.RECORD_AUDIO) }
            SETTINGS -> external("Assistant settings could not open. Use Android Settings → Apps → Default apps.") { settings.launch(AssistantSettings.intent(this)) }
            BUBBLE -> {
                if (!controller.flavor.allowBubble) { controller.message("Floating bubbles are unavailable in this build."); resume(); return }
                if (Settings.canDrawOverlays(this)) minimize()
                else external("Overlay settings could not open. You can continue using the assistant here.") {
                    overlays.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
            else -> systemVoice()
        }
    }
    private fun external(error: String, launch: () -> Unit) {
        runCatching(launch).onFailure { controller.message(error); resume() }
    }
    private fun systemVoice() = external("No system voice input is installed. Enable a speech recognition service, or type your question.") {
        voice.launch(AssistantSpeechInput.intent())
    }
    private fun minimize() {
        lifecycleScope.launch { if (AssistantBubbleService.show(this@AssistantInputActivity)) finish() else resume() }
    }
    private fun resume() {
        if (isCurrent()) runCatching { OmniVoiceInteractionService.resume(this) }.onFailure {
            android.widget.Toast.makeText(this, "Hold Home to reopen Omni. Your conversation is saved.", android.widget.Toast.LENGTH_LONG).show()
        }
        finish()
    }
    companion object {
        private const val GENERATION = "assistant_input_generation"
        const val ACTION = "assistant_input_action"
        const val FILES = "files"
        const val MICROPHONE = "microphone"
        const val BUBBLE = "bubble"
        const val SETTINGS = "settings"
        const val VOICE = "voice"
        fun intent(context: android.content.Context, action: String) = Intent(context, AssistantInputActivity::class.java).putExtra(ACTION, action).putExtra(GENERATION, AssistantRuntime.get(context).sessionGeneration)
    }
}
