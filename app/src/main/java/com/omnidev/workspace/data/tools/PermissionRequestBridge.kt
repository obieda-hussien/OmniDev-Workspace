package com.omnidev.workspace.data.tools

import android.app.Activity
import androidx.core.app.ActivityCompat
import java.lang.ref.WeakReference

/**
 * Keeps a weak reference to the foreground Activity so agent tools that are
 * constructed with applicationContext can still launch Android runtime-permission
 * dialogs correctly. No Activity is retained after destruction.
 */
object PermissionRequestBridge {
    @Volatile
    private var activityRef = WeakReference<Activity>(null)

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (activityRef.get() === activity) activityRef.clear()
    }

    fun foregroundActivity(): Activity? = activityRef.get()?.takeIf {
        !it.isFinishing && !it.isDestroyed
    }

    fun requestRuntimePermissions(permissions: Array<String>, requestCode: Int): Boolean {
        val activity = foregroundActivity() ?: return false
        if (permissions.isEmpty()) return true
        activity.runOnUiThread {
            ActivityCompat.requestPermissions(activity, permissions, requestCode)
        }
        return true
    }
}
