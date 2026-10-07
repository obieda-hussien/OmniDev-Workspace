package com.omnidev.workspace.data.admin

/** Select exactly one authentication route. Failure never authorizes another credential attempt. */
internal object UnlockRoutePolicy {
    data class Result(val output: String, val backend: String)

    suspend fun request(
        locked: Boolean,
        savedPinAuthorized: Boolean,
        voiceAvailable: Boolean,
        savedPin: suspend () -> String,
        voice: suspend () -> String,
        native: suspend () -> String,
        privateEntryAvailable: Boolean = false,
        privateEntry: suspend () -> String = native
    ): Result = when {
        !locked -> Result("UNLOCKED: verified with Android keyguard state.", "android-keyguard")
        savedPinAuthorized -> Result(savedPin(), "android-saved-pin")
        voiceAvailable -> Result(voice(), "android-private-voice")
        privateEntryAvailable -> Result(privateEntry(), "android-private-input")
        else -> Result(native(), "android-keyguard")
    }
}
