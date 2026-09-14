package com.omnidev.workspace.data.network

import java.net.URI

object ProviderEndpoint {
    fun normalize(value: String): String {
        val trimmed = value.trim().trimEnd('/').removeSuffix("/chat/completions")
        val uri = URI(trimmed)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "Enter an absolute HTTP or HTTPS base URL." }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "Base URL must not include credentials, a query or a fragment." }
        return trimmed
    }
}
