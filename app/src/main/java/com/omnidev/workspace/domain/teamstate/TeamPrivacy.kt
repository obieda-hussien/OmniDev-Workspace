package com.omnidev.workspace.domain.teamstate

import com.omnidev.workspace.domain.engine.SensitiveObservationRedactor

/** Operational persistence also receives JSON/config snippets, not just plain-text observations. */
internal object TeamPrivacy {
    private const val KEYS = "api[_ -]?key|access[_ -]?token|refresh[_ -]?token|token|secret|authorization|credential|password|passwd|otp|pin"
    private val doubleQuoted = Regex("(?i)(\"(?:$KEYS)\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"")
    private val singleQuoted = Regex("(?i)('(?:$KEYS)'\\s*:\\s*)'(?:\\\\.|[^'\\\\])*'")
    private val urlCredentials = Regex("(?i)(https?://)[^/\\s@]+@")
    fun redact(text: String): String = SensitiveObservationRedactor.redact(text
        .replace(doubleQuoted) { it.groupValues[1] + "\"[REDACTED]\"" }
        .replace(singleQuoted) { it.groupValues[1] + "'[REDACTED]'" }
        .replace(urlCredentials) { it.groupValues[1] + "[REDACTED]@" })
}
