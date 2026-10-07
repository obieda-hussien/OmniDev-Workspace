package com.omnidev.workspace.data.admin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.omnidev.workspace.data.accessibility.OmniAccessibilityService
import com.omnidev.workspace.data.voice.SpokenCredential
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

internal object CredentialInputGate {
    private val busy = AtomicBoolean(false)
    val entering get() = busy.get()
    fun acquire() = busy.compareAndSet(false, true)
    fun release() { busy.set(false) }
}

/** One explicit user-confirmed attempt against recognized genuine SystemUI controls. */
object LocalSpokenUnlock {
    private const val SYSTEM = "com.android.systemui"
    private fun allowed(context: Context) = DeviceConsentStore(context).let {
        it.locked() && it.enabled(DeviceConsentPolicy.Scope.UNLOCK) && it.enabled(DeviceConsentPolicy.Scope.VOICE_CREDENTIAL)
    }
    private fun root(service: OmniAccessibilityService) = SystemUiCredentialControls.root(service)
    private fun find(root: AccessibilityNodeInfo, id: String) = SystemUiCredentialControls.find(root, id)
    fun visibleKind(): SpokenCredential.Kind? {
        val service = OmniAccessibilityService.instance ?: return null
        val root = root(service) ?: return null
        try {
            for ((id, kind) in listOf("pinEntry" to SpokenCredential.Kind.PIN, "passwordEntry" to SpokenCredential.Kind.PASSWORD, "lockPatternView" to SpokenCredential.Kind.PATTERN)) {
                find(root, id)?.let { it.recycle(); return kind }
            }
            return null
        } finally { root.recycle() }
    }
    suspend fun attempt(context: Context, credential: SpokenCredential): Boolean = withContext(Dispatchers.Main.immediate) {
        if (!allowed(context) || !CredentialInputGate.acquire()) { credential.close(); return@withContext false }
        try {
            val service = OmniAccessibilityService.instance ?: return@withContext false
            val root = root(service) ?: return@withContext false
            val submitted = try {
                when (credential.kind) {
                    SpokenCredential.Kind.PIN -> {
                        val input = find(root, "pinEntry") ?: return@withContext false
                        val empty = SystemUiCredentialControls.emptyPin(input)
                        input.recycle()
                        if (!empty || credential.chars.size !in 4..16 || credential.chars.any { it !in '0'..'9' }) return@withContext false
                        if (!(0..9).all { find(root, "key$it")?.let { key -> val yes = key.isClickable; key.recycle(); yes } == true }) return@withContext false
                        for (digit in credential.chars) {
                            if (!allowed(context)) return@withContext false
                            val current = root(service) ?: return@withContext false
                            val key = find(current, "key$digit")
                            val success = try { key?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true } finally { key?.recycle(); current.recycle() }
                            if (!success) return@withContext false
                            delay(120)
                        }
                        if (!DeviceConsentStore(context).locked()) true
                        else if (allowed(context)) {
                            val current = root(service)
                            if (current == null) true // Await Android state after an auto-submit animation.
                            else {
                                val enter = find(current, "key_enter")
                                try { enter == null || enter.performAction(AccessibilityNodeInfo.ACTION_CLICK) } finally { enter?.recycle(); current.recycle() }
                            }
                        } else false
                    }
                    SpokenCredential.Kind.PASSWORD -> {
                        val input = find(root, "passwordEntry") ?: return@withContext false
                        try {
                            if (!input.isPassword || !input.isEditable || !input.text.isNullOrEmpty() || credential.chars.size !in 4..64 || !allowed(context)) return@withContext false
                            val text = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, String(credential.chars)) }
                            val set = try { input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text) } finally { text.clear() }
                            if (!set || !allowed(context)) return@withContext false
                            if (android.os.Build.VERSION.SDK_INT >= 30 && input.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id })
                                input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                            else find(root, "key_enter")?.let { try { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } finally { it.recycle() } } == true
                        } finally { input.recycle() }
                    }
                    SpokenCredential.Kind.PATTERN -> {
                        val view = find(root, "lockPatternView") ?: return@withContext false
                        val bounds = Rect()
                        try {
                            // AOSP LockPatternView inherits View's accessibility class name.
                            if (view.className?.toString() !in setOf("android.view.View", "com.android.internal.widget.LockPatternView")) return@withContext false
                            view.getBoundsInScreen(bounds)
                        } finally { view.recycle() }
                        if (credential.chars.size !in 4..9 || credential.chars.toSet().size != credential.chars.size || credential.chars.any { it !in '1'..'9' } ||
                            bounds.width() < 120 || bounds.height() < 120 || bounds.left < 0 || bounds.top < 0 || bounds.width().toFloat() / bounds.height() !in .8f..1.2f) return@withContext false
                        // Require an active SystemUI window and no other window covering the gesture.
                        val windows = service.windows
                        val target = windows.firstOrNull { w -> w.isActive && w.root?.let { n -> try { n.packageName?.toString() == SYSTEM } finally { n.recycle() } } == true }
                            ?: return@withContext false
                        if (windows.any { w -> val rectangle = Rect(); w.getBoundsInScreen(rectangle); w.layer > target.layer && Rect.intersects(bounds, rectangle) }) return@withContext false
                        if (!allowed(context)) return@withContext false
                        val path = Path()
                        credential.chars.forEachIndexed { index, digit ->
                            val cell = digit - '1'; val x = bounds.left + (cell % 3 + .5f) * bounds.width() / 3
                            val y = bounds.top + (cell / 3 + .5f) * bounds.height() / 3
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        val done = CompletableDeferred<Boolean>()
                        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, credential.chars.size * 140L)).build()
                        val sent = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription) { done.complete(true) }
                            override fun onCancelled(gestureDescription: GestureDescription) { done.complete(false) }
                        }, Handler(Looper.getMainLooper()))
                        sent && withTimeoutOrNull(2000) { done.await() } == true
                    }
                }
            } finally { root.recycle() }
            if (!submitted) return@withContext false
            repeat(25) {
                if (!DeviceConsentStore(context).locked()) return@withContext true
                delay(200)
            }
            false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { false }
        finally { credential.close(); CredentialInputGate.release() }
    }
}
