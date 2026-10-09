// Copyright 2026 Abdelrahman Hussein. Original Omni integration contribution.

package com.omnidev.workspace.data.ipc

import com.omnilink.sdk.OmniJson
import com.omnilink.sdk.PublicGatewayPolicy
import com.omnilink.sdk.PublicOmniRequest
import com.omnilink.sdk.PublicRequestKind

/** Public launcher text is an untrusted draft, never a command or privileged caller identity. */
object OmniSearchIngress {
    const val MAX_PROMPT_CHARS = 8_192

    data class Navigation(val prompt: String? = null)

    fun parse(json: String): Navigation? {
        if (PublicGatewayPolicy.validate(json) != null) return null
        val request = runCatching { OmniJson.instance.decodeFromString<PublicOmniRequest>(json) }.getOrNull()
            ?: return null
        return when (request.kind) {
            PublicRequestKind.OPEN_OMNI -> Navigation()
            PublicRequestKind.ASK_OMNI -> {
                val text = request.text?.trim().orEmpty()
                if (text.isBlank() || text.length > MAX_PROMPT_CHARS) null else Navigation(text)
            }
            PublicRequestKind.SHARE_TO_OMNI -> null
        }
    }
}
