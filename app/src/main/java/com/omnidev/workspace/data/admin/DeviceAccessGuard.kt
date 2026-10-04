package com.omnidev.workspace.data.admin

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.omnidev.workspace.data.accessibility.AccessibilityStateManager
import com.omnidev.workspace.data.assistant.AssistantRuntime
import com.omnidev.workspace.data.tools.ToolExecutionResult

/** Gates structured UI/capture paths independently of the flavor's confirmation gate. */
object DeviceAccessGuard {
    private val readers = setOf("dump_tree", "get_summary", "find_element", "get_text", "describe", "verify", "wait_for", "macro_list", "routine_wait")
    fun currentPackage(): String? = AccessibilityStateManager.activePackage.value ?: AssistantRuntime.targetPackage
    fun check(context: Context, pkg: String? = currentPackage(), screenshot: Boolean = false, mutation: Boolean = false): String? {
        if (LocalPinUnlock.entering) return "Local credential entry is in progress. Observation and other interaction are paused."
        if (pkg == context.packageName && AccessibilityStateManager.activeActivity.value?.let {
                it.endsWith("DeviceAccessActivity") || it.endsWith("DeviceUnlockActivity")
            } == true) return "USER_ACTION_REQUIRED: device consent and authentication screens are user-operated."
        val consent = DeviceConsentStore(context)
        consent.denial(pkg, screenshot, mutation)?.let { return it }
        if (screenshot && (pkg == null || containsPassword(AccessibilityStateManager.rootNode.value)))
            return "Screen capture unavailable while the foreground target is unknown or contains a protected input."
        if (mutation && pkg == "com.android.systemui" && containsPassword(AccessibilityStateManager.rootNode.value))
            return "USER_ACTION_REQUIRED: complete Android credential verification manually."
        return null
    }
    fun toolDenial(context: Context, name: String, args: Map<String, String>): ToolExecutionResult? {
        val screenshot = name in setOf("visual_inspector", "screenshot_tool", "ui_replica_pipeline")
        val ui = name in setOf("semantic_ui", "ui_automation", "autofill_assist", "ime_tool")
        if (!screenshot && !ui) return null
        // Raw XML is never an approved lock-screen observation path.
        if (name == "ui_automation" && DeviceConsentStore(context).locked())
            return ToolExecutionResult("Use consented semantic_ui inspection while locked; raw UI dumps and gestures are unavailable.", true)
        val mutation = ui && args["action"]?.lowercase() !in readers
        return check(context, screenshot = screenshot, mutation = mutation)?.let { ToolExecutionResult(it, true) }
    }
    fun containsPassword(root: AccessibilityNodeInfo?): Boolean {
        root ?: return false
        if (root.isPassword || root.viewIdResourceName?.substringAfterLast('/') in setOf("pinEntry", "passwordEntry")) return true
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val protected = try { containsPassword(child) } finally { child.recycle() }
            if (protected) return true
        }
        return false
    }
}
