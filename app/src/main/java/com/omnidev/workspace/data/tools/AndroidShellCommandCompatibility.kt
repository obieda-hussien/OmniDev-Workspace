package com.omnidev.workspace.data.tools

/**
 * Normalizes known Android shell CLI changes before sending commands to Shizuku.
 *
 * Android 11 (API 30) moved media volume control behind the media_session cmd service.
 * This deliberately rewrites only the exact legacy "media volume" command token; it does
 * not inspect/modify quoted arguments, arbitrary binaries, or unrelated shell commands.
 */
internal object AndroidShellCommandCompatibility {
    data class AdaptedCommand(
        val command: String,
        val note: String? = null
    )

    private val legacyMediaVolume = Regex("""(^|[;&|][ \t]*)media[ \t]+volume(?=[ \t]|$)""")

    fun adapt(command: String, apiLevel: Int): AdaptedCommand {
        if (apiLevel < ANDROID_11_API || command.isBlank()) return AdaptedCommand(command)
        if (!legacyMediaVolume.containsMatchIn(command)) return AdaptedCommand(command)

        val rewritten = legacyMediaVolume.replace(command) { match ->
            match.groupValues[1] + "cmd media_session volume"
        }
        return if (rewritten == command) AdaptedCommand(command) else AdaptedCommand(
            command = rewritten,
            note = "Android 11+ compatibility: routed legacy 'media volume' through 'cmd media_session volume'."
        )
    }

    const val ANDROID_11_API = 30
}
