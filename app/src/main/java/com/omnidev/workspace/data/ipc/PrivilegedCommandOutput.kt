package com.omnidev.workspace.data.ipc

/** Captured successful output, including truncation reported by the backend. */
internal data class PrivilegedCommandOutput(val output: String, val truncated: Boolean = false) {
    fun limitedTo(maxChars: Int): PrivilegedCommandOutput =
        if (output.length <= maxChars) this else copy(output = output.take(maxChars), truncated = true)
}
