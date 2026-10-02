package com.omnidev.workspace.ui.assistant

import android.Manifest
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.omnidev.workspace.MainActivity
import com.omnidev.workspace.WorkspaceChatRuntime
import com.omnidev.workspace.data.assistant.AssistantController
import com.omnidev.workspace.data.assistant.AssistantSpeechInput
import com.omnidev.workspace.ui.theme.OmniDevTheme

/** Translucent ACTION_ASSIST fallback for launchers which invoke an Activity. */
class AssistantActivity : ComponentActivity() {
    companion object { const val REQUEST_MICROPHONE = "request_assistant_microphone" }
    private lateinit var controller: AssistantController
    private lateinit var speech: AssistantSpeechInput
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (intent.getBooleanExtra(REQUEST_MICROPHONE, false)) {
            finish()
        } else if (granted) speech.toggle {} else controller.message("Microphone permission was declined. You can type your question.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = AssistantController(applicationContext, WorkspaceChatRuntime.get(this))
        speech = AssistantSpeechInput(this, controller)
        controller.show()
        setContent {
            OmniDevTheme(dynamicColor = false) {
                AssistantOverlay(controller, onDismiss = ::finish, onExpand = {
                    controller.openConversation {
                        startActivity(Intent(this, MainActivity::class.java).putExtra("open_assistant_conversation", true))
                        finish()
                    }
                }, onSetup = { startActivity(AssistantSettings.intent(this)) }, onMicrophone = {
                    speech.toggle { microphone.launch(Manifest.permission.RECORD_AUDIO) }
                })
            }
        }
        if (intent.getBooleanExtra(REQUEST_MICROPHONE, false) && savedInstanceState == null) {
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    override fun onStop() { speech.stop(); super.onStop() }
    override fun onDestroy() { speech.stop(); controller.destroy(); super.onDestroy() }
}
