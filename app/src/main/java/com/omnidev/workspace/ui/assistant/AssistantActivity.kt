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
    private var runtimeOpened = false
    private var publicDraftId: String? = null
    private var publicDraftText: String? = null
    private var preserveOnClose = false
    private var capturePrivacy: AssistantWindowPrivacy? = null
    private fun handoff(action: String): Boolean {
        speech.stop()
        return runCatching { startActivity(AssistantInputActivity.intent(this, action)) }
            .onSuccess { preserveOnClose = true; controller.hide(); finish() }
            .onFailure { controller.message("Could not open the system picker. Try again or use File path.") }.isSuccess
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val publicSearch = intent.action == com.omnilink.sdk.OmniLinkConstants.ACTION_PUBLIC_OMNI_REQUEST
        val navigation = if (publicSearch) runCatching {
            if (savedInstanceState?.getBoolean("validated_launcher_request") == true)
                com.omnidev.workspace.data.ipc.OmniSearchIngress.Navigation(savedInstanceState.getString("launcher_draft"))
            else intent.getStringExtra(com.omnilink.sdk.OmniLinkConstants.EXTRA_PUBLIC_REQUEST_JSON)
                ?.let(com.omnidev.workspace.data.ipc.OmniSearchIngress::parse)
        }.getOrNull() else null
        if (publicSearch && navigation == null) { finish(); return }
        intent.removeExtra(com.omnilink.sdk.OmniLinkConstants.EXTRA_PUBLIC_REQUEST_JSON)
        capturePrivacy = AssistantWindowPrivacy(this, window)
        val lockConsent = com.omnidev.workspace.data.admin.DeviceConsentStore(this)
            .enabled(com.omnidev.workspace.data.admin.DeviceConsentPolicy.Scope.LOCK_OVERLAY)
        if (com.omnidev.workspace.data.admin.DeviceConsentStore(this).locked() && !lockConsent) { finish(); return }
        if (android.os.Build.VERSION.SDK_INT >= 27) setShowWhenLocked(lockConsent)
        else if (lockConsent) {
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        AssistantRuntime.begin(this, publicSearch || savedInstanceState != null || intent.getBooleanExtra(OmniVoiceInteractionService.RESUME, false))
        runtimeOpened = true
        if (publicSearch) {
            publicDraftId = savedInstanceState?.getString("launcher_request_id") ?: java.util.UUID.randomUUID().toString()
            publicDraftText = navigation?.prompt
            navigation?.prompt?.let { controller.acceptExternalSearchDraft(it, publicDraftId!!) }
        }
        AssistantRuntime.openAccessCenter = { handoff(AssistantInputActivity.ACCESS) }
        AssistantRuntime.hideForUnlock = { preserveOnClose = true; speech.stop(); controller.hide(); finish() }
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
                    onAccess = { handoff(AssistantInputActivity.ACCESS) },
                    onAttach = { handoff(AssistantInputActivity.FILES) }, onSystemVoice = { handoff(AssistantInputActivity.VOICE) }, onMinimize = {
                        if (!Settings.canDrawOverlays(this)) handoff(AssistantInputActivity.BUBBLE)
                        else lifecycleScope.launch { AssistantRuntime.minimizeForAction?.invoke() }
                    },
                    onMicrophone = { speech.toggle({ handoff(AssistantInputActivity.MICROPHONE) }, { handoff(AssistantInputActivity.VOICE) }) })
            }
        }
    }
    override fun onResume() { super.onResume(); capturePrivacy?.refresh(); com.omnidev.workspace.data.tools.PermissionRequestBridge.attach(this) }
    override fun onPause() { com.omnidev.workspace.data.tools.PermissionRequestBridge.detach(this); super.onPause() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != com.omnilink.sdk.OmniLinkConstants.ACTION_PUBLIC_OMNI_REQUEST || !runtimeOpened) return
        val navigation = runCatching {
            intent.getStringExtra(com.omnilink.sdk.OmniLinkConstants.EXTRA_PUBLIC_REQUEST_JSON)
                ?.let(com.omnidev.workspace.data.ipc.OmniSearchIngress::parse)
        }.getOrNull() ?: return
        intent.removeExtra(com.omnilink.sdk.OmniLinkConstants.EXTRA_PUBLIC_REQUEST_JSON)
        setIntent(intent)
        publicDraftId = java.util.UUID.randomUUID().toString()
        publicDraftText = navigation.prompt
        navigation.prompt?.let { controller.acceptExternalSearchDraft(it, publicDraftId!!) }
        controller.show()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        publicDraftId?.let {
            outState.putBoolean("validated_launcher_request", true)
            outState.putString("launcher_request_id", it)
            outState.putString("launcher_draft", publicDraftText)
        }
        super.onSaveInstanceState(outState)
    }
    override fun onStop() { if (runtimeOpened) speech.stop(); super.onStop() }
    override fun onDestroy() {
        capturePrivacy?.close(); capturePrivacy = null
        if (!runtimeOpened) { super.onDestroy(); return }
        speech.stop()
        AssistantRuntime.openAccessCenter = null
        AssistantRuntime.hideForUnlock = null
        AssistantRuntime.minimizeForAction = null
        if (!isChangingConfigurations && !preserveOnClose) AssistantRuntime.close(this)
        super.onDestroy()
    }
}
