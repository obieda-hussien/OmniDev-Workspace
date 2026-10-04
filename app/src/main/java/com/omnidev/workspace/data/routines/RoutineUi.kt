package com.omnidev.workspace.data.routines

import android.view.accessibility.AccessibilityNodeInfo
import com.omnidev.workspace.data.accessibility.OmniAccessibilityService
import com.omnidev.workspace.data.tools.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Resolves the current tree afresh for every action. No screen coordinates or stale N IDs. */
object RoutineUi {
    fun selector(node: AccessibilityNodeInfo): UiSelector? {
        if (node.isPassword || protected(node)) return null
        return UiSelector(node.packageName?.toString().orEmpty(),
            node.viewIdResourceName.orEmpty(),
            if (node.isEditable) "" else node.text?.toString().orEmpty().take(200),
            node.contentDescription?.toString().orEmpty().take(200), node.className?.toString().orEmpty())
            .takeIf { it.hasIdentity && it.packageName.isNotBlank() }
    }
    private fun protected(node: AccessibilityNodeInfo): Boolean {
        val identity = "${node.viewIdResourceName} ${node.contentDescription} ${if (android.os.Build.VERSION.SDK_INT >= 26) node.hintText else ""}"
        return node.isEditable && Regex("password|passwd|pin|otp|verification.?code|credit.?card|cvv|كلمة.?المرور|رمز.?التحقق", RegexOption.IGNORE_CASE).containsMatchIn(identity)
    }
    private fun candidates(root: AccessibilityNodeInfo, selector: UiSelector): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (++visited > 500 || depth > 35) return
            if (node.isVisibleToUser && node.isEnabled && !node.isPassword && !protected(node) &&
                node.packageName?.toString() == selector.packageName &&
                (selector.viewId.isBlank() || node.viewIdResourceName == selector.viewId) &&
                (selector.text.isBlank() || node.text?.toString() == selector.text) &&
                (selector.description.isBlank() || node.contentDescription?.toString() == selector.description) &&
                (selector.className.isBlank() || node.className?.toString() == selector.className)) {
                results += AccessibilityNodeInfo.obtain(node)
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { walk(child, depth + 1) } finally { child.recycle() }
            }
        }
        walk(root, 0)
        return results
    }
    suspend fun present(selector: UiSelector): Boolean = withContext(Dispatchers.Main) {
        val root = OmniAccessibilityService.instance?.routineRoot() ?: return@withContext false
        try {
            val found = candidates(root, selector)
            val unique = found.size == 1
            found.forEach { it.recycle() }
            unique
        } finally { root.recycle() }
    }
    suspend fun execute(action: String, args: Map<String, String>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val selector = runCatching { Json.decodeFromString<UiSelector>(args["selector"].orEmpty()) }.getOrNull()
            ?: return@withContext ToolExecutionResult("Invalid selector", true)
        if (!selector.hasIdentity || selector.packageName.isBlank()) return@withContext ToolExecutionResult("Selector requires package and identity", true)
        if (action == "routine_wait") {
            val timeout = (args["timeout_ms"]?.toLongOrNull() ?: 5_000).coerceIn(100, 15_000)
            val started = android.os.SystemClock.elapsedRealtime()
            while (android.os.SystemClock.elapsedRealtime() - started < timeout) {
                if (present(selector)) return@withContext ToolExecutionResult("Expected element is present")
                delay(150)
            }
            return@withContext ToolExecutionResult("Element not observed", true, classification = "SELECTOR_MISSING")
        }
        val service = OmniAccessibilityService.instance ?: return@withContext ToolExecutionResult("Accessibility disconnected", true)
        val timeout = (args["timeout_ms"]?.toLongOrNull() ?: 5_000).coerceIn(100, 15_000)
        val deadline = android.os.SystemClock.elapsedRealtime() + timeout
        var found: List<AccessibilityNodeInfo> = emptyList()
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val root = service.routineRoot()
            if (root != null) found = try { candidates(root, selector) } finally { root.recycle() }
            if (found.isNotEmpty()) break
            delay(150)
        }
        try {
            if (found.size != 1) return@withContext ToolExecutionResult("Expected one element; found ${found.size}. User/agent takeover required.", true,
                classification = if (found.isEmpty()) "SELECTOR_MISSING" else "SELECTOR_AMBIGUOUS")
            val node = found.single()
            val success = when (action) {
                "routine_click" -> service.clickNode(node)
                "routine_long_click" -> service.longClickNode(node)
                "routine_type" -> node.isEditable && service.typeIntoNode(node, args["text"].orEmpty())
                "routine_scroll" -> node.performAction(if (args["text"] == "up") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                else -> false
            }
            delay(250)
            if (success && action == "routine_type" && (!node.refresh() || node.text?.toString() != args["text"].orEmpty()))
                return@withContext ToolExecutionResult("Input read-back did not match; inspect manually", true, classification = "UI_VERIFICATION_FAILED")
            ToolExecutionResult(if (success) "Semantic action accepted" else "Semantic action was not accepted", !success,
                classification = if (success) "SUCCESS" else "UI_ACTION_FAILED")
        } finally { found.forEach { it.recycle() } }
    }
}
