package com.omnidev.workspace.data.ipc

import android.content.Context
import java.io.File
import com.omnidev.workspace.data.tools.ToolExecutionResult

@Suppress("UNUSED_PARAMETER")
class RishShellManager(context: Context) {
    fun isAvailable(): Boolean = false
    fun cachedHealth(): RishRuntimeHealth = RishRuntimeHealth(RishRuntimeHealth.State.TERMUX_UNAVAILABLE, "Backend not included")
    suspend fun execute(command: String): Result<String> = Result.failure(IllegalStateException("Execution backend is not included in Lite."))
    suspend fun executeScript(scriptContent: String): Result<String> = Result.failure(IllegalStateException("Execution backend is not included in Lite."))
    suspend fun installIntoTermux(): RishRuntimeHealth = RishRuntimeHealth(RishRuntimeHealth.State.TERMUX_UNAVAILABLE, "Backend not included")
    suspend fun refreshHealth(): RishRuntimeHealth = RishRuntimeHealth(RishRuntimeHealth.State.TERMUX_UNAVAILABLE, "Backend not included")
    suspend fun statusReport(): String = "Execution backend is not included in Lite."
    suspend fun prepareLocalDex(): File? = null
    fun locateDex(): File? = null
    fun ensureRishScript(): String = "Execution backend is not included in Lite."
}
