package com.omnidev.workspace.data.ipc

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.omnidev.launcher.ipc.IOmniLauncherInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages dynamic binding to the current default launcher implementing OmniDev launcher IPC.
 */
object LauncherConnectionManager {
    private const val TAG = "LauncherConnectionMgr"
    private const val ACTION_CONTROL_LAUNCHER = "com.omnidev.action.CONTROL_LAUNCHER"

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var boundPackage: String? = null
    @Volatile
    private var launcherInterface: IOmniLauncherInterface? = null

    private val initialized = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            launcherInterface = IOmniLauncherInterface.Stub.asInterface(service)
            Log.i(TAG, "Connected to launcher IPC service: ${name?.flattenToShortString()}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            launcherInterface = null
            Log.w(TAG, "Launcher IPC disconnected: ${name?.flattenToShortString()}")
        }

        override fun onNullBinding(name: ComponentName?) {
            launcherInterface = null
            Log.w(TAG, "Launcher IPC null binding: ${name?.flattenToShortString()}")
        }

        override fun onBindingDied(name: ComponentName?) {
            launcherInterface = null
            Log.w(TAG, "Launcher IPC binding died: ${name?.flattenToShortString()}")
            rebindToCurrentDefaultLauncher()
        }
    }

    private val launcherChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            Log.i(TAG, "Launcher/default-home change detected: ${intent?.action}")
            rebindToCurrentDefaultLauncher()
        }
    }

    fun initialize(context: Context) {
        val localContext = context.applicationContext
        appContext = localContext
        if (initialized.compareAndSet(false, true)) {
            registerLauncherChangeReceiver(localContext)
        }
        rebindToCurrentDefaultLauncher()
    }

    fun getLauncherInterface(): IOmniLauncherInterface? = launcherInterface

    fun renderOmniWidget(widgetId: String, composeJson: String): Boolean {
        val launcher = launcherInterface ?: return false
        return runCatching { launcher.renderOmniWidget(widgetId, composeJson) }.getOrElse {
            Log.w(TAG, "renderOmniWidget failed for widgetId=$widgetId", it)
            false
        }
    }

    fun rebindToCurrentDefaultLauncher() {
        val context = appContext ?: return
        val defaultLauncherPackage = resolveDefaultLauncherPackage(context)
        if (defaultLauncherPackage.isNullOrBlank()) {
            unbindInternal()
            Log.w(TAG, "Default launcher package could not be resolved")
            return
        }

        if (defaultLauncherPackage == boundPackage && launcherInterface != null) {
            return
        }

        unbindInternal()

        val bindIntent = Intent(ACTION_CONTROL_LAUNCHER).apply {
            setPackage(defaultLauncherPackage)
        }

        val didBind = runCatching {
            context.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            Log.w(TAG, "Failed binding to launcher package=$defaultLauncherPackage", it)
            false
        }

        if (didBind) {
            boundPackage = defaultLauncherPackage
            Log.i(TAG, "Binding requested to default launcher package=$defaultLauncherPackage")
        } else {
            boundPackage = null
            launcherInterface = null
            Log.w(TAG, "Default launcher does not expose OmniDev IPC service: $defaultLauncherPackage")
        }
    }

    fun shutdown() {
        val context = appContext
        if (context != null && receiverRegistered.compareAndSet(true, false)) {
            runCatching { context.unregisterReceiver(launcherChangeReceiver) }
        }
        unbindInternal()
        initialized.set(false)
    }

    private fun unbindInternal() {
        val context = appContext ?: return
        if (boundPackage != null) {
            runCatching { context.unbindService(serviceConnection) }
        }
        boundPackage = null
        launcherInterface = null
    }

    private fun resolveDefaultLauncherPackage(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName
    }

    private fun registerLauncherChangeReceiver(context: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return

        val filter = IntentFilter().apply {
            addAction("com.omnidev.action.DEFAULT_DISCOVER")
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }

        ContextCompat.registerReceiver(
            context,
            launcherChangeReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
}
