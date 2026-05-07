package com.omnidev.workspace.util

/**
 * Normalizes malformed URLs where a valid http(s) URL was prefixed with leading slashes.
 * Example: "/https://www.google.com" -> "https://www.google.com".
 */
fun normalizeLeadingSlashHttpUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    val slashStripped = trimmed.trimStart('/')
    return if (trimmed.startsWith("/") &&
        (slashStripped.startsWith("http://") || slashStripped.startsWith("https://"))
    ) {
        slashStripped
    } else {
        trimmed
    }
}
