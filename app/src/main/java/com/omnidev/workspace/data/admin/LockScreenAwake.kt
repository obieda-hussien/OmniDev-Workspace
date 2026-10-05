package com.omnidev.workspace.data.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Window
import android.view.WindowManager
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

/** Bounded display hold survives a handoff to SystemUI; it never wakes or unlocks on its own. */
object LockScreenAwake {
    private val deadline = ScreenAwakeDeadline()
    private val handler = Handler(Looper.getMainLooper())
    private var display: PowerManager.WakeLock? = null
    private var app: Context? = null
    private val windows = mutableListOf<WeakReference<Window>>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { release() }
    }
    private val poll = object : Runnable {
        override fun run() {
            val context = app ?: return
            val consent = DeviceConsentStore(context)
            if (remaining() == 0L || !consent.locked() || !allowed(consent)) { release(); return }
            handler.postDelayed(this, 250)
        }
    }
    private fun allowed(consent: DeviceConsentStore) = consent.enabled(DeviceConsentPolicy.Scope.WAKE) &&
        (consent.enabled(DeviceConsentPolicy.Scope.LOCK_OVERLAY) || consent.enabled(DeviceConsentPolicy.Scope.UNLOCK))
    private fun remaining() = deadline.remaining(SystemClock.elapsedRealtime())

    fun hold(context: Context, newInvocation: Boolean = false) = hold(context, if (newInvocation)
        ScreenAwakeDeadline.Origin.ASSISTANT_INVOCATION else ScreenAwakeDeadline.Origin.HOST_HANDOFF)

    /** Called once at the request boundary, never from Activity creation/recreation. */
    fun holdForRequest(context: Context, assistantHandoff: Boolean) = hold(context, if (assistantHandoff)
        ScreenAwakeDeadline.Origin.HOST_HANDOFF else ScreenAwakeDeadline.Origin.STANDALONE_REQUEST)

    @Suppress("DEPRECATION")
    private fun hold(context: Context, origin: ScreenAwakeDeadline.Origin) {
        val consent = DeviceConsentStore(context)
        if (!consent.locked() || !allowed(consent)) { release(); return }
        deadline.start(SystemClock.elapsedRealtime(), origin)
        val duration = remaining()
        if (duration == 0L) return
        if (app == null) {
            app = context.applicationContext
            ContextCompat.registerReceiver(app!!, receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        // A window flag alone ends when SystemUI covers the assistant. A bounded display
        // lock bridges that handoff. No ACQUIRE_CAUSES_WAKEUP or ON_AFTER_RELEASE flags.
        if (display?.isHeld != true || origin != ScreenAwakeDeadline.Origin.HOST_HANDOFF) runCatching {
            display?.let { if (it.isHeld) it.release() }
            val power = context.getSystemService(PowerManager::class.java)
            if (power?.isWakeLockLevelSupported(PowerManager.SCREEN_BRIGHT_WAKE_LOCK) == true) {
                display = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "OmniDev:LockScreenAwake").apply {
                    setReferenceCounted(false); acquire(duration)
                }
            }
        }
        windows.removeAll { it.get() == null }
        windows.forEach { it.get()?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    fun attach(window: Window) {
        windows.removeAll { it.get() == null || it.get() === window }
        windows += WeakReference(window)
        if (app != null && remaining() > 0) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    fun detach(window: Window) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        windows.removeAll { it.get() == null || it.get() === window }
    }
    fun release() {
        deadline.cancel()
        handler.removeCallbacks(poll)
        display?.let { runCatching { if (it.isHeld) it.release() } }; display = null
        windows.forEach { it.get()?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        app?.let { runCatching { it.unregisterReceiver(receiver) } }; app = null
    }
}
