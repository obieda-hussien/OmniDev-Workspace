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
 * OmniInputMethodService — Context note Context note Context note Context note
 *
 * Context note Context note: Context note Context note Context note Context note
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. **Context note Context note Context note (Context Detection)**:
 *    Context note Context note EditorInfo Context note Context note Context note Context note:
 *    PASSWORD, EMAIL, SEARCH, PHONE, MULTILINE, CHAT, URL, etc.
 *    Context note Context note Context note Context note Context note Context note.
 *
 * 2. **Context note Context note Clipboard Context note (Smart Clipboard)**:
 *    Context note Context note 10 Context note Context note (Context note Context note Context note).
 *    Context note Context note Context note Context note.
 *
 * 3. **Context note Context note Context note (Input Pattern Analysis)**:
 *    Context note Context note Context note Context note Context note Context note Context note.
 *    Context note "keystroke analytics" Context note.
 *
 * 4. **Context note Context note Context note (Advanced Text Injection)**:
 *    commitText Context note Context note selectionContext note cursor placementContext note Context note markdown injection.
 *
 * 5. **Context note: Context note Context note Context note Context note**:
 *    Context note PASSWORD Context note Context note Context note Context note Context note.
 *    Context note analytics Context note Context note Context note Context note.
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

        /** Context note Context note Clipboard: Context note Context note Context note */
        private val clipboardHistory = LinkedList<ClipboardEntry>()

        @Volatile
        private var activeService: OmniInputMethodService? = null

        // ─────────────────────────────────────────────────────────────────────
        // API Context note
        // ─────────────────────────────────────────────────────────────────────

        /**
         * Context note Context note Context note Context note Context note Context note Context note Context note.
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
                    // Context note Context note Context note
                    ic.beginBatchEdit()
                    ic.commitText(text, if (moveCursorToEnd) 1 else 0)
                    ic.endBatchEdit()
                } else {
                    ic.commitText(text, if (moveCursorToEnd) 1 else 0)
                }

                // Context note Context note analytics
                updateAnalyticsOnCommit(text)
                Log.d(TAG, "✅ commitText: ${text.take(30)}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "❌ Info commitText: ${e.message}")
                false
            }
        }

        /**
         * [Context note] Context note Context note Context note Context note Context note Context note wrapper (Context note: bold = "**text**").
         */
        fun commitWrappedText(
            innerText: String,
            prefix: String,
            suffix: String = prefix
        ): Boolean {
            return commitText("$prefix$innerText$suffix")
        }

        /**
         * [Context note] Context note Context note Context note Context note ($cursor) Context note Context note cursor Context note Context note.
         * Context note: insertTemplate("```\n$cursor\n```") Context note Context note block Context note Context note Context note.
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
                    // Context note Context note Context note $cursor
                    if (after.isNotEmpty()) {
                        ic.setSelection(
                            ic.getTextBeforeCursor(after.length + before.length, 0)?.length?.minus(after.length) ?: 0,
                            ic.getTextBeforeCursor(after.length + before.length, 0)?.length?.minus(after.length) ?: 0
                        )
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "insertTemplate Info: ${e.message}")
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
         * [Context note] Context note Context note Context note Context note Context note.
         */
        fun getFullFieldContext(): String = buildString {
            val context = _currentFieldContext.value
            if (context == null) { append("Info Info Info Info"); return@buildString }

            append("📝 Info Info:\n")
            append("Info: ${context.fieldType.name}\n")
            append("Info: ${context.packageName}\n")
            append("hint: ${context.hint ?: "(Info Info)"}\n")
            append("Info Info: ${if (context.isPassword) "Info 🔒" else "Info"}\n")
            append("Info Info: ${context.isMultiline}\n")

            val textBefore = getTextBeforeCursor(MAX_FIELD_CONTEXT_CHARS)
            if (!textBefore.isNullOrEmpty()) {
                append("Info Info (Info ${textBefore.length} Info): \"${textBefore.takeLast(80)}\"")
            }
        }

        /**
         * [Context note] Context note Context note Context note Context note Clipboard (Context note Context note Context note).
         */
        fun addToClipboardHistory(text: String, label: String = "Agent") {
            if (text.length > 2000) return // Context note Context note Context note
            val entry = ClipboardEntry(text, label, System.currentTimeMillis())
            synchronized(clipboardHistory) {
                if (clipboardHistory.firstOrNull()?.text == text) return // Context note Context note
                clipboardHistory.addFirst(entry)
                if (clipboardHistory.size > MAX_CLIPBOARD_HISTORY) clipboardHistory.removeLast()
            }
        }

        /**
         * [Context note] Context note Context note Context note Clipboard Context note.
         */
        fun getClipboardHistory(): String = buildString {
            val history = synchronized(clipboardHistory) { clipboardHistory.toList() }
            if (history.isEmpty()) { append("Info Info Clipboard Info"); return@buildString }
            append("📋 Info Info Clipboard (Info ${history.size}):\n")
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
        Log.i(TAG, "✅ OmniDev IME Info")
    }

    override fun onDestroy() {
        activeService = null
        _isActive.value = false
        _currentFieldContext.value = null
        Log.i(TAG, "OmniDev IME Info")
        super.onDestroy()
    }

    override fun onCreateInputView(): android.view.View? = null

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        attribute ?: return

        // Context note Context note Context note Context note
        val context = buildFieldContext(attribute)
        _currentFieldContext.value = context

        // Context note Context note analytics
        val current = _inputAnalytics.value
        _inputAnalytics.value = current.copy(
            fieldSwitchCount = current.fieldSwitchCount + 1,
            currentFieldType = context.fieldType
        )

        Log.d(TAG, "Info Info — ${context.fieldType.name} Info ${context.packageName}")

        // Context note Context note Context note Context note Context note
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
        // Context note Context note Context note Context note Context note Context note
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
                        // Context note analytics
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
            "search" in h || "Info" in h -> FieldType.SEARCH
            "email" in h || "Info" in h || "@" in h -> FieldType.EMAIL
            "phone" in h || "Info" in h || "mobile" in h -> FieldType.PHONE
            "message" in h || "Info" in h || "comment" in h -> FieldType.CHAT
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
