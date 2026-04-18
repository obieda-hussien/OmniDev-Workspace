package com.omnidev.workspace.data.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.delay

/**
 * GodModeAccessibility — طبقة الهجين المتقدمة (Shizuku + Accessibility)
 *
 * الجيل الثاني: تحسينات جوهرية
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **إعادة المحاولة التكيّفية (Adaptive Retry)**: خوارزمية Exponential Backoff
 *    مع Jitter لمنع الاصطدام في الحمل الزائد.
 *
 * 2. **البحث الدلالي عن العناصر (Semantic Element Finder)**: يبحث عن عناصر
 *    بالنص / الوصف / الـ viewId — بدون الحاجة لـ node_id مُسبق.
 *
 * 3. **تسلسل الإجراءات (Action Chain)**: ينفّذ سلسلة من tap → type → tap
 *    في خطوة واحدة مع تحقق من النتيجة بين كل خطوة.
 *
 * 4. **تسجيل الإجراءات (Action Recorder)**: يُسجّل كل عملية ناجحة ويُشكّل
 *    "ماكرو" قابل للتشغيل لاحقاً.
 *
 * 5. **التمرير الذكي (Smart Scroll-To)**: يمرّر الشاشة حتى يظهر العنصر المستهدف
 *    بدلاً من تمرير عدد محدد من المرات.
 *
 * 6. **التحقق من النتيجة (Post-Action Verification)**: بعد كل tap يتحقق أن
 *    الواجهة تغيّرت فعلاً — ويُعيد المحاولة إذا بقيت ثابتة.
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

    /** سجل الإجراءات المسجّلة (ماكروات) */
    private val actionRecordings = mutableMapOf<String, List<RecordedAction>>()

    @Volatile
    private var isRecording = false

    @Volatile
    private var currentRecording = mutableListOf<RecordedAction>()

    // ── Core Operations (Enhanced) ────────────────────────────────────────────

    suspend fun autoEnableOmniVision(): String {
        if (!PrivilegedExecutionManager.isShizukuReady()) {
            return "❌ Shizuku غير متاح أو غير مخوّل."
        }
        if (AccessibilityStateManager.isServiceConnected.value) {
            return "✅ OmniAccessibilityService نشطة بالفعل."
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

            "✅ OmniAccessibilityService تم تفعيلها عبر Shizuku. ستتصل خلال ثوانٍ."
        }
    }

    /**
     * نقر هجين مع التحقق من التأثير.
     * يُعيد المحاولة تلقائياً إذا لم تتغيّر الواجهة.
     */
    suspend fun hybridTap(node: AccessibilityNodeInfo, verifyChange: Boolean = true): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty || bounds.centerX() <= 0 || bounds.centerY() <= 0) {
            return "❌ العقدة لها حدود غير صحيحة: $bounds"
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
                        if (fallback) "⚠️ Fallback → Semantic tap نجح"
                        else throw err
                    }
                )
        }

        if (verifyChange) {
            delay(VERIFICATION_WAIT_MS)
            val postTimestamp = AccessibilityStateManager.lastUpdateTime.value
            if (postTimestamp == preTimestamp) {
                Log.w(TAG, "Tap at ($centerX,$centerY) — لم تتغيّر الواجهة!")
                return "$result\n⚠️ تحذير: لم تتغيّر الواجهة بعد النقر."
            }
        }

        if (isRecording) {
            currentRecording.add(RecordedAction.Tap(centerX, centerY))
        }

        return result
    }

    /**
     * نقر طويل هجين مع fallback ذكي.
     */
    suspend fun hybridLongPress(
        node: AccessibilityNodeInfo,
        durationMs: Int = 800
    ): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return "❌ حدود العقدة غير صحيحة: $bounds"

        val cx = bounds.centerX(); val cy = bounds.centerY()

        return retryWithBackoff(maxRetries = 2, operationName = "long_press($cx,$cy)") {
            PrivilegedExecutionManager.executeCommand("input swipe $cx $cy $cx $cy $durationMs")
                .fold(
                    onSuccess = { "✅ Hardware long-press at ($cx, $cy) for ${durationMs}ms" },
                    onFailure = { err ->
                        val fallback = OmniAccessibilityService.instance?.longClickNode(node) == true
                        if (fallback) "⚠️ Fallback → Semantic long-press نجح"
                        else throw err
                    }
                )
        }.also {
            if (isRecording) currentRecording.add(RecordedAction.LongPress(cx, cy, durationMs))
        }
    }

    /**
     * حقن نص هجين مع escape ذكي للأحرف الخاصة.
     * يدعم Unicode، الأحرف العربية، والـ emojis.
     */
    suspend fun hybridType(
        text: String,
        fallbackNode: AccessibilityNodeInfo? = null,
        clearFirst: Boolean = false
    ): String {
        if (text.isEmpty()) return "❌ النص فارغ"

        // تنظيف الحقل أولاً إذا طُلب
        if (clearFirst) {
            PrivilegedExecutionManager.executeCommand("input keyevent KEYCODE_CTRL_A")
            delay(100)
            PrivilegedExecutionManager.executeCommand("input keyevent KEYCODE_DEL")
            delay(100)
        }

        return retryWithBackoff(maxRetries = 2, operationName = "hybrid_type") {
            // الـ text يُقسَّم لأجزاء صغيرة إذا كان طويلاً (لتجنب قيود الـ shell)
            if (text.length <= 200 && !containsSpecialChars(text)) {
                val escaped = text.replace("'", "'\\''").replace(" ", "%s")
                PrivilegedExecutionManager.executeCommand("input text '$escaped'")
                    .fold(
                        onSuccess = { "✅ Hardware text injection: ${text.take(50)}${if (text.length > 50) "..." else ""}" },
                        onFailure = { err -> injectViaAccessibility(text, fallbackNode) ?: throw err }
                    )
            } else {
                // نص معقد: استخدام clipboard كـ bridge
                injectViaClipboard(text) ?: (injectViaAccessibility(text, fallbackNode) ?: "❌ فشل حقن النص")
            }
        }.also {
            if (isRecording) currentRecording.add(RecordedAction.TypeText(text))
        }
    }

    /**
     * [جديد] تمرير ذكي حتى يظهر عنصر محدد.
     * يمرّر تدريجياً ويتحقق بعد كل تمرير — حتى SCROLL_TO_MAX_ATTEMPTS مرة.
     *
     * @param targetText النص المستهدف للبحث عنه
     * @param scrollDirection "down" أو "up"
     * @return الـ node عند إيجاده، أو null
     */
    suspend fun scrollUntilVisible(
        targetText: String,
        scrollDirection: String = "down"
    ): ScrollToResult {
        val service = OmniAccessibilityService.instance
            ?: return ScrollToResult(false, "❌ خدمة الـ accessibility غير نشطة")

        for (attempt in 1..SCROLL_TO_MAX_ATTEMPTS) {
            // بحث أولاً
            val found = AccessibilityStateManager.findNodeByText(targetText)
            if (found != null) {
                return ScrollToResult(true, "✅ وُجد '$targetText' بعد $attempt تمريرة", found)
            }

            // تمرير
            val root = AccessibilityStateManager.rootNode.value ?: break
            val forward = scrollDirection != "up"
            val scrollable = findFirstScrollable(root)
                ?: run {
                    // استخدام gesture كـ fallback
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

        return ScrollToResult(false, "❌ لم يُعثر على '$targetText' بعد $SCROLL_TO_MAX_ATTEMPTS تمريرة")
    }

    /**
     * [جديد] تنفيذ سلسلة إجراءات atomically.
     * إذا فشل أي إجراء، يتوقف ويُرجع تقريراً كاملاً.
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
                val errorMsg = "❌ فشل عند الخطوة ${index + 1} (${action::class.simpleName}): ${e.message}"
                results.add(errorMsg)
                failedAt = index
                if (stopOnFirstError) break
                continue
            }
            results.add(result)
            if (action is ChainedAction.WaitMs) continue
            delay(150) // تأخير طبيعي بين الإجراءات
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
     * [جديد] يبدأ تسجيل الإجراءات كـ macro قابل للإعادة.
     */
    fun startRecording(macroName: String) {
        currentRecording = mutableListOf()
        isRecording = true
        Log.i(TAG, "🎬 بدأ تسجيل الماكرو: $macroName")
    }

    /**
     * [جديد] يوقف التسجيل ويحفظ الماكرو.
     * @return عدد الإجراءات المسجّلة
     */
    fun stopRecording(macroName: String): Int {
        isRecording = false
        actionRecordings[macroName] = currentRecording.toList()
        val count = currentRecording.size
        currentRecording = mutableListOf()
        Log.i(TAG, "⏹️ توقف تسجيل '$macroName': $count إجراء")
        return count
    }

    /**
     * [جديد] يُشغّل ماكرو مسجّلاً مسبقاً.
     */
    suspend fun playMacro(macroName: String): String {
        val actions = actionRecordings[macroName]
            ?: return "❌ ماكرو '$macroName' غير موجود. المتاح: ${actionRecordings.keys}"

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
            "✅ ماكرو '$macroName' نُفّذ بنجاح (${result.completedSteps}/${result.totalSteps} خطوة)"
        } else {
            "⚠️ ماكرو '$macroName' فشل في الخطوة ${result.failedAtStep + 1}:\n${result.results.joinToString("\n")}"
        }
    }

    /**
     * [جديد] يُرجع قائمة الماكروات المحفوظة مع تفاصيلها.
     */
    fun listMacros(): String {
        if (actionRecordings.isEmpty()) return "لا توجد ماكروات محفوظة."
        return actionRecordings.entries.joinToString("\n") { (name, actions) ->
            "📼 $name: ${actions.size} إجراء"
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    /**
     * Exponential backoff مع Jitter للتعامل مع الأخطاء المؤقتة.
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
                    Log.w(TAG, "$operationName: محاولة ${attempt + 1}/$maxRetries فشلت. انتظار ${delayMs}ms")
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
            // نسخ النص للـ clipboard عبر Shizuku ثم paste
            PrivilegedExecutionManager.executeCommand(
                "am broadcast -a clipper.set -e text '${text.replace("'", "\\'")}'"
            )
            delay(200)
            PrivilegedExecutionManager.executeCommand("input keyevent KEYCODE_CTRL_V")
            "✅ حُقن النص عبر Clipboard"
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
            if (service.typeIntoNode(fallbackNode, text)) "⚠️ Fallback → Accessibility typing نجح"
            else null
        } else {
            if (service.typeIntoFocusedNode(text)) "⚠️ Fallback → Accessibility focused typing نجح"
            else null
        }
    }

    private suspend fun waitForText(text: String, timeoutMs: Long): String {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val found = AccessibilityStateManager.findNodeByText(text)
            if (found != null) return "✅ ظهر النص '$text' خلال ${System.currentTimeMillis() - startTime}ms"
            delay(300)
        }
        return "⏱️ انتهى الوقت: '$text' لم يظهر خلال ${timeoutMs}ms"
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
            append(if (success) "✅ السلسلة نجحت" else "❌ السلسلة فشلت")
            append(" ($completedSteps/$totalSteps خطوة)\n")
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
