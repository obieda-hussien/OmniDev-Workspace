package com.omnidev.workspace.core.privileged

object ShizukuPermissionBridge {
    fun interface OnRequestPermissionResultListener { fun onRequestPermissionResult(requestCode: Int, grantResult: Int) }
    fun addRequestPermissionResultListener(listener: OnRequestPermissionResultListener) = Unit
    fun removeRequestPermissionResultListener(listener: OnRequestPermissionResultListener) = Unit
    fun getUid() = -1
    fun pingBinder() = false
    fun checkSelfPermission() = android.content.pm.PackageManager.PERMISSION_DENIED
    fun requestPermission(code: Int) = Unit
}
