package com.omnidev.workspace.data.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import com.omnidev.workspace.ui.assistant.AssistantActivity
import com.omnidev.workspace.ui.assistant.AssistantOverlay
import com.omnidev.workspace.ui.assistant.AssistantSettings
import com.omnidev.workspace.ui.theme.OmniDevTheme

class OmniVoiceSession(context: Context) : VoiceInteractionSession(context) {
    private val owner = SessionOwner()
    private val controller by lazy { AssistantController(context.applicationContext, WorkspaceChatRuntime.get(context)) }
    private val speech by lazy { AssistantSpeechInput(context, controller) }
    private var composition: ComposeView? = null

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
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        view.setContent {
            OmniDevTheme(dynamicColor = false) {
                AssistantOverlay(controller, onDismiss = ::hide, onExpand = {
                    controller.openConversation {
                        startAssistantActivity(Intent(context, MainActivity::class.java)
                            .putExtra("open_assistant_conversation", true))
                        hide()
                    }
                }, onSetup = {
                    startAssistantActivity(AssistantSettings.intent(context)); hide()
                }, onMicrophone = {
                    speech.toggle {
                        startAssistantActivity(Intent(context, AssistantActivity::class.java)
                            .putExtra(AssistantActivity.REQUEST_MICROPHONE, true))
                    }
                })
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        controller.show()
        owner.registry.currentState = Lifecycle.State.RESUMED
        composition?.createComposition()
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        // Delivered by Android from the foreground app, before the assistant covers it.
        controller.screenshot(screenshot)
    }

    override fun onHide() {
        speech.stop()
        controller.hide()
        composition?.disposeComposition()
        owner.registry.currentState = Lifecycle.State.CREATED
        super.onHide()
    }

    override fun onDestroy() {
        speech.stop()
        controller.destroy()
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
