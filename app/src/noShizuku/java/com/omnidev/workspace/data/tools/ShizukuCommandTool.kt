package com.omnidev.workspace.data.tools

/** Denial-only compatibility API. No Shizuku SDK, binder or permission requester. */
@Suppress("UNUSED_PARAMETER")
object ShizukuCommandTool {
    const val SHIZUKU_UNAVAILABLE_ERROR = "This build has no Shizuku backend."
    fun init(context: android.content.Context) = Unit
    fun isAvailable() = false
    fun hasPermission() = false
    suspend fun privilegedUidOrNull(): Int? = null
    suspend fun execute(command: String, timeoutMs: Long = 30_000L): ShizukuResult = ShizukuResult.Unavailable(SHIZUKU_UNAVAILABLE_ERROR)
    fun isShizukuServiceException(error: Throwable) = false
}
