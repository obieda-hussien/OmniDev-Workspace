package com.omnidev.workspace.data.tools

/** Keep common one-time codes out of model-visible notification results. */
internal object NotificationTextPolicy {
    private val code = Regex(
        """(?i)(?:\b(?:otp|one.time (?:code|password)|verification code|security code|passcode)\b|(?:رمز|كود)(?:\s+التحقق)?).{0,24}?\b[0-9٠-٩]{4,8}\b"""
    )

    fun redact(text: String): String = code.replace(text, "[one-time code hidden]")
}
