package com.omnidev.workspace.util

/**
 * Normalizes malformed URLs where a valid http(s) URL was prefixed with leading slashes.
 * Example: "/https://www.google.com" -> "https://www.google.com".
 */
fun normalizeLeadingSlashHttpUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (!trimmed.startsWith("/")) return trimmed
    val slashStripped = trimmed.trimStart('/')
    return if (slashStripped.startsWith("http://") || slashStripped.startsWith("https://")) {
        slashStripped
    } else trimmed
}
