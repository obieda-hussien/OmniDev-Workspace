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
 * OmniInputMethodService — [Localized] [Localized] [Localized] [Localized]
 *
 * [Localized] [Localized]: [Localized] [Localized] [Localized] [Localized]
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **[Localized] [Localized] [Localized] (Context Detection)**:
 *    [Localized] [Localized] EditorInfo [Localized] [Localized] [Localized] [Localized]:
 *    PASSWORD, EMAIL, SEARCH, PHONE, MULTILINE, CHAT, URL, etc.
 *    [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
 *
 * 2. **[Localized] [Localized] Clipboard [Localized] (Smart Clipboard)**:
 *    [Localized] [Localized] 10 [Localized] [Localized] ([Localized] [Localized] [Localized]).
 *    [Localized] [Localized] [Localized] [Localized].
 *
 * 3. **[Localized] [Localized] [Localized] (Input Pattern Analysis)**:
 *    [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
 *    [Localized] "keystroke analytics" [Localized].
 *
 * 4. **[Localized] [Localized] [Localized] (Advanced Text Injection)**:
 *    commitText [Localized] [Localized] selection[Localized] cursor placement[Localized] [Localized] markdown injection.
 *
 * 5. **[Localized]: [Localized] [Localized] [Localized] [Localized]**:
 *    [Localized] PASSWORD [Localized] [Localized] [Localized] [Localized] [Localized].
 *    [Localized] analytics [Localized] [Localized] [Localized] [Localized].
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

        /** [Localized] [Localized] Clipboard: [Localized] [Localized] [Localized] */
        private val clipboardHistory = LinkedList<ClipboardEntry>()

        @Volatile
        private var activeService: OmniInputMethodService? = null

        // ─────────────────────────────────────────────────────────────────────
        // API [Localized]
        // ─────────────────────────────────────────────────────────────────────

        /**
         * [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
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
                    // [Localized] [Localized] [Localized]
                    ic.beginBatchEdit()
                    ic.commitText(text, if (moveCursorToEnd) 1 else 0)
                    ic.endBatchEdit()
                } else {
                    ic.commitText(text, if (moveCursorToEnd) 1 else 0)
                }

                // [Localized] [Localized] analytics
                updateAnalyticsOnCommit(text)
                Log.d(TAG, "✅ commitText: ${text.take(30)}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "❌ [Localized] commitText: ${e.message}")
                false
            }
        }

        /**
         * [[Localized]] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] wrapper ([Localized]: bold = "**text**").
         */
        fun commitWrappedText(
            innerText: String,
            prefix: String,
            suffix: String = prefix
        ): Boolean {
            return commitText("$prefix$innerText$suffix")
        }

        /**
         * [[Localized]] [Localized] [Localized] [Localized] [Localized] ($cursor) [Localized] [Localized] cursor [Localized] [Localized].
         * [Localized]: insertTemplate("```\n$cursor\n```") [Localized] [Localized] block [Localized] [Localized] [Localized].
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
                    // [Localized] [Localized] [Localized] $cursor
                    if (after.isNotEmpty()) {
                        ic.setSelection(
                            ic.getTextBeforeCursor(after.length + before.length, 0)?.length?.minus(after.length) ?: 0,
                            ic.getTextBeforeCursor(after.length + before.length, 0)?.length?.minus(after.length) ?: 0
                        )
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "insertTemplate [Localized]: ${e.message}")
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
         * [[Localized]] [Localized] [Localized] [Localized] [Localized] [Localized].
         */
        fun getFullFieldContext(): String = buildString {
            val context = _currentFieldContext.value
            if (context == null) { append("[Localized] [Localized] [Localized] [Localized]"); return@buildString }

            append("📝 [Localized] [Localized]:\n")
            append("[Localized]: ${context.fieldType.name}\n")
            append("[Localized]: ${context.packageName}\n")
            append("hint: ${context.hint ?: "([Localized] [Localized])"}\n")
            append("[Localized] [Localized]: ${if (context.isPassword) "[Localized] 🔒" else "[Localized]"}\n")
            append("[Localized] [Localized]: ${context.isMultiline}\n")

            val textBefore = getTextBeforeCursor(MAX_FIELD_CONTEXT_CHARS)
            if (!textBefore.isNullOrEmpty()) {
                append("[Localized] [Localized] ([Localized] ${textBefore.length} [Localized]): \"${textBefore.takeLast(80)}\"")
            }
        }

        /**
         * [[Localized]] [Localized] [Localized] [Localized] [Localized] Clipboard ([Localized] [Localized] [Localized]).
         */
        fun addToClipboardHistory(text: String, label: String = "Agent") {
            if (text.length > 2000) return // [Localized] [Localized] [Localized]
            val entry = ClipboardEntry(text, label, System.currentTimeMillis())
            synchronized(clipboardHistory) {
                if (clipboardHistory.firstOrNull()?.text == text) return // [Localized] [Localized]
                clipboardHistory.addFirst(entry)
                if (clipboardHistory.size > MAX_CLIPBOARD_HISTORY) clipboardHistory.removeLast()
            }
        }

        /**
         * [[Localized]] [Localized] [Localized] [Localized] Clipboard [Localized].
         */
        fun getClipboardHistory(): String = buildString {
            val history = synchronized(clipboardHistory) { clipboardHistory.toList() }
            if (history.isEmpty()) { append("[Localized] [Localized] Clipboard [Localized]"); return@buildString }
            append("📋 [Localized] [Localized] Clipboard ([Localized] ${history.size}):\n")
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
        Log.i(TAG, "✅ OmniDev IME [Localized]")
    }

    override fun onDestroy() {
        activeService = null
        _isActive.value = false
        _currentFieldContext.value = null
        Log.i(TAG, "OmniDev IME [Localized]")
        super.onDestroy()
    }

    override fun onCreateInputView(): android.view.View? = null

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        attribute ?: return

        // [Localized] [Localized] [Localized] [Localized]
        val context = buildFieldContext(attribute)
        _currentFieldContext.value = context

        // [Localized] [Localized] analytics
        val current = _inputAnalytics.value
        _inputAnalytics.value = current.copy(
            fieldSwitchCount = current.fieldSwitchCount + 1,
            currentFieldType = context.fieldType
        )

        Log.d(TAG, "[Localized] [Localized] — ${context.fieldType.name} [Localized] ${context.packageName}")

        // [Localized] [Localized] [Localized] [Localized] [Localized]
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
        // [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]
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
                        // [Localized] analytics
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
            inputType and android.text.InputType.TYPE_MASK_CLASS == android.text.InputType.TYPE_CLASS_PHONE -> FieldType.PHONE
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
            "search" in h || "[Localized]" in h -> FieldType.SEARCH
            "email" in h || "[Localized]" in h || "@" in h -> FieldType.EMAIL
            "phone" in h || "[Localized]" in h || "mobile" in h -> FieldType.PHONE
            "message" in h || "[Localized]" in h || "comment" in h -> FieldType.CHAT
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
