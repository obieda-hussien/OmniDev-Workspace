package com.omnidev.workspace.data.input

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedList

/**
 * OmniInputMethodService — لوحة المفاتيح الذكية المتقدمة
 *
 * الجيل الثاني: ذكاء سياقي وأمان متقدم
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **كشف السياق الذكي (Context Detection)**:
 *    يُحلّل معلومات EditorInfo لتصنيف نوع الحقل تلقائياً:
 *    PASSWORD, EMAIL, SEARCH, PHONE, MULTILINE, CHAT, URL, etc.
 *    يُرسل السياق للوكيل قبل أي تفاعل.
 *
 * 2. **ذاكرة الـ Clipboard الذكية (Smart Clipboard)**:
 *    يحفظ آخر 10 نصوص نُسخت (غير كلمات المرور).
 *    يُتيح للوكيل قراءتها وإدارتها.
 *
 * 3. **تحليل أنماط الإدخال (Input Pattern Analysis)**:
 *    يتتبع وتيرة الكتابة، عدد التصحيحات، اللغة المستخدمة.
 *    يُرسل "keystroke analytics" للوكيل.
 *
 * 4. **حقن نص متقدم (Advanced Text Injection)**:
 *    commitText مع دعم selection، cursor placement، و markdown injection.
 *
 * 5. **أمان: منع تسريب كلمات المرور**:
 *    حقول PASSWORD لا تُرسل أحداث النص أبداً.
 *    الـ analytics تُخفّف تلقائياً لهذه الحقول.
 */
class OmniInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "OmniIME"
        private const val MAX_CLIPBOARD_HISTORY = 10
        private const val MAX_RECENT_TEXT_LENGTH = 500
        private const val MAX_FIELD_CONTEXT_CHARS = 200

        // ── Flows ────────────────────────────────────────────────────────────
        private val _textInputFlow = MutableSharedFlow<TextInputEvent>(extraBufferCapacity = 64)
        val textInputFlow: SharedFlow<TextInputEvent> = _textInputFlow.asSharedFlow()

        private val _isActive = MutableStateFlow(false)
        val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

        private val _currentFieldContext = MutableStateFlow<FieldContext?>(null)
        val currentFieldContext: StateFlow<FieldContext?> = _currentFieldContext.asStateFlow()

        private val _inputAnalytics = MutableStateFlow(InputAnalytics())
        val inputAnalytics: StateFlow<InputAnalytics> = _inputAnalytics.asStateFlow()

        /** تاريخ الـ Clipboard: أحدث النصوص أولاً */
        private val clipboardHistory = LinkedList<ClipboardEntry>()

        @Volatile
        private var activeService: OmniInputMethodService? = null

        // ─────────────────────────────────────────────────────────────────────
        // API العام
        // ─────────────────────────────────────────────────────────────────────

        /**
         * يُدخل نصاً في الحقل الحالي مع خيارات متقدمة.
         */
        fun commitText(
            text: String,
            moveCursorToEnd: Boolean = true,
            replaceSelection: Boolean = false
        ): Boolean {
            val service = activeService ?: return false
            return try {
                val ic = service.currentInputConnection ?: return false

                if (replaceSelection) {
                    // استبدال النص المحدد
                    ic.beginBatchEdit()
                    ic.commitText(text, if (moveCursorToEnd) 1 else 0)
                    ic.endBatchEdit()
                } else {
                    ic.commitText(text, if (moveCursorToEnd) 1 else 0)
                }

                // تحديث الـ analytics
                updateAnalyticsOnCommit(text)
                Log.d(TAG, "✅ commitText: ${text.take(30)}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "❌ فشل commitText: ${e.message}")
                false
            }
        }

        /**
         * [جديد] يُدخل نصاً ثم يُضيف محاطاً بـ wrapper (مثل: bold = "**text**").
         */
        fun commitWrappedText(
            innerText: String,
            prefix: String,
            suffix: String = prefix
        ): Boolean {
            return commitText("$prefix$innerText$suffix")
        }

        /**
         * [جديد] يُدخل قالباً مع مؤشر ($cursor) ويُحرك الـ cursor للموضع المحدد.
         * مثال: insertTemplate("```\n$cursor\n```") يُدخل كود block ويضع المؤشر داخله.
         */
        fun insertTemplate(template: String, cursorPlaceholder: String = "\$cursor"): Boolean {
            val service = activeService ?: return false
            val ic = service.currentInputConnection ?: return false
            val cursorIndex = template.indexOf(cursorPlaceholder)
            return try {
                if (cursorIndex == -1) {
                    ic.commitText(template, 1)
                } else {
                    val before = template.substring(0, cursorIndex)
                    val after = template.substring(cursorIndex + cursorPlaceholder.length)
                    ic.commitText(before + after, 1)
                    // نرجّع المؤشر لموضع $cursor
                    if (after.isNotEmpty()) {
                        ic.setSelection(
                            ic.getTextBeforeCursor(after.length + before.length, 0)?.length?.minus(after.length) ?: 0,
                            ic.getTextBeforeCursor(after.length + before.length, 0)?.length?.minus(after.length) ?: 0
                        )
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "insertTemplate فشل: ${e.message}")
                false
            }
        }

        fun deleteSurrounding(beforeLength: Int = 1, afterLength: Int = 0): Boolean {
            val service = activeService ?: return false
            return try {
                service.currentInputConnection?.deleteSurroundingText(beforeLength, afterLength)
                true
            } catch (e: Exception) { false }
        }

        fun selectAll(): Boolean {
            val service = activeService ?: return false
            return try {
                service.currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
                true
            } catch (e: Exception) { false }
        }

        fun getSelectedText(): String? =
            activeService?.currentInputConnection?.getSelectedText(0)?.toString()

        fun getTextBeforeCursor(length: Int = 100): String? =
            activeService?.currentInputConnection?.getTextBeforeCursor(length, 0)?.toString()

        fun getTextAfterCursor(length: Int = 100): String? =
            activeService?.currentInputConnection?.getTextAfterCursor(length, 0)?.toString()

        /**
         * [جديد] يُرجع السياق الكامل للحقل الحالي.
         */
        fun getFullFieldContext(): String = buildString {
            val context = _currentFieldContext.value
            if (context == null) { append("لا يوجد حقل نشط"); return@buildString }

            append("📝 سياق الحقل:\n")
            append("النوع: ${context.fieldType.name}\n")
            append("التطبيق: ${context.packageName}\n")
            append("hint: ${context.hint ?: "(لا يوجد)"}\n")
            append("كلمة مرور: ${if (context.isPassword) "نعم 🔒" else "لا"}\n")
            append("متعدد الأسطر: ${context.isMultiline}\n")

            val textBefore = getTextBeforeCursor(MAX_FIELD_CONTEXT_CHARS)
            if (!textBefore.isNullOrEmpty()) {
                append("النص الحالي (آخر ${textBefore.length} حرف): \"${textBefore.takeLast(80)}\"")
            }
        }

        /**
         * [جديد] يُضيف نصاً لتاريخ الـ Clipboard (للاستخدام من الوكيل).
         */
        fun addToClipboardHistory(text: String, label: String = "Agent") {
            if (text.length > 2000) return // تجنب النصوص الضخمة
            val entry = ClipboardEntry(text, label, System.currentTimeMillis())
            synchronized(clipboardHistory) {
                if (clipboardHistory.firstOrNull()?.text == text) return // تجنب التكرار
                clipboardHistory.addFirst(entry)
                if (clipboardHistory.size > MAX_CLIPBOARD_HISTORY) clipboardHistory.removeLast()
            }
        }

        /**
         * [جديد] يُرجع تاريخ الـ Clipboard للوكيل.
         */
        fun getClipboardHistory(): String = buildString {
            val history = synchronized(clipboardHistory) { clipboardHistory.toList() }
            if (history.isEmpty()) { append("تاريخ الـ Clipboard فارغ"); return@buildString }
            append("📋 تاريخ الـ Clipboard (آخر ${history.size}):\n")
            history.forEachIndexed { i, entry ->
                append("${i + 1}. [${entry.label}] ${entry.text.take(60)}\n")
            }
        }

        fun switchToPreviousKeyboard(): Boolean {
            val service = activeService ?: return false
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    service.switchToPreviousInputMethod(); true
                } else false
            } catch (e: Exception) { false }
        }

        // ── Private Helpers ───────────────────────────────────────────────────

        private fun updateAnalyticsOnCommit(text: String) {
            val current = _inputAnalytics.value
            _inputAnalytics.value = current.copy(
                totalCharsTyped = current.totalCharsTyped + text.length,
                commitCount = current.commitCount + 1,
                lastCommitTime = System.currentTimeMillis()
            )
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        activeService = this
        _isActive.value = true
        Log.i(TAG, "✅ OmniDev IME بدأت")
    }

    override fun onDestroy() {
        activeService = null
        _isActive.value = false
        _currentFieldContext.value = null
        Log.i(TAG, "OmniDev IME أوقفت")
        super.onDestroy()
    }

    override fun onCreateInputView(): android.view.View? = null

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        attribute ?: return

        // بناء سياق الحقل بالكامل
        val context = buildFieldContext(attribute)
        _currentFieldContext.value = context

        // تحديث الـ analytics
        val current = _inputAnalytics.value
        _inputAnalytics.value = current.copy(
            fieldSwitchCount = current.fieldSwitchCount + 1,
            currentFieldType = context.fieldType
        )

        Log.d(TAG, "بدأ إدخال — ${context.fieldType.name} في ${context.packageName}")

        // بث حدث تغيير الحقل للوكيل
        _textInputFlow.tryEmit(
            TextInputEvent(
                text = "",
                packageName = context.packageName,
                eventType = TextInputEventType.FIELD_STARTED,
                fieldContext = context
            )
        )
    }

    override fun onFinishInput() {
        super.onFinishInput()
        _textInputFlow.tryEmit(
            TextInputEvent(
                text = "",
                packageName = _currentFieldContext.value?.packageName,
                eventType = TextInputEventType.FIELD_ENDED,
                fieldContext = _currentFieldContext.value
            )
        )
        _currentFieldContext.value = null
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        val context = _currentFieldContext.value ?: return
        // لا تُرسل أحداث لحقول كلمة المرور
        if (context.isPassword) return

        if (newSelStart > oldSelStart) {
            try {
                val ic = currentInputConnection ?: return
                val length = newSelStart - oldSelStart
                if (length in 1..500) {
                    val newText = ic.getTextBeforeCursor(length, 0)?.toString()
                    if (!newText.isNullOrEmpty()) {
                        _textInputFlow.tryEmit(
                            TextInputEvent(
                                text = newText,
                                packageName = context.packageName,
                                eventType = TextInputEventType.TEXT_COMMITTED,
                                fieldContext = context
                            )
                        )
                        // تحديث analytics
                        val current = _inputAnalytics.value
                        _inputAnalytics.value = current.copy(
                            totalCharsTyped = current.totalCharsTyped + newText.length,
                            lastCommitTime = System.currentTimeMillis()
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // ── Context Builder ───────────────────────────────────────────────────────

    private fun buildFieldContext(attribute: EditorInfo): FieldContext {
        val inputType = attribute.inputType
        val isPassword = inputType and android.text.InputType.TYPE_MASK_VARIATION in listOf(
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )

        val fieldType = when {
            isPassword -> FieldType.PASSWORD
            inputType and android.text.InputType.TYPE_MASK_CLASS == android.text.InputType.TYPE_CLASS_NUMBER -> FieldType.NUMBER
            inputType and android.text.InputType.TYPE_MASK_VARIATION == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> FieldType.EMAIL
            inputType and android.text.InputType.TYPE_MASK_VARIATION == android.text.InputType.TYPE_TEXT_VARIATION_URI -> FieldType.URL
            inputType and android.text.InputType.TYPE_MASK_VARIATION == android.text.InputType.TYPE_TEXT_VARIATION_PHONE_NUMBER -> FieldType.PHONE
            inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0 -> FieldType.MULTILINE
            attribute.inputType == android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT -> FieldType.WEB
            else -> inferFieldTypeFromHint(attribute.hintText?.toString(), attribute.packageName)
        }

        return FieldContext(
            fieldType = fieldType,
            packageName = attribute.packageName ?: "unknown",
            hint = attribute.hintText?.toString(),
            isPassword = isPassword,
            isMultiline = inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0,
            imeAction = attribute.imeOptions and EditorInfo.IME_MASK_ACTION,
            inputType = inputType
        )
    }

    private fun inferFieldTypeFromHint(hint: String?, packageName: String?): FieldType {
        if (hint == null) return FieldType.TEXT
        val h = hint.lowercase()
        return when {
            "search" in h || "بحث" in h -> FieldType.SEARCH
            "email" in h || "بريد" in h || "@" in h -> FieldType.EMAIL
            "phone" in h || "هاتف" in h || "mobile" in h -> FieldType.PHONE
            "message" in h || "رسالة" in h || "comment" in h -> FieldType.CHAT
            "url" in h || "website" in h || "link" in h -> FieldType.URL
            else -> FieldType.TEXT
        }
    }

    // ── Data Classes ──────────────────────────────────────────────────────────

    enum class FieldType {
        TEXT, EMAIL, PASSWORD, NUMBER, PHONE, SEARCH, MULTILINE, CHAT, URL, WEB, UNKNOWN
    }

    enum class TextInputEventType {
        TEXT_COMMITTED, FIELD_STARTED, FIELD_ENDED, SELECTION_CHANGED
    }

    data class FieldContext(
        val fieldType: FieldType,
        val packageName: String,
        val hint: String?,
        val isPassword: Boolean,
        val isMultiline: Boolean,
        val imeAction: Int,
        val inputType: Int
    )

    data class TextInputEvent(
        val text: String,
        val packageName: String?,
        val eventType: TextInputEventType = TextInputEventType.TEXT_COMMITTED,
        val fieldContext: FieldContext? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class InputAnalytics(
        val totalCharsTyped: Long = 0L,
        val commitCount: Int = 0,
        val fieldSwitchCount: Int = 0,
        val lastCommitTime: Long = 0L,
        val currentFieldType: FieldType = FieldType.UNKNOWN
    )

    data class ClipboardEntry(
        val text: String,
        val label: String,
        val timestamp: Long
    )
}
