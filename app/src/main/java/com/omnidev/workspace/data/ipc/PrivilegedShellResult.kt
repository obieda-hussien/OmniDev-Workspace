package com.omnidev.workspace.data.ipc

data class PrivilegedShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val uid: Int,
    val error: String? = null
) {
    val isSuccess: Boolean get() = !timedOut && error == null && exitCode == 0

    fun mergedOutput(): String = when {
        stdout.isNotBlank() && stderr.isNotBlank() -> "$stdout\n[stderr]\n$stderr"
        stdout.isNotBlank() -> stdout
        stderr.isNotBlank() -> stderr
        else -> ""
    }
}

