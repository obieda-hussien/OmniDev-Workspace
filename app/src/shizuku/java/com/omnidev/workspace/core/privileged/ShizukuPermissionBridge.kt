package com.omnidev.workspace.core.privileged

object ShizukuPermissionBridge {
    fun interface OnRequestPermissionResultListener { fun onRequestPermissionResult(requestCode: Int, grantResult: Int) }
    private val listeners = java.util.concurrent.ConcurrentHashMap<OnRequestPermissionResultListener, rikka.shizuku.Shizuku.OnRequestPermissionResultListener>()
    fun addRequestPermissionResultListener(listener: OnRequestPermissionResultListener) {
        val bridge = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { code, result -> listener.onRequestPermissionResult(code, result) }
        listeners[listener] = bridge
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(bridge)
    }
    fun removeRequestPermissionResultListener(listener: OnRequestPermissionResultListener) {
        listeners.remove(listener)?.let { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(it) }
    }
    fun getUid() = rikka.shizuku.Shizuku.getUid()
    fun pingBinder() = rikka.shizuku.Shizuku.pingBinder()
    fun checkSelfPermission() = rikka.shizuku.Shizuku.checkSelfPermission()
    fun requestPermission(code: Int) = rikka.shizuku.Shizuku.requestPermission(code)
}
