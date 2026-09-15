package com.omnidev.workspace.data.network

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Keeps provider cooldown metadata without retaining response bodies or credentials. */
class RateLimitException(val retryAfterMs: Long?) : IOException(
    "Rate limit exceeded." + (retryAfterMs?.let { " Try again after ${(it + 999) / 1000} seconds." }
        ?: " Wait before trying again.")
)

internal fun parseRetryAfter(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    raw.toLongOrNull()?.let { seconds ->
        return if (seconds >= 0) seconds.coerceAtMost(Long.MAX_VALUE / 1000 - 1) * 1000 else null
    }
    return runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
            isLenient = false
        }.parse(raw)?.time?.let { (it - nowMs).coerceAtLeast(0) }
    }.getOrNull()
}
