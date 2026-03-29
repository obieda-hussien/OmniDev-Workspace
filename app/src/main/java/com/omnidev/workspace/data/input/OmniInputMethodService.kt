package com.omnidev.workspace.data.input

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class OmniInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "OmniIME"

        private val _textInputFlow = MutableSharedFlow<TextInputEvent>(extraBufferCapacity = 64)
        val textInputFlow: SharedFlow<TextInputEvent> = _textInputFlow.asSharedFlow()

        private val _isActive = MutableStateFlow(false)
        val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

        private var activeService: OmniInputMethodService? = null

        fun commitText(text: String): Boolean {
            val service = activeService ?: return false
            return try {
                val ic = service.currentInputConnection ?: return false
                ic.commitText(text, 1)
                Log.d(TAG, "Committed ${text.length} chars")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to commit text", e)
                false
            }
        }

        fun deleteSurrounding(beforeLength: Int = 1, afterLength: Int = 0): Boolean {
            val service = activeService ?: return false
            return try {
                service.currentInputConnection?.deleteSurroundingText(beforeLength, afterLength)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun getSelectedText(): String? {
            return activeService?.currentInputConnection?.getSelectedText(0)?.toString()
        }

        fun getTextBeforeCursor(length: Int = 100): String? {
            return activeService?.currentInputConnection?.getTextBeforeCursor(length, 0)?.toString()
        }

        /**
         * الإضافة الخارقة: دالة لتبديل الكيبورد وإرجاع التحكم للمستخدم
         * الوكيل ينادي عليها بعد ما يخلص كتابة عشان المستخدم ميعلقش في الكيبورد المخفي
         */
        fun switchToPreviousKeyboard(): Boolean {
            val service = activeService ?: return false
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    service.switchToPreviousInputMethod()
                } else {
                    service.switchToNextInputMethod(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch keyboard", e)
                false
            }
        }
    }

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
        // نتركه null لأننا لا نريد واجهة، ولكننا ضمنا دالة للهروب إذا علق المستخدم
        return null
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(TAG, "Input started — package: ${attribute?.packageName}")
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        // تحسين التقاط النص لتجنب الكراش إذا كان النص ضخماً
        if (newSelStart > oldSelStart) {
            try {
                val ic = currentInputConnection ?: return
                val length = newSelStart - oldSelStart
                // حماية إضافية: عدم التقاط نصوص ضخمة جداً دفعة واحدة لتجنب تجميد الخدمة
                if (length in 1..500) {
                    val newText = ic.getTextBeforeCursor(length, 0)?.toString()
                    if (!newText.isNullOrEmpty()) {
                        _textInputFlow.tryEmit(
                            TextInputEvent(
                                text = newText,
                                packageName = currentInputEditorInfo?.packageName
                            )
                        )
                    }
                }
            } catch (_: Exception) { }
        }
    }
}
