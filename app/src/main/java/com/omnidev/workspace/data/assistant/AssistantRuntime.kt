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
    var openAccessCenter: (() -> Boolean)? = null
    var minimizeForAction: (suspend () -> Boolean)? = null
    suspend fun prepareAction(context: Context, tool: String, args: Map<String, String>): com.omnidev.workspace.data.tools.ToolExecutionResult? {
        if (tool !in setOf("semantic_ui", "ui_automation", "autofill_assist", "app_manager", "app_manager_tool", "ime_tool") ||
            AssistantActionPolicy.isReadOnly(tool, args)) return null
        if (!get(context).flavor.allowScreenActions) return com.omnidev.workspace.data.tools.ToolExecutionResult(
            "Live device actions are unavailable in this flavor.", isError = true, classification = "TIER_DENIED")
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            if (!get(context).state.value.visible) {
                if (targetingScreen && AssistantBubbleService.isReady(context)) null
                else {
                    restoreForConfirmation(context)
                    com.omnidev.workspace.data.tools.ToolExecutionResult(
                        "The assistant is hidden without an attached restoration bubble. No device action was executed.",
                        isError = true, classification = "ASSISTANT_WINDOW_UNAVAILABLE", retryable = false)
                }
            } else if (android.provider.Settings.canDrawOverlays(context) && minimizeForAction != null) {
                if (minimizeForAction?.invoke() == true) {
                    kotlinx.coroutines.delay(200)
                    if (get(context).state.value.visible || !AssistantBubbleService.isReady(context)) {
                        restoreForConfirmation(context)
                        com.omnidev.workspace.data.tools.ToolExecutionResult(
                            "The assistant was restored or its bubble detached before the action. No gesture was executed.",
                            isError = true, classification = "ASSISTANT_WINDOW_UNAVAILABLE", retryable = false)
                    } else null
                } else com.omnidev.workspace.data.tools.ToolExecutionResult(
                    "Could not minimize the assistant safely. No gesture was executed; retry after minimizing.", isError = true)
            } else if (tool == "ui_automation" || args["action"] in setOf("routine_click", "routine_long_click", "routine_scroll", "tap_xy", "swipe", "force_click", "force_long_click", "force_type", "chain", "macro_play", "back", "home", "recents")) {
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
        com.omnidev.workspace.data.voice.LocalWakeService.pauseCapture()
        controller.show()
        AssistantBubbleService.remove(context)
    }
    fun close(context: Context) {
        if (!com.omnidev.workspace.data.voice.LocalVoiceSessionService.handoff) com.omnidev.workspace.data.voice.LocalVoiceSessionService.stop(context)
        get(context).close()
        targetingScreen = false
        targetPackage = null
        AssistantBubbleService.remove(context)
    }
}
