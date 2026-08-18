package com.omnidev.workspace.data.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.delay

/**
 * GodModeAccessibility — [Localized] [Localized] [Localized] (Shizuku + Accessibility)
 *
 * [Localized] [Localized]: [Localized] [Localized]
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **[Localized] [Localized] [Localized] (Adaptive Retry)**: [Localized] Exponential Backoff
 *    [Localized] Jitter [Localized] [Localized] [Localized] [Localized] [Localized].
 *
 * 2. **[Localized] [Localized] [Localized] [Localized] (Semantic Element Finder)**: [Localized] [Localized] [Localized]
 *    [Localized] / [Localized] / [Localized] viewId — [Localized] [Localized] [Localized] node_id [Localized].
 *
 * 3. **[Localized] [Localized] (Action Chain)**: [Localized] [Localized] [Localized] tap → type → tap
 *    [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
 *
 * 4. **[Localized] [Localized] (Action Recorder)**: [Localized] [Localized] [Localized] [Localized] [Localized]
 *    "[Localized]" [Localized] [Localized] [Localized].
 *
 * 5. **[Localized] [Localized] (Smart Scroll-To)**: [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
 *    [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
 *
 * 6. **[Localized] [Localized] [Localized] (Post-Action Verification)**: [Localized] [Localized] tap [Localized] [Localized]
 *    [Localized] [Localized] [Localized] — [Localized] [Localized] [Localized] [Localized] [Localized].
 */
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

    /** [Localized] [Localized] [Localized] ([Localized]) */
    private val actionRecordings = mutableMapOf<String, List<RecordedAction>>()

    @Volatile
    private var isRecording = false

    @Volatile
    private var currentRecording = mutableListOf<RecordedAction>()

    // ── Core Operations (Enhanced) ────────────────────────────────────────────

    suspend fun autoEnableOmniVision(): String {
        if (!PrivilegedExecutionManager.isShizukuReady()) {
            return "❌ Shizuku [Localized] [Localized] [Localized] [Localized] [Localized]."
        }
        if (AccessibilityStateManager.isServiceConnected.value) {
            return "✅ OmniAccessibilityService [Localized] [Localized]."
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

            "✅ OmniAccessibilityService [Localized] [Localized] [Localized] Shizuku. [Localized] [Localized] [Localized]."
        }
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     */
    suspend fun hybridTap(node: AccessibilityNodeInfo, verifyChange: Boolean = true): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty || bounds.centerX() <= 0 || bounds.centerY() <= 0) {
            return "❌ [Localized] [Localized] [Localized] [Localized] [Localized]: $bounds"
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
                        if (fallback) "⚠️ Fallback → Semantic tap [Localized]"
                        else throw err
                    }
                )
        }

        if (verifyChange) {
            delay(VERIFICATION_WAIT_MS)
            val postTimestamp = AccessibilityStateManager.lastUpdateTime.value
            if (postTimestamp == preTimestamp) {
                Log.w(TAG, "Tap at ($centerX,$centerY) — [Localized] [Localized] [Localized]!")
                return "$result\n⚠️ [Localized]: [Localized] [Localized] [Localized] [Localized] [Localized]."
            }
        }

        if (isRecording) {
            currentRecording.add(RecordedAction.Tap(centerX, centerY))
        }

        return result
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] fallback [Localized].
     */
    suspend fun hybridLongPress(
        node: AccessibilityNodeInfo,
        durationMs: Int = 800
    ): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return "❌ [Localized] [Localized] [Localized] [Localized]: $bounds"

        val cx = bounds.centerX(); val cy = bounds.centerY()

        return retryWithBackoff(maxRetries = 2, operationName = "long_press($cx,$cy)") {
            PrivilegedExecutionManager.executeCommand("input swipe $cx $cy $cx $cy $durationMs")
                .fold(
                    onSuccess = { "✅ Hardware long-press at ($cx, $cy) for ${durationMs}ms" },
                    onFailure = { err ->
                        val fallback = OmniAccessibilityService.instance?.longClickNode(node) == true
                        if (fallback) "⚠️ Fallback → Semantic long-press [Localized]"
                        else throw err
                    }
                )
        }.also {
            if (isRecording) currentRecording.add(RecordedAction.LongPress(cx, cy, durationMs))
        }
    }

    /**
     * [Localized] [Localized] [Localized] [Localized] escape [Localized] [Localized] [Localized].
     * [Localized] Unicode[Localized] [Localized] [Localized] [Localized] emojis.
     */
    suspend fun hybridType(
        text: String,
        fallbackNode: AccessibilityNodeInfo? = null,
        clearFirst: Boolean = false
    ): String {
        if (text.isEmpty()) return "❌ [Localized] [Localized]"

        // [Localized] [Localized] [Localized] [Localized] [Localized]
        if (clearFirst) {
            PrivilegedExecutionManager.executeCommand("input keyevent KEYCODE_CTRL_A")
            delay(100)
            PrivilegedExecutionManager.executeCommand("input keyevent KEYCODE_DEL")
            delay(100)
        }

        return retryWithBackoff(maxRetries = 2, operationName = "hybrid_type") {
            // [Localized] text [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] ([Localized] [Localized] [Localized] shell)
            if (text.length <= 200 && !containsSpecialChars(text)) {
                val escaped = text.replace("'", "'\\''").replace(" ", "%s")
                PrivilegedExecutionManager.executeCommand("input text '$escaped'")
                    .fold(
                        onSuccess = { "✅ Hardware text injection: ${text.take(50)}${if (text.length > 50) "..." else ""}" },
                        onFailure = { err -> injectViaAccessibility(text, fallbackNode) ?: throw err }
                    )
            } else {
                // [Localized] [Localized]: [Localized] clipboard [Localized] bridge
                injectViaClipboard(text) ?: (injectViaAccessibility(text, fallbackNode) ?: "❌ [Localized] [Localized] [Localized]")
            }
        }.also {
            if (isRecording) currentRecording.add(RecordedAction.TypeText(text))
        }
    }

    /**
     * [[Localized]] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] — [Localized] SCROLL_TO_MAX_ATTEMPTS [Localized].
     *
     * @param targetText [Localized] [Localized] [Localized] [Localized]
     * @param scrollDirection "down" [Localized] "up"
     * @return [Localized] node [Localized] [Localized] [Localized] null
     */
    suspend fun scrollUntilVisible(
        targetText: String,
        scrollDirection: String = "down"
    ): ScrollToResult {
        val service = OmniAccessibilityService.instance
            ?: return ScrollToResult(false, "❌ [Localized] [Localized] accessibility [Localized] [Localized]")

        for (attempt in 1..SCROLL_TO_MAX_ATTEMPTS) {
            // [Localized] [Localized]
            val found = AccessibilityStateManager.findNodeByText(targetText)
            if (found != null) {
                return ScrollToResult(true, "✅ [Localized] '$targetText' [Localized] $attempt [Localized]", found)
            }

            // [Localized]
            val root = AccessibilityStateManager.rootNode.value ?: break
            val forward = scrollDirection != "up"
            val scrollable = findFirstScrollable(root)
            if (scrollable == null) {

                    // [Localized] gesture [Localized] fallback
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

        return ScrollToResult(false, "❌ [Localized] [Localized] [Localized] '$targetText' [Localized] $SCROLL_TO_MAX_ATTEMPTS [Localized]")
    }

    /**
     * [[Localized]] [Localized] [Localized] [Localized] atomically.
     * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     */
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
                val errorMsg = "❌ [Localized] [Localized] [Localized] ${index + 1} (${action::class.simpleName}): ${e.message}"
                results.add(errorMsg)
                failedAt = index
                if (stopOnFirstError) break
                continue
            }
            results.add(result)
            if (action is ChainedAction.WaitMs) continue
            delay(150) // [Localized] [Localized] [Localized] [Localized]
        }

        return ActionChainResult(
            success = failedAt == -1,
            results = results,
            failedAtStep = failedAt,
            totalSteps = actions.size,
            completedSteps = if (failedAt == -1) actions.size else failedAt
        )
    }

    /**
     * [[Localized]] [Localized] [Localized] [Localized] [Localized] macro [Localized] [Localized].
     */
    fun startRecording(macroName: String) {
        currentRecording = mutableListOf()
        isRecording = true
        Log.i(TAG, "🎬 [Localized] [Localized] [Localized]: $macroName")
    }

    /**
     * [[Localized]] [Localized] [Localized] [Localized] [Localized].
     * @return [Localized] [Localized] [Localized]
     */
    fun stopRecording(macroName: String): Int {
        isRecording = false
        actionRecordings[macroName] = currentRecording.toList()
        val count = currentRecording.size
        currentRecording = mutableListOf()
        Log.i(TAG, "⏹️ [Localized] [Localized] '$macroName': $count [Localized]")
        return count
    }

    /**
     * [[Localized]] [Localized] [Localized] [Localized] [Localized].
     */
    suspend fun playMacro(macroName: String): String {
        val actions = actionRecordings[macroName]
            ?: return "❌ [Localized] '$macroName' [Localized] [Localized]. [Localized]: ${actionRecordings.keys}"

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
            "✅ [Localized] '$macroName' [Localized] [Localized] (${result.completedSteps}/${result.totalSteps} [Localized])"
        } else {
            "⚠️ [Localized] '$macroName' [Localized] [Localized] [Localized] ${result.failedAtStep + 1}:\n${result.results.joinToString("\n")}"
        }
    }

    /**
     * [[Localized]] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     */
    fun listMacros(): String {
        if (actionRecordings.isEmpty()) return "[Localized] [Localized] [Localized] [Localized]."
        return actionRecordings.entries.joinToString("\n") { (name, actions) ->
            "📼 $name: ${actions.size} [Localized]"
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    /**
     * Exponential backoff [Localized] Jitter [Localized] [Localized] [Localized] [Localized].
     */
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
                    Log.w(TAG, "$operationName: [Localized] ${attempt + 1}/$maxRetries [Localized]. [Localized] ${delayMs}ms")
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
            // [Localized] [Localized] [Localized] clipboard [Localized] Shizuku [Localized] paste
            PrivilegedExecutionManager.executeCommand(
                "am broadcast -a clipper.set -e text '${text.replace("'", "\\'")}'"
            )
            delay(200)
            PrivilegedExecutionManager.executeCommand("input keyevent KEYCODE_CTRL_V")
            "✅ [Localized] [Localized] [Localized] Clipboard"
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
            if (service.typeIntoNode(fallbackNode, text)) "⚠️ Fallback → Accessibility typing [Localized]"
            else null
        } else {
            if (service.typeIntoFocusedNode(text)) "⚠️ Fallback → Accessibility focused typing [Localized]"
            else null
        }
    }

    private suspend fun waitForText(text: String, timeoutMs: Long): String {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val found = AccessibilityStateManager.findNodeByText(text)
            if (found != null) return "✅ [Localized] [Localized] '$text' [Localized] ${System.currentTimeMillis() - startTime}ms"
            delay(300)
        }
        return "⏱️ [Localized] [Localized]: '$text' [Localized] [Localized] [Localized] ${timeoutMs}ms"
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
            append(if (success) "✅ [Localized] [Localized]" else "❌ [Localized] [Localized]")
            append(" ($completedSteps/$totalSteps [Localized])\n")
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
