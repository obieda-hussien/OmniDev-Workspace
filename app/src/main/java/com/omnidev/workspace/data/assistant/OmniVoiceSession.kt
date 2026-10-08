package com.omnidev.workspace.data.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import kotlinx.coroutines.*
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import com.omnidev.workspace.ui.chat.LocalChatMediaSaveAs
import com.omnidev.workspace.ui.chat.LocalChatMediaExternalActivity
import com.omnidev.workspace.data.model.AttachmentMeta
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.omnidev.workspace.MainActivity
import com.omnidev.workspace.R
import com.omnidev.workspace.WorkspaceChatRuntime
import com.omnidev.workspace.ui.assistant.AssistantInputActivity
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import com.omnidev.workspace.ui.assistant.AssistantOverlay
import com.omnidev.workspace.ui.assistant.AssistantSettings
import com.omnidev.workspace.ui.assistant.AssistantWindowPrivacy
import com.omnidev.workspace.ui.theme.OmniDevTheme

class OmniVoiceSession(context: Context) : VoiceInteractionSession(context) {
    private var capturePrivacy: AssistantWindowPrivacy? = null
    private val owner = SessionOwner()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller by lazy { AssistantRuntime.get(context) }
    private val speech by lazy { AssistantSpeechInput(context, controller) }
    private var composition: ComposeView? = null
    private var preserveOnHide = false
    private var userDismissed = false
    private fun handoff(action: String, media: AttachmentMeta? = null): Boolean = handoff(
        if (media == null) AssistantInputActivity.intent(context, action) else AssistantInputActivity.saveMediaIntent(context, media))
    private fun handoff(intent: Intent): Boolean {
        speech.stop()
        return runCatching { startAssistantActivity(intent) }
            .onSuccess { preserveOnHide = true; hide() }
            .onFailure { controller.message("Could not open this window. Try again or open the full conversation.") }.isSuccess
    }

    init { setTheme(R.style.Theme_OmniDevWorkspace_Assistant) }

    override fun onCreate() {
        super.onCreate()
        owner.create()
    }

    override fun onCreateContentView(): View = ComposeView(context).also { view ->
        composition = view
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        window?.window?.apply {
            decorView.setViewTreeLifecycleOwner(owner)
            decorView.setViewTreeSavedStateRegistryOwner(owner)
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            capturePrivacy?.close()
            capturePrivacy = AssistantWindowPrivacy(context, this)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        view.setContent {
            CompositionLocalProvider(
                LocalChatMediaSaveAs provides { meta -> handoff(AssistantInputActivity.SAVE_MEDIA, meta); Unit },
                LocalChatMediaExternalActivity provides { intent -> handoff(intent); Unit }
            ) {
                OmniDevTheme(dynamicColor = false) {
                    AssistantOverlay(controller, onDismiss = { userDismissed = true; preserveOnHide = false; hide() }, onExpand = {
                        controller.openConversation {
                            preserveOnHide = true
                            startAssistantActivity(Intent(context, MainActivity::class.java)
                                .putExtra("open_assistant_conversation", true)
                                .putExtra("assistant_session_id", controller.chat.uiState.value.currentSessionId ?: -1L))
                            hide()
                        }
                    }, onSetup = { handoff(AssistantInputActivity.SETTINGS) },
                        onAccess = { handoff(AssistantInputActivity.ACCESS) },
                        onAttach = { handoff(AssistantInputActivity.FILES) },
                        onSystemVoice = { handoff(AssistantInputActivity.VOICE) },
                        onMinimize = {
                            if (!Settings.canDrawOverlays(context)) handoff(AssistantInputActivity.BUBBLE)
                            else uiScope.launch { AssistantRuntime.minimizeForAction?.invoke() }
                        },
                        onMicrophone = {
                            speech.toggle({ handoff(AssistantInputActivity.MICROPHONE) }, { handoff(AssistantInputActivity.VOICE) })
                        }
                    )
                }
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        capturePrivacy?.refresh()
        if (com.omnidev.workspace.data.admin.DeviceConsentStore(context).let {
            it.locked() && !it.enabled(com.omnidev.workspace.data.admin.DeviceConsentPolicy.Scope.LOCK_OVERLAY)
        }) { hide(); return }
        preserveOnHide = false
        userDismissed = false
        AssistantRuntime.begin(context, args?.getBoolean(OmniVoiceInteractionService.RESUME) == true, native = true)
        val wake = args?.getBoolean(OmniVoiceInteractionService.WAKE_INVOCATION) == true || args?.getBoolean(OmniVoiceInteractionService.UNLOCK_REQUEST) == true
        val deviceConsent = com.omnidev.workspace.data.admin.DeviceConsentStore(context)
        @Suppress("DEPRECATION")
        if (deviceConsent.enabled(com.omnidev.workspace.data.admin.DeviceConsentPolicy.Scope.LOCK_OVERLAY)) {
            window?.window?.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        } else window?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        if (wake && deviceConsent.enabled(com.omnidev.workspace.data.admin.DeviceConsentPolicy.Scope.WAKE)) {
            window?.window?.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        } else window?.window?.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        AssistantRuntime.hideForUnlock = { preserveOnHide = true; hide() }
        if (wake && args?.getBoolean(OmniVoiceInteractionService.UNLOCK_REQUEST) != true && com.omnidev.workspace.data.voice.WakePreferences(context).autoDictation) {
            uiScope.launch {
                delay(400)
                if (controller.state.value.visible && com.omnidev.workspace.data.voice.WakePreferences(context).autoDictation) {
                    if (!com.omnidev.workspace.data.voice.LocalVoiceSessionService.start(context))
                        controller.message("Install an offline voice model in Hi Omni settings to start local voice.")
                }
            }
        }
        AssistantRuntime.openAccessCenter = { handoff(AssistantInputActivity.ACCESS) }
        AssistantRuntime.minimizeForAction = {
            AssistantBubbleService.show(context).also { started ->
                if (started) { preserveOnHide = true; hide() }
            }
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        // The first onShow can precede window attachment; Compose then creates itself on attach.
        composition?.takeIf { it.isAttachedToWindow }?.createComposition()
    }

    @Suppress("DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)
        structure?.activityComponent?.packageName?.takeIf { it != context.packageName }?.let { AssistantRuntime.targetPackage = it }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        // Delivered by Android from the foreground app, before the assistant covers it.
        controller.screenshot(screenshot)
    }

    override fun onHide() {
        // SystemUI may hide the host when the display sleeps or its credential UI takes
        // focus. That is not an explicit cancellation of the user's running task.
        if (!userDismissed && com.omnidev.workspace.data.admin.DeviceConsentStore(context).locked() &&
            (controller.chat.uiState.value.isProcessing || com.omnidev.workspace.data.voice.LocalVoiceSessionService.running)) preserveOnHide = true
        uiScope.coroutineContext.cancelChildren()
        speech.stop()
        AssistantRuntime.openAccessCenter = null
        AssistantRuntime.minimizeForAction = null
        AssistantRuntime.hideForUnlock = null
        if (preserveOnHide) controller.hide() else AssistantRuntime.close(context)
        composition?.disposeComposition()
        owner.registry.currentState = Lifecycle.State.CREATED
        super.onHide()
    }

    override fun onDestroy() {
        capturePrivacy?.close(); capturePrivacy = null
        uiScope.cancel()
        speech.stop()
        if (!preserveOnHide) AssistantRuntime.close(context)
        owner.registry.currentState = Lifecycle.State.DESTROYED
        composition?.disposeComposition()
        super.onDestroy()
    }

    private class SessionOwner : LifecycleOwner, SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        fun create() {
            savedState.performAttach()
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }
    }
}
