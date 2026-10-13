package com.omnidev.workspace.data.ipc

@Suppress("UNUSED_PARAMETER")
object ShizukuUserServiceClient {
    fun init(context: android.content.Context) = Unit
    fun isInitialized() = false
    fun cachedUid(): Int? = null
    fun invalidateService() = Unit
    suspend fun execute(command: String, timeoutMs: Long): PrivilegedShellResult = PrivilegedShellResult(-1, "", "Backend not included", false, -1, "Unavailable")
    suspend fun ping(): String = error("Backend not included")
}
