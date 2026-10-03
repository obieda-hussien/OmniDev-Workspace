package com.omnidev.workspace.data.assistant

import android.content.Context
import com.omnidev.workspace.WorkspaceChatRuntime

/** Process-owned state survives a file picker or minimized bubble, not a new invocation. */
object AssistantRuntime {
    private var instance: AssistantController? = null
    @Volatile var targetingScreen = false
        private set
    var targetPackage: String? = null
    var nativeHost = false
    suspend fun restoreForConfirmation(context: Context) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
        if (targetingScreen && !get(context).state.value.visible) OmniVoiceInteractionService.resume(context)
    }
    var minimizeForAction: (() -> Unit)? = null
    suspend fun prepareAction(context: Context, tool: String, args: Map<String, String>): com.omnidev.workspace.data.tools.ToolExecutionResult? {
        if (tool !in setOf("semantic_ui", "ui_automation", "autofill_assist", "app_manager", "ime_tool") ||
            AssistantActionPolicy.isReadOnly(tool, args) || !get(context).state.value.visible) return null
        if (!get(context).flavor.allowScreenActions) return com.omnidev.workspace.data.tools.ToolExecutionResult(
            "Live device actions are unavailable in this flavor.", isError = true, classification = "TIER_DENIED")
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            if (android.provider.Settings.canDrawOverlays(context) && minimizeForAction != null) {
                minimizeForAction?.invoke()
                kotlinx.coroutines.delay(200)
                null
            } else if (tool == "ui_automation" || args["action"] in setOf("tap_xy", "swipe", "force_click", "force_long_click", "force_type", "chain", "macro_play", "back", "home", "recents")) {
                com.omnidev.workspace.data.tools.ToolExecutionResult("Minimize the assistant with the minus button and allow display over other apps before gesture actions. Then retry.", isError = true)
            } else null
        }
    }
    @Synchronized fun get(context: Context): AssistantController = instance ?: AssistantController(
        context.applicationContext, WorkspaceChatRuntime.assistant(context)).also { instance = it }
    fun begin(context: Context, resume: Boolean, native: Boolean = false) {
        nativeHost = native
        val controller = get(context)
        if (!resume) { controller.close(); targetPackage = null }
        targetingScreen = true
        controller.show()
        AssistantBubbleService.remove(context)
    }
    fun close(context: Context) {
        get(context).close()
        targetingScreen = false
        targetPackage = null
        AssistantBubbleService.remove(context)
    }
}
