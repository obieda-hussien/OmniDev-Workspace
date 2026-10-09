package com.omnidev.workspace.data.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.delay

/** GodModeAccessibility combines Shizuku and accessibility with exponential-backoff retries and jitter, semantic element lookup, action chains, macro recording, scrolling to target text and post-action UI-change checks. */
object GodModeAccessibility {

    private const val TAG = "GodModeA11y"
    private const val SERVICE_CLASS =
        "com.omnidev.workspace/.data.accessibility.OmniAccessibilityService"

    // ── Retry Configuration ──────────────────────────────────────────────────
    private const val DEFAULT_MAX_RETRIES = 3
    private const val BASE_DELAY_MS = 200L
    private const val MAX_DELAY_MS = 2_000L
    private const val VERIFICATION_WAIT_MS = 600L
    private const val SCROLL_TO_MAX_ATTEMPTS = 10

    /** Recorded action sequences (macros). */
    private val actionRecordings = mutableMapOf<String, List<RecordedAction>>()

    @Volatile
    private var isRecording = false

    @Volatile
    private var currentRecording = mutableListOf<RecordedAction>()

    // ── Core Operations (Enhanced) ────────────────────────────────────────────

    suspend fun autoEnableOmniVision(): String {
        if (!PrivilegedExecutionManager.isShizukuReady()) {
            return "❌ Shizuku is unavailable or unauthorized."
        }
        if (AccessibilityStateManager.isServiceConnected.value) {
            return "✅ OmniAccessibilityService is already active."
        }

        return retryWithBackoff(maxRetries = 2, operationName = "auto_enable_accessibility") {
            val currentServices = PrivilegedExecutionManager.executeCommand(
                "settings get secure enabled_accessibility_services"
            ).fold(
                onSuccess = { it.trim().let { s -> if (s == "null" || s.isBlank()) "" else s } },
                onFailure = { "" }
            )

            val newServices = when {
                currentServices.contains(SERVICE_CLASS) -> currentServices
                currentServices.isEmpty() -> SERVICE_CLASS
                else -> "$currentServices:$SERVICE_CLASS"
            }

            PrivilegedExecutionManager.executeCommand(
                "settings put secure enabled_accessibility_services '$newServices'"
            ).getOrThrow()

            PrivilegedExecutionManager.executeCommand(
                "settings put secure accessibility_enabled 1"
            ).getOrThrow()

            "✅ OmniAccessibilityService was enabled through Shizuku. It should connect within seconds."
        }
    }

    /** Hybrid tap with effect verification and retry when the UI stays unchanged. */
    suspend fun hybridTap(node: AccessibilityNodeInfo, verifyChange: Boolean = true): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty || bounds.centerX() <= 0 || bounds.centerY() <= 0) {
            return "❌ Node has invalid bounds: $bounds"
        }

        val preTimestamp = AccessibilityStateManager.lastUpdateTime.value
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()

        val result = retryWithBackoff(
            maxRetries = DEFAULT_MAX_RETRIES,
            operationName = "hybrid_tap($centerX,$centerY)"
        ) {
            PrivilegedExecutionManager.executeCommand("input tap $centerX $centerY")
                .fold(
                    onSuccess = { "✅ Hardware tap at ($centerX, $centerY)" },
                    onFailure = { err ->
                        val fallback = OmniAccessibilityService.instance?.clickNode(node) == true
                        if (fallback) "⚠️ Fallback → Semantic tap succeeded"
                        else throw err
                    }
                )
        }

        if (verifyChange) {
            delay(VERIFICATION_WAIT_MS)
            val postTimestamp = AccessibilityStateManager.lastUpdateTime.value
            if (postTimestamp == preTimestamp) {
                Log.w(TAG, "Tap at ($centerX,$centerY) — The UI did not change!")
                return "$result\n⚠️ Warning: the UI did not change after the tap."
            }
        }

        if (isRecording) {
            currentRecording.add(RecordedAction.Tap(centerX, centerY))
        }

        return result
    }

    /** Hybrid long press with accessibility fallback. */
    suspend fun hybridLongPress(
        node: AccessibilityNodeInfo,
        durationMs: Int = 800
    ): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return "❌ Invalid node bounds: $bounds"

        val cx = bounds.centerX(); val cy = bounds.centerY()

        return retryWithBackoff(maxRetries = 2, operationName = "long_press($cx,$cy)") {
            PrivilegedExecutionManager.executeCommand("input swipe $cx $cy $cx $cy $durationMs")
                .fold(
                    onSuccess = { "✅ Hardware long-press at ($cx, $cy) for ${durationMs}ms" },
                    onFailure = { err ->
                        val fallback = OmniAccessibilityService.instance?.longClickNode(node) == true
                        if (fallback) "⚠️ Fallback → Semantic long-press succeeded"
                        else throw err
                    }
                )
        }.also {
            if (isRecording) currentRecording.add(RecordedAction.LongPress(cx, cy, durationMs))
        }
    }

    /** Hybrid text injection with escaping for special characters; supports Unicode, Arabic and emoji. */
    suspend fun hybridType(
        text: String,
        fallbackNode: AccessibilityNodeInfo? = null,
        clearFirst: Boolean = false
    ): String {
        if (text.isEmpty()) return "❌ Text is empty"
        if (fallbackNode?.isPassword == true ||
            com.omnidev.workspace.data.admin.DeviceAccessGuard.containsPassword(AccessibilityStateManager.rootNode.value))
            return "Protected input: user handoff required."
        OmniAccessibilityService.instance?.let { service ->
            com.omnidev.workspace.data.admin.DeviceAccessGuard.check(service, mutation = true)
        }?.let { return it }

        // Clear the field first when requested.
        if (clearFirst) {
            PrivilegedExecutionManager.executeCommand("input keyevent KEYCODE_CTRL_A")
            delay(100)
            PrivilegedExecutionManager.executeCommand("input keyevent KEYCODE_DEL")
            delay(100)
        }

        return retryWithBackoff(maxRetries = 2, operationName = "hybrid_type") {
            // Split long text into small chunks to avoid shell limits.
            if (text.length <= 200 && !containsSpecialChars(text)) {
                val escaped = text.replace("'", "'\\''").replace(" ", "%s")
                PrivilegedExecutionManager.executeCommand("input text '$escaped'")
                    .fold(
                        onSuccess = { "✅ Hardware text injection: ${text.take(50)}${if (text.length > 50) "..." else ""}" },
                        onFailure = { err -> injectViaAccessibility(text, fallbackNode) ?: throw err }
                    )
            } else {
                // Use the clipboard as a bridge for complex text.
                injectViaClipboard(text) ?: (injectViaAccessibility(text, fallbackNode) ?: "❌ Text injection failed")
            }
        }.also {
            if (isRecording) currentRecording.add(RecordedAction.TypeText(text))
        }
    }

    /** Scroll incrementally until target text appears, checking after each scroll up to SCROLL_TO_MAX_ATTEMPTS. targetText specifies the search text; scrollDirection is down or up. The result contains the node when found. */
    suspend fun scrollUntilVisible(
        targetText: String,
        scrollDirection: String = "down"
    ): ScrollToResult {
        val service = OmniAccessibilityService.instance
            ?: return ScrollToResult(false, "❌ Accessibility service is inactive")

        for (attempt in 1..SCROLL_TO_MAX_ATTEMPTS) {
            // Search first.
            val found = AccessibilityStateManager.findNodeByText(targetText)
            if (found != null) {
                return ScrollToResult(true, "✅ Found '$targetText' after $attempt scrolls", found)
            }

            // Scroll.
            val root = AccessibilityStateManager.rootNode.value ?: break
            val forward = scrollDirection != "up"
            val scrollable = findFirstScrollable(root)
            if (scrollable == null) {

                    // Use a gesture as the fallback.
                    val h = root.let { Rect().also { r -> it.getBoundsInScreen(r) }.height() }
                    val w = root.let { Rect().also { r -> it.getBoundsInScreen(r) }.width() }
                    val halfW = w / 2f
                    if (forward) {
                        service.swipeGesture(halfW, h * 0.7f, halfW, h * 0.3f, 400)
                    } else {
                        service.swipeGesture(halfW, h * 0.3f, halfW, h * 0.7f, 400)
                    }
                    delay(400)
                    continue
                }

            val scrolled = service.scrollNode(scrollable, forward)
            if (!scrolled) break
            delay(350)
        }

        return ScrollToResult(false, "❌ '$targetText' was not found after $SCROLL_TO_MAX_ATTEMPTS scrolls")
    }

    /** Execute a sequence of actions and stop on failure with a report of completed steps. */
    suspend fun executeActionChain(
        actions: List<ChainedAction>,
        stopOnFirstError: Boolean = true
    ): ActionChainResult {
        val results = mutableListOf<String>()
        var failedAt = -1

        for ((index, action) in actions.withIndex()) {
            val result = try {
                when (action) {
                    is ChainedAction.TapCoord -> {
                        PrivilegedExecutionManager.executeCommand(
                            "input tap ${action.x} ${action.y}"
                        ).fold({ "✅ Tap (${action.x}, ${action.y})" }, { throw it })
                    }
                    is ChainedAction.TypeText -> {
                        hybridType(action.text)
                    }
                    is ChainedAction.WaitMs -> {
                        delay(action.ms)
                        "✅ Waited ${action.ms}ms"
                    }
                    is ChainedAction.PressKey -> {
                        PrivilegedExecutionManager.executeCommand(
                            "input keyevent ${action.keycode}"
                        ).fold({ "✅ Key: ${action.keycode}" }, { throw it })
                    }
                    is ChainedAction.Swipe -> {
                        PrivilegedExecutionManager.executeCommand(
                            "input swipe ${action.x1} ${action.y1} ${action.x2} ${action.y2} ${action.durationMs}"
                        ).fold({ "✅ Swipe" }, { throw it })
                    }
                    is ChainedAction.WaitForText -> {
                        waitForText(action.text, action.timeoutMs)
                    }
                }
            } catch (e: Exception) {
                val errorMsg = "❌ Failed at step ${index + 1} (${action::class.simpleName}): ${e.message}"
                results.add(errorMsg)
                failedAt = index
                if (stopOnFirstError) break
                continue
            }
            results.add(result)
            if (action is ChainedAction.WaitMs) continue
            delay(150) // Natural delay between actions
        }

        return ActionChainResult(
            success = failedAt == -1,
            results = results,
            failedAtStep = failedAt,
            totalSteps = actions.size,
            completedSteps = if (failedAt == -1) actions.size else failedAt
        )
    }

    /** Start recording actions as a replayable macro. */
    fun startRecording(macroName: String) {
        currentRecording = mutableListOf()
        isRecording = true
        Log.i(TAG, "🎬 Started recording macro: $macroName")
    }

    /** Stop recording and save the macro; return the number of recorded actions. */
    fun stopRecording(macroName: String): Int {
        isRecording = false
        actionRecordings[macroName] = currentRecording.toList()
        val count = currentRecording.size
        currentRecording = mutableListOf()
        Log.i(TAG, "⏹️ Stopped recording '$macroName': $count actions")
        return count
    }

    /** Replay a previously recorded macro. */
    suspend fun playMacro(macroName: String): String {
        val actions = actionRecordings[macroName]
            ?: return "❌ Macro '$macroName' was not found. Available: ${actionRecordings.keys}"

        val chainedActions = actions.map { recorded ->
            when (recorded) {
                is RecordedAction.Tap -> ChainedAction.TapCoord(recorded.x, recorded.y)
                is RecordedAction.LongPress -> ChainedAction.Swipe(
                    recorded.x, recorded.y, recorded.x, recorded.y, recorded.durationMs.toLong()
                )
                is RecordedAction.TypeText -> ChainedAction.TypeText(recorded.text)
            }
        }

        val result = executeActionChain(chainedActions)
        return if (result.success) {
            "✅ Macro '$macroName' completed (${result.completedSteps}/${result.totalSteps} steps)"
        } else {
            "⚠️ Macro '$macroName' failed at step ${result.failedAtStep + 1}:\n${result.results.joinToString("\n")}"
        }
    }

    /** List saved macros with their details. */
    fun listMacros(): String {
        if (actionRecordings.isEmpty()) return "No macros have been saved."
        return actionRecordings.entries.joinToString("\n") { (name, actions) ->
            "📼 $name: ${actions.size} actions"
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    /** Exponential backoff with jitter for transient failures. */
    private suspend fun <T> retryWithBackoff(
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        operationName: String = "operation",
        block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    val delayMs = (BASE_DELAY_MS * (1L shl attempt) + (0..100).random())
                        .coerceAtMost(MAX_DELAY_MS)
                    Log.w(TAG, "$operationName: Attempt ${attempt + 1}/$maxRetries failed. Waiting ${delayMs}ms")
                    delay(delayMs)
                }
            }
        }
        throw lastException!!
    }

    private fun containsSpecialChars(text: String): Boolean =
        text.any { it.code > 127 || it == '\'' || it == '"' || it == '`' || it == '\\' }

    private suspend fun injectViaClipboard(text: String): String? {
        return try {
            // Copy text through Shizuku, then paste.
            PrivilegedExecutionManager.executeCommand(
                "am broadcast -a clipper.set -e text '${text.replace("'", "\\'")}'"
            )
            delay(200)
            PrivilegedExecutionManager.executeCommand("input keyevent KEYCODE_CTRL_V")
            "✅ Injected text through the clipboard"
        } catch (e: Exception) {
            null
        }
    }

    private fun injectViaAccessibility(
        text: String,
        fallbackNode: AccessibilityNodeInfo? = null
    ): String? {
        val service = OmniAccessibilityService.instance ?: return null
        return if (fallbackNode != null) {
            if (service.typeIntoNode(fallbackNode, text)) "⚠️ Fallback → Accessibility typing succeeded"
            else null
        } else {
            if (service.typeIntoFocusedNode(text)) "⚠️ Fallback → Accessibility focused typing succeeded"
            else null
        }
    }

    private suspend fun waitForText(text: String, timeoutMs: Long): String {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val found = AccessibilityStateManager.findNodeByText(text)
            if (found != null) return "✅ Text '$text' appeared within ${System.currentTimeMillis() - startTime}ms"
            delay(300)
        }
        return "⏱️ Timed out: '$text' did not appear within ${timeoutMs}ms"
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstScrollable(child)
            if (result != null) return result
        }
        return null
    }

    // ── Data Classes ──────────────────────────────────────────────────────────

    data class ScrollToResult(
        val found: Boolean,
        val message: String,
        val node: AccessibilityNodeInfo? = null
    )

    data class ActionChainResult(
        val success: Boolean,
        val results: List<String>,
        val failedAtStep: Int,
        val totalSteps: Int,
        val completedSteps: Int
    ) {
        override fun toString(): String = buildString {
            append(if (success) "✅ Chain succeeded" else "❌ Chain failed")
            append(" ($completedSteps/$totalSteps steps)\n")
            results.forEachIndexed { i, r -> append("${i + 1}. $r\n") }
        }
    }

    sealed class ChainedAction {
        data class TapCoord(val x: Int, val y: Int) : ChainedAction()
        data class TypeText(val text: String) : ChainedAction()
        data class WaitMs(val ms: Long) : ChainedAction()
        data class PressKey(val keycode: String) : ChainedAction()
        data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Long = 300) : ChainedAction()
        data class WaitForText(val text: String, val timeoutMs: Long = 5000) : ChainedAction()
    }

    sealed class RecordedAction {
        data class Tap(val x: Int, val y: Int) : RecordedAction()
        data class LongPress(val x: Int, val y: Int, val durationMs: Int) : RecordedAction()
        data class TypeText(val text: String) : RecordedAction()
    }
}
