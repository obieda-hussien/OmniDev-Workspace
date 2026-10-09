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

/** OmniInputMethodService classifies EditorInfo fields (password, email, search, phone, multiline, chat and URL), maintains up to 10 clipboard entries, tracks input patterns and supports selection-aware text injection and templates. Password fields suppress text events and reduce analytics to avoid disclosure. */
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

        /** Clipboard history, newest entries first. */
        private val clipboardHistory = LinkedList<ClipboardEntry>()

        @Volatile
        private var activeService: OmniInputMethodService? = null

        // ─────────────────────────────────────────────────────────────────────
        // Public API.
        // ─────────────────────────────────────────────────────────────────────

        /** Insert text into the current field with selection and cursor options. */
        fun commitText(
            text: String,
            moveCursorToEnd: Boolean = true,
            replaceSelection: Boolean = false
        ): Boolean {
            val service = activeService ?: return false
            return try {
                val ic = service.currentInputConnection ?: return false

                if (replaceSelection) {
                    // Replace selected text.
                    ic.beginBatchEdit()
                    ic.commitText(text, if (moveCursorToEnd) 1 else 0)
                    ic.endBatchEdit()
                } else {
                    ic.commitText(text, if (moveCursorToEnd) 1 else 0)
                }

                // Update analytics.
                updateAnalyticsOnCommit(text)
                Log.d(TAG, "✅ commitText: ${text.take(30)}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "❌ commitText failed: ${e.message}")
                false
            }
        }

        /** Insert wrapped text, for example **text** for bold formatting. */
        fun commitWrappedText(
            innerText: String,
            prefix: String,
            suffix: String = prefix
        ): Boolean {
            return commitText("$prefix$innerText$suffix")
        }

        /** Insert a template containing a $cursor marker and move the caret to that marker, for example inside a fenced code block. */
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
                    // Move the caret back to the $cursor marker.
                    if (after.isNotEmpty()) {
                        ic.setSelection(
                            ic.getTextBeforeCursor(after.length + before.length, 0)?.length?.minus(after.length) ?: 0,
                            ic.getTextBeforeCursor(after.length + before.length, 0)?.length?.minus(after.length) ?: 0
                        )
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "insertTemplate failed: ${e.message}")
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

        /** Return the complete context of the current input field. */
        fun getFullFieldContext(): String = buildString {
            val context = _currentFieldContext.value
            if (context == null) { append("No active input field"); return@buildString }

            append("📝 Field context:\n")
            append("Type: ${context.fieldType.name}\n")
            append("App: ${context.packageName}\n")
            append("hint: ${context.hint ?: "(none)"}\n")
            append("Password: ${if (context.isPassword) "yes 🔒" else "no"}\n")
            append("Multiline: ${context.isMultiline}\n")

            val textBefore = getTextBeforeCursor(MAX_FIELD_CONTEXT_CHARS)
            if (!textBefore.isNullOrEmpty()) {
                append("Current text (last ${textBefore.length} characters): \"${textBefore.takeLast(80)}\"")
            }
        }

        /** Add text to clipboard history for agent use. */
        fun addToClipboardHistory(text: String, label: String = "Agent") {
            if (text.length > 2000) return // Avoid oversized text entries.
            val entry = ClipboardEntry(text, label, System.currentTimeMillis())
            synchronized(clipboardHistory) {
                if (clipboardHistory.firstOrNull()?.text == text) return // Avoid duplicate entries.
                clipboardHistory.addFirst(entry)
                if (clipboardHistory.size > MAX_CLIPBOARD_HISTORY) clipboardHistory.removeLast()
            }
        }

        /** Return clipboard history to the agent. */
        fun getClipboardHistory(): String = buildString {
            val history = synchronized(clipboardHistory) { clipboardHistory.toList() }
            if (history.isEmpty()) { append("Clipboard history is empty"); return@buildString }
            append("📋 Clipboard history (last ${history.size}):\n")
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
        Log.i(TAG, "✅ OmniDev IME started")
    }

    override fun onDestroy() {
        activeService = null
        _isActive.value = false
        _currentFieldContext.value = null
        Log.i(TAG, "OmniDev IME stopped")
        super.onDestroy()
    }

    override fun onCreateInputView(): android.view.View? = null

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        attribute ?: return

        // Build complete field context.
        val context = buildFieldContext(attribute)
        _currentFieldContext.value = context

        // Update analytics.
        val current = _inputAnalytics.value
        _inputAnalytics.value = current.copy(
            fieldSwitchCount = current.fieldSwitchCount + 1,
            currentFieldType = context.fieldType
        )

        Log.d(TAG, "Input started: ${context.fieldType.name} in ${context.packageName}")

        // Emit a field-change event for the agent.
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
        // Do not send events for password fields.
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
                        // Update analytics.
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
