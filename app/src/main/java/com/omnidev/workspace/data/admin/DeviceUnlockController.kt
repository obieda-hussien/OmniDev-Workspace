package com.omnidev.workspace.data.admin

import android.content.Context
import com.omnidev.workspace.data.voice.LocalVoiceSessionService
import com.omnidev.workspace.ui.assistant.DeviceUnlockActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Entry point for tools. Private voice sessions use LocalPinUnlock directly to avoid recursion. */
internal object DeviceUnlockController {
    suspend fun request(context: Context): UnlockRoutePolicy.Result = withContext(Dispatchers.Main.immediate) {
        val consent = DeviceConsentStore(context)
        if (!consent.enabled(DeviceConsentPolicy.Scope.UNLOCK)) return@withContext UnlockRoutePolicy.Result(
            "DENIED: enable Android unlock in Device access.", "android-keyguard")
        UnlockRoutePolicy.request(
            locked = consent.locked(),
            savedPinAuthorized = LocalPinUnlock.authorized(context),
            voiceAvailable = LocalVoiceSessionService.unlockUnavailableReason(context) == null,
            savedPin = { LocalPinUnlock.request(context) },
            voice = { LocalVoiceSessionService.requestUnlockResult(context) },
            native = { DeviceUnlockActivity.request(context, true) }
        )
    }
}
