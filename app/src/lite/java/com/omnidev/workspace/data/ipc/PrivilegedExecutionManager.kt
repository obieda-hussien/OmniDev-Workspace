package com.omnidev.workspace.data.ipc

import android.content.Context

@Suppress("UNUSED_PARAMETER")
object PrivilegedExecutionManager {
    fun init(context: Context) = Unit
    fun getRishManager(): RishShellManager?  = null
    fun isShizukuReady(): Boolean  = false
    fun isRishReady(): Boolean  = false
    fun isRootAvailable(forceProbe: Boolean = false, probeTimeoutMs: Long = 1_500L): Boolean  = false
    fun cachedRootAvailable(): Boolean?  = null
    fun resetRootHealth() = Unit
    suspend fun executeCommand(
        command: String,
        allowRootFallback: Boolean = false
    ): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun executeRootCommand(command: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    fun shellQuote(value: String): String  = "\'" + value.replace("\'", "\'\"\'\"\'") + "\'"
    suspend fun getDeviceState(context: Context): DeviceStateSnapshot  = DeviceStateSnapshot(android.os.Build.FINGERPRINT, android.os.Build.VERSION.SDK_INT, android.os.Build.MODEL, android.os.Build.VERSION.RELEASE, false, false, false, "", 0, 0L)
    suspend fun getSystemProperty(key: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun setSystemProperty(key: String, value: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun dumpSysInfo(service: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun listRunningProcesses(): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun readSetting(namespace: String, key: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun writeSetting(namespace: String, key: String, value: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun listSettings(namespace: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun queryPackages(filter: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun installApk(apkPath: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun uninstallPackage(packageName: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun grantPermission(packageName: String, permission: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun revokePermission(packageName: String, permission: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun getPackageInfo(packageName: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun forceStopApp(packageName: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun launchComponent(component: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun sendBroadcast(action: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun injectTap(x: Int, y: Int): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun injectSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun injectText(text: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun injectKeyEvent(keyCode: Int): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun captureScreen(outputPath: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun controlService(service: String, action: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    suspend fun windowManager(subCommand: String, value: String): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
    fun getTermuxBootstrapHints(): String  = "Privileged execution is not included in Lite."
    suspend fun installTermuxViaShizuku(): Result<String>  = Result.failure(IllegalStateException("Privileged execution is not included in Lite."))
}
