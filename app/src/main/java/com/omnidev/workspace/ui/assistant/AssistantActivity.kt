package com.omnidev.workspace.ui.assistant

import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.omnidev.workspace.MainActivity
import com.omnidev.workspace.WorkspaceChatRuntime
import com.omnidev.workspace.data.assistant.*
import com.omnidev.workspace.ui.theme.OmniDevTheme

/** Translucent ACTION_ASSIST fallback for launchers which invoke an Activity. */
class AssistantActivity : ComponentActivity() {
    private val controller by lazy { AssistantRuntime.get(this) }
    private val speech by lazy { AssistantSpeechInput(this, controller) }
    private var preserveOnClose = false
    private fun handoff(action: String) {
        speech.stop()
        runCatching { startActivity(AssistantInputActivity.intent(this, action)) }
            .onSuccess { preserveOnClose = true; controller.hide(); finish() }
            .onFailure { controller.message("Could not open the system picker. Try again or use File path.") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AssistantRuntime.begin(this, savedInstanceState != null || intent.getBooleanExtra(OmniVoiceInteractionService.RESUME, false))
        AssistantRuntime.minimizeForAction = {
            AssistantBubbleService.show(this).also { started ->
                if (started) { preserveOnClose = true; controller.hide(); finish() }
            }
        }
        setContent {
            OmniDevTheme(dynamicColor = false) {
                AssistantOverlay(controller, onDismiss = ::finish, onExpand = {
                    controller.openConversation {
                        preserveOnClose = true
                        startActivity(Intent(this, MainActivity::class.java).putExtra("open_assistant_conversation", true)
                            .putExtra("assistant_session_id", controller.chat.uiState.value.currentSessionId ?: -1L))
                        finish()
                    }
                }, onSetup = { handoff(AssistantInputActivity.SETTINGS) },
                    onAttach = { handoff(AssistantInputActivity.FILES) }, onSystemVoice = { handoff(AssistantInputActivity.VOICE) }, onMinimize = {
                        if (!Settings.canDrawOverlays(this)) handoff(AssistantInputActivity.BUBBLE)
                        else lifecycleScope.launch { AssistantRuntime.minimizeForAction?.invoke() }
                    },
                    onMicrophone = { speech.toggle({ handoff(AssistantInputActivity.MICROPHONE) }, { handoff(AssistantInputActivity.VOICE) }) })
            }
        }
    }
    override fun onStop() { speech.stop(); super.onStop() }
    override fun onDestroy() {
        speech.stop()
        AssistantRuntime.minimizeForAction = null
        if (!isChangingConfigurations && !preserveOnClose) AssistantRuntime.close(this)
        super.onDestroy()
    }
}
