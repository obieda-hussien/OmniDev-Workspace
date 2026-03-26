package com.omnidev.workspace.data.input

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OmniDev Input Method Service (IME).
 *
 * Provides keyboard-level text interception for the AI agent. When the user
 * activates this IME in Settings → Languages & Input, the agent gains the
 * ability to:
 *  1. **Read** what the user is typing in any app (via [textInputFlow]).
 *  2. **Inject** text into the current input field (via [commitText]).
 *
 * This is a stub implementation — it does NOT render a visible keyboard.
 * The user should keep their regular keyboard as the primary IME and switch
 * to OmniDev IME only when needed for agent-assisted text entry.
 *
 * All text interception is gated behind the explicit user action of enabling
 * and switching to this IME; the app cannot activate it silently.
 */
class OmniInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "OmniIME"

        private val _textInputFlow = MutableSharedFlow<TextInputEvent>(extraBufferCapacity = 64)
        val textInputFlow: SharedFlow<TextInputEvent> = _textInputFlow.asSharedFlow()

        private val _isActive = MutableStateFlow(false)
        val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

        /**
         * Commits text to the currently focused input field.
         *
         * Must be called while this IME is the active keyboard; otherwise the
         * connection will be null and the call silently fails.
         */
        private var activeService: OmniInputMethodService? = null

        fun commitText(text: String): Boolean {
            val service = activeService ?: return false
            return try {
                val ic = service.currentInputConnection ?: return false
                ic.commitText(text, 1)
                Log.d(TAG, "Committed ${text.length} chars to input field")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to commit text", e)
                false
            }
        }

        /** Sends a delete (backspace) key event. */
        fun deleteSurrounding(beforeLength: Int = 1, afterLength: Int = 0): Boolean {
            val service = activeService ?: return false
            return try {
                val ic = service.currentInputConnection ?: return false
                ic.deleteSurroundingText(beforeLength, afterLength)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete text", e)
                false
            }
        }

        /** Returns the text currently selected or surrounding the cursor. */
        fun getSelectedText(): String? {
            val service = activeService ?: return null
            return try {
                val ic = service.currentInputConnection ?: return null
                ic.getSelectedText(0)?.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get selected text", e)
                null
            }
        }

        /** Returns text before the cursor. */
        fun getTextBeforeCursor(length: Int = 100): String? {
            val service = activeService ?: return null
            return try {
                val ic = service.currentInputConnection ?: return null
                ic.getTextBeforeCursor(length, 0)?.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get text before cursor", e)
                null
            }
        }
    }

    /** Event emitted when the user types or edits text. */
    data class TextInputEvent(
        val text: String,
        val packageName: String?,
        val timestamp: Long = System.currentTimeMillis()
    )

    override fun onCreate() {
        super.onCreate()
        activeService = this
        _isActive.value = true
        Log.i(TAG, "OmniDev IME created")
    }

    override fun onDestroy() {
        activeService = null
        _isActive.value = false
        Log.i(TAG, "OmniDev IME destroyed")
        super.onDestroy()
    }

    override fun onCreateInputView(): View? {
        // Stub IME — no visible keyboard view. The agent injects text
        // programmatically via commitText(). Users should keep their
        // regular keyboard for manual typing.
        return null
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(TAG, "Input started — package: ${attribute?.packageName}, field: ${attribute?.fieldName}")
    }

    override fun onFinishInput() {
        super.onFinishInput()
        Log.d(TAG, "Input finished")
    }

    /**
     * Called when the user commits text through the IME.
     * Captures the text and emits it via [textInputFlow].
     */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        // Capture text changes when cursor moves forward (new text entered)
        if (newSelStart > oldSelStart) {
            try {
                val ic = currentInputConnection ?: return
                val newText = ic.getTextBeforeCursor(newSelStart - oldSelStart, 0)?.toString()
                if (!newText.isNullOrEmpty()) {
                    _textInputFlow.tryEmit(
                        TextInputEvent(
                            text = newText,
                            packageName = currentInputEditorInfo?.packageName
                        )
                    )
                }
            } catch (_: Exception) {
                // Silently ignore — the input connection may be stale
            }
        }
    }
}
