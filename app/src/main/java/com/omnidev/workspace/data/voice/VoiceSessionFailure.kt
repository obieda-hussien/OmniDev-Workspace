package com.omnidev.workspace.data.voice

/** Never interpolate exception messages: a recognizer/parser exception may contain a code. */
internal object VoiceSessionFailure {
    fun describe(stage: String): String = when (stage) {
        "speech_output" -> "Offline speech output unavailable. Install an offline TTS voice, or use private local code entry."
        "microphone" -> "Local microphone capture stopped or is blocked. Close other recording apps and check microphone access."
        "model" -> "Offline speech model could not be opened. Check the installed model in Hi Omni settings."
        "native_prompt" -> "Android credential prompt is unavailable. Open Omni in the foreground and unlock manually."
        else -> "Private voice session stopped before Android unlock. Use private local code entry or check Hi Omni settings."
    }
}
