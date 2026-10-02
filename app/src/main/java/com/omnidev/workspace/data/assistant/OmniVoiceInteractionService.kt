package com.omnidev.workspace.data.assistant

import android.service.voice.VoiceInteractionService

/** Android owns invocation; no foreground service, hotword listener, or overlay permission. */
class OmniVoiceInteractionService : VoiceInteractionService()
