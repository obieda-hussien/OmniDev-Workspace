package com.omnidev.workspace.data.network

import java.io.IOException

/** A provider explicitly rejected tool/function calling for this model. */
class ModelCapabilityException : IOException(
    "Selected model does not support function calling. Choose an agent-capable model or use Auto."
) {
    companion object {
        fun fromHttp(code: Int, body: String): Boolean =
            code == 400 && (
                body.contains("Function calling is not enabled", ignoreCase = true) ||
                body.contains("function calling is not supported", ignoreCase = true) ||
                body.contains("tools are not supported", ignoreCase = true)
            )
    }
}
