package com.omnidev.workspace.data.voice

/** A failed native class initializer can also throw NoClassDefFoundError on later attempts. */
internal class VoiceNativeUnavailableException(cause: LinkageError) : IllegalStateException(
    "The offline speech library could not load. Update Omni and restart the app. " +
        "Your wake profile and downloaded languages are retained.", cause
)

// Catch native linkage failures only. Cancellation, VM errors and ordinary failures keep their semantics.
internal inline fun <T> voiceNativeCall(block: () -> T): T = try {
    block()
} catch (failure: LinkageError) {
    throw VoiceNativeUnavailableException(failure)
}
