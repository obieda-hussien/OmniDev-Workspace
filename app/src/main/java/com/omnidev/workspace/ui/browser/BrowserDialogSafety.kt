package com.omnidev.workspace.ui.browser

import android.app.Activity
import android.app.Dialog
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A browser callback can outlive the Activity/window that created its WebView.
 * All browser-owned dialogs must pass this gate instead of calling Dialog.show()
 * directly. A detached WebView, stopped Activity, missing token, or stale callback
 * is treated as a cancelled prompt, never as a process-fatal window operation.
 */
internal object BrowserDialogSafety {
    private const val TAG = "BrowserDialogSafety"

    internal fun eligible(
        isFinishing: Boolean,
        isDestroyed: Boolean,
        lifecycleResumed: Boolean,
        decorAttached: Boolean,
        hasWindowToken: Boolean,
        anchorAttached: Boolean
    ): Boolean = !isFinishing && !isDestroyed && lifecycleResumed &&
        decorAttached && hasWindowToken && anchorAttached

    fun isUsable(activity: Activity, anchor: View? = null): Boolean {
        val decor = runCatching { activity.window?.decorView }.getOrNull() ?: return false
        val lifecycleResumed = (activity as? LifecycleOwner)?.lifecycle
            ?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
        return eligible(
            isFinishing = activity.isFinishing,
            isDestroyed = activity.isDestroyed,
            lifecycleResumed = lifecycleResumed,
            decorAttached = decor.isAttachedToWindow,
            hasWindowToken = decor.windowToken != null,
            anchorAttached = anchor?.isAttachedToWindow ?: true
        )
    }

    /**
     * Returns true when the dialog was shown or safely queued on Main. If its
     * origin becomes stale before the queued show runs, [onUnavailable] is called.
     */
    fun show(
        activity: Activity,
        dialog: Dialog,
        anchor: View? = null,
        onUnavailable: () -> Unit = {}
    ): Boolean {
        val unavailableNotified = AtomicBoolean(false)
        fun reject() {
            if (!unavailableNotified.compareAndSet(false, true)) return
            runCatching { dialog.dismiss() }
            runCatching(onUnavailable).onFailure {
                Log.w(TAG, "Browser dialog cancellation callback failed", it)
            }
        }

        val show = Runnable {
            if (!isUsable(activity, anchor)) {
                reject()
                return@Runnable
            }
            try {
                dialog.show()
            } catch (badToken: WindowManager.BadTokenException) {
                Log.w(TAG, "Skipped browser dialog after its Activity window became invalid", badToken)
                reject()
            } catch (badState: IllegalStateException) {
                Log.w(TAG, "Skipped browser dialog after its window state changed", badState)
                reject()
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            show.run()
            return !unavailableNotified.get()
        }

        return try {
            activity.runOnUiThread(show)
            true
        } catch (dispatchFailure: RuntimeException) {
            Log.w(TAG, "Could not dispatch browser dialog to Main", dispatchFailure)
            reject()
            false
        }
    }

    fun dismiss(dialog: Dialog?) {
        if (dialog == null) return
        runCatching { dialog.dismiss() }
            .onFailure { Log.d(TAG, "Ignoring dismiss for an already-detached browser dialog", it) }
    }
}
