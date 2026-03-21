package com.omnidev.workspace.data.ipc

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import android.view.KeyEvent
import com.omnidev.workspace.IOmniCoreInterface
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * OmniCoreService -- a bound Android Service running in the isolated process
 * :core_ipc that exposes the full IOmniCoreInterface AIDL interface to
 * trusted companion apps (e.g., a Magisk module companion).
 *
 * Security: every binder method calls enforceCorePermission() first.
 * Delegation: all privileged work goes to PrivilegedExecutionManager.
 */
class OmniCoreService : Service() {

    companion object {
        private const val TAG = "OmniCoreService"
        const val PERMISSION_CONTROL_CORE = "com.omnidev.permission.CONTROL_CORE"
    }

    private val serviceJob = SupervisorJob()
    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Unhandled coroutine exception", t)
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob + exceptionHandler)

    private val binder = object : IOmniCoreInterface.Stub() {

        override fun executeSystemCommand(command: String): Boolean {
            enforceCorePermission()
            return ipc("executeSystemCommand") {
                PrivilegedExecutionManager.executeCommand(command).isSuccess
            } ?: false
        }

        override fun executeCommandWithOutput(command: String): String {
            enforceCorePermission()
            return ipcString("executeCommandWithOutput") {
                PrivilegedExecutionManager.executeCommand(command)
                    .getOrElse { "ERROR: ${it.message}" }
            }
        }

        override fun getDeviceState(): String {
            enforceCorePermission()
            return ipcString("getDeviceState") {
                PrivilegedExecutionManager.getDeviceState(applicationContext).toJson()
            }
        }

        override fun getSystemProperty(key: String): String {
            enforceCorePermission()
            return ipcString("getSystemProperty") {
                PrivilegedExecutionManager.getSystemProperty(key)
                    .getOrElse { "ERROR: ${it.message}" }
            }
        }

        override fun setSystemProperty(key: String, value: String): Boolean {
            enforceCorePermission()
            return ipc("setSystemProperty") {
                PrivilegedExecutionManager.setSystemProperty(key, value).isSuccess
            } ?: false
        }

        override fun dumpSysInfo(service: String): String {
            enforceCorePermission()
            return ipcString("dumpSysInfo") {
                PrivilegedExecutionManager.dumpSysInfo(service)
                    .getOrElse { "ERROR: ${it.message}" }
            }
        }

        override fun listRunningProcesses(): String {
            enforceCorePermission()
            return ipcString("listRunningProcesses") {
                PrivilegedExecutionManager.listRunningProcesses()
                    .getOrElse { "ERROR: ${it.message}" }
            }
        }

        override fun readSetting(namespace: String, key: String): String {
            enforceCorePermission()
            return ipcString("readSetting") {
                PrivilegedExecutionManager.readSetting(namespace, key)
                    .getOrElse { "ERROR: ${it.message}" }
            }
        }

        override fun writeSetting(namespace: String, key: String, value: String): Boolean {
            enforceCorePermission()
            return ipc("writeSetting") {
                PrivilegedExecutionManager.writeSetting(namespace, key, value).isSuccess
            } ?: false
        }

        override fun listSettings(namespace: String): String {
            enforceCorePermission()
            return ipcString("listSettings") {
                PrivilegedExecutionManager.listSettings(namespace)
                    .getOrElse { "ERROR: ${it.message}" }
            }
        }

        override fun queryPackages(filter: String): String {
            enforceCorePermission()
            return ipcString("queryPackages") {
                PrivilegedExecutionManager.queryPackages(filter)
                    .getOrElse { "ERROR: ${it.message}" }
            }
        }

        override fun installApk(apkPath: String): Boolean {
            enforceCorePermission()
            return ipc("installApk") {
                PrivilegedExecutionManager.installApk(apkPath).isSuccess
            } ?: false
        }

        override fun uninstallPackage(packageName: String): Boolean {
            enforceCorePermission()
            return ipc("uninstallPackage") {
                PrivilegedExecutionManager.uninstallPackage(packageName).isSuccess
            } ?: false
        }

        override fun grantPermission(packageName: String, permission: String): Boolean {
            enforceCorePermission()
            return ipc("grantPermission") {
                PrivilegedExecutionManager.grantPermission(packageName, permission).isSuccess
            } ?: false
        }

        override fun revokePermission(packageName: String, permission: String): Boolean {
            enforceCorePermission()
            return ipc("revokePermission") {
                PrivilegedExecutionManager.revokePermission(packageName, permission).isSuccess
            } ?: false
        }

        override fun getPackageInfo(packageName: String): String {
            enforceCorePermission()
            return ipcString("getPackageInfo") {
                PrivilegedExecutionManager.getPackageInfo(packageName)
                    .getOrElse { "ERROR: ${it.message}" }
            }
        }

        override fun forceStopApp(packageName: String): Boolean {
            enforceCorePermission()
            return ipc("forceStopApp") {
                PrivilegedExecutionManager.forceStopApp(packageName).isSuccess
            } ?: false
        }

        override fun launchComponent(component: String): Boolean {
            enforceCorePermission()
            return ipc("launchComponent") {
                PrivilegedExecutionManager.launchComponent(component).isSuccess
            } ?: false
        }

        override fun sendBroadcast(action: String): Boolean {
            enforceCorePermission()
            return ipc("sendBroadcast") {
                PrivilegedExecutionManager.sendBroadcast(action).isSuccess
            } ?: false
        }

        override fun injectInputEvent(event: KeyEvent?) {
            enforceCorePermission()
            if (event == null) {
                Log.w(TAG, "injectInputEvent: null KeyEvent ignored")
                return
            }
            serviceScope.launch {
                PrivilegedExecutionManager.injectKeyEvent(event.keyCode)
                    .onFailure { t -> Log.e(TAG, "injectInputEvent failed", t) }
            }
        }

        override fun injectTap(x: Int, y: Int): Boolean {
            enforceCorePermission()
            return ipc("injectTap") {
                PrivilegedExecutionManager.injectTap(x, y).isSuccess
            } ?: false
        }

        override fun injectSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
            enforceCorePermission()
            return ipc("injectSwipe") {
                PrivilegedExecutionManager.injectSwipe(x1, y1, x2, y2, durationMs).isSuccess
            } ?: false
        }

        override fun injectText(text: String): Boolean {
            enforceCorePermission()
            return ipc("injectText") {
                PrivilegedExecutionManager.injectText(text).isSuccess
            } ?: false
        }

        override fun captureScreen(outputPath: String): String {
            enforceCorePermission()
            return ipcString("captureScreen") {
                PrivilegedExecutionManager.captureScreen(outputPath)
                    .getOrElse { "ERROR: ${it.message}" }
            }
        }

        override fun controlService(service: String, action: String): Boolean {
            enforceCorePermission()
            return ipc("controlService") {
                PrivilegedExecutionManager.controlService(service, action).isSuccess
            } ?: false
        }

        override fun windowManager(subCommand: String, value: String): String {
            enforceCorePermission()
            return ipcString("windowManager") {
                PrivilegedExecutionManager.windowManager(subCommand, value)
                    .getOrElse { "ERROR: ${it.message}" }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        // Ensure rish is available for the isolated :core_ipc process as well.
        PrivilegedExecutionManager.init(applicationContext)
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "OmniCoreService destroyed")
    }

    private fun <T> ipc(tag: String, block: suspend () -> T): T? {
        return runBlocking(Dispatchers.IO) {
            runCatching { block() }.getOrElse { t ->
                when (t) {
                    is DeadObjectException, is RemoteException ->
                        Log.w(TAG, "IPC transport error in $tag: ${t.message}")
                    else -> Log.e(TAG, "$tag failed", t)
                }
                null
            }
        }
    }

    private fun ipcString(tag: String, block: suspend () -> String): String =
        ipc(tag, block) ?: "{\"error\":\"IPC failure in $tag\"}"

    private fun enforceCorePermission() {
        val callerUid = Binder.getCallingUid()
        val check = checkPermission(PERMISSION_CONTROL_CORE, Binder.getCallingPid(), callerUid)
        if (check != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException(
                "Caller UID $callerUid does not hold $PERMISSION_CONTROL_CORE"
            )
        }
    }
}
