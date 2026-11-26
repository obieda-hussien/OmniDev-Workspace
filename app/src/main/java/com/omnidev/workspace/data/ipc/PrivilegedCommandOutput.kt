package com.omnidev.workspace.data.ipc

import com.omnidev.workspace.data.tools.ShizukuResult

/** Captured successful output, including truncation reported by the backend. */
internal data class PrivilegedCommandOutput(val output: String, val truncated: Boolean = false) {
    fun limitedTo(maxChars: Int): PrivilegedCommandOutput =
        if (output.length <= maxChars) this else copy(output = output.take(maxChars), truncated = true)
}


internal class PrivilegedProcessFailure(
    message: String,
    val exitCode: Int,
    val captured: PrivilegedCommandOutput,
    val backend: String = "shizuku-user-service"
) : IllegalStateException(message)

internal fun ShizukuResult.Failure.toPrivilegedProcessFailure(): PrivilegedProcessFailure? =
    exitCode?.let {
        PrivilegedProcessFailure("Shizuku command failed: $reason", it, PrivilegedCommandOutput(output, truncated))
    }
