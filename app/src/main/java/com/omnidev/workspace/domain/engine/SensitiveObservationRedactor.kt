package com.omnidev.workspace.domain.engine

/**
 * Removes authentication secrets from tool observations before they enter model context or
 * persistent learning. The original ToolExecutionResult can still be shown in the local UI.
 *
 * This is deliberately narrow: financial amounts, balances, phone numbers and transaction IDs
 * are not redacted because they may be the fact the user explicitly asked the agent to inspect.
 */
object SensitiveObservationRedactor {

    private val keyedSecret = Regex(
        "(?i)\\b(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|token|secret|" +
            "password|passwd|otp|one[- ]time(?: password)?|verification code|security code|pin)\\b" +
            "\\s*[:=]\\s*([^\\s,;]+)"
    )

    private val spacedSecret = Regex(
        "(?i)\\b(otp|one[- ]time(?: password)?|verification code|security code|" +
            "password|passwd|pin|bearer)\\b\\s+([A-Za-z0-9._~+/=-]{4,})"
    )

    private val longToken = Regex(
        "(?i)\\b(token|bearer)\\b\\s+([A-Za-z0-9._~+/=-]{16,})"
    )

    private val bearerHeader = Regex(
        "(?i)\\bAuthorization\\s*:\\s*Bearer\\s+[A-Za-z0-9._~+/=-]+"
    )

    private val arabicSecret = Regex(
        "(?i)(رمز\\s*التحقق|كود\\s*التحقق|رمز\\s*التأكيد|كود\\s*التأكيد|" +
            "الرقم\\s*السري\\s*المؤقت|الرقم\\s*السري|رمز\\s*سري|" +
            "كلمة\\s*السر|كلمة\\s*المرور)\\s*[:：-]?\\s*" +
            "([0-9٠-٩A-Za-z]{4,16})"
    )

    fun redact(value: String): String {
        if (value.isBlank()) return value
        return value
            .replace(bearerHeader, "Authorization: Bearer [REDACTED]")
            .replace(keyedSecret) { match ->
                "${match.groupValues[1]}=[REDACTED]"
            }
            .replace(spacedSecret) { match ->
                "${match.groupValues[1]} [REDACTED]"
            }
            .replace(longToken) { match ->
                "${match.groupValues[1]} [REDACTED]"
            }
            .replace(arabicSecret) { match ->
                "${match.groupValues[1]} [REDACTED]"
            }
    }
}
