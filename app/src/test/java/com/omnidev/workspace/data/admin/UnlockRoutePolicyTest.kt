package com.omnidev.workspace.data.admin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UnlockRoutePolicyTest {
    private val calls = mutableListOf<String>()
    private suspend fun request(locked: Boolean = true, saved: Boolean = true, voice: Boolean = true,
                                savedResult: String = "UNLOCKED: native verified") = UnlockRoutePolicy.request(
        locked, saved, voice,
        savedPin = { calls += "saved"; savedResult },
        voice = { calls += "voice"; "voice result" },
        native = { calls += "native"; "native result" })

    @Test fun authorizedSavedPinTakesPriorityOverConfiguredVoice() = runTest {
        val result = request()
        assertEquals(listOf("saved"), calls)
        assertEquals("android-saved-pin", result.backend)
        assertEquals("UNLOCKED: native verified", result.output)
    }
    @Test fun failedSavedAttemptCannotFallThroughIntoVoiceOrNative() = runTest {
        val failure = "USER_ACTION_REQUIRED: Android remains locked."
        assertEquals(failure, request(savedResult = failure).output)
        assertEquals(listOf("saved"), calls)
    }
    @Test fun unavailableSavedKeypadCannotStartASecondCredentialRoute() = runTest {
        request(savedResult = "USER_ACTION_REQUIRED: standard keypad unavailable; no PIN entered.")
        assertEquals(listOf("saved"), calls)
    }
    @Test fun alreadyUnlockedDoesNotConsumeAuthorizationOrStartAnyPrompt() = runTest {
        assertEquals("android-keyguard", request(locked = false).backend)
        assertTrue(calls.isEmpty())
    }
    @Test fun voiceRemainsAvailableWithoutAuthorizedSavedPin() = runTest {
        assertEquals("android-private-voice", request(saved = false).backend)
        assertEquals(listOf("voice"), calls)
    }
    @Test fun nativePromptWorksWithoutEitherCredentialRoute() = runTest {
        assertEquals("android-keyguard", request(saved = false, voice = false).backend)
        assertEquals(listOf("native"), calls)
    }
    @Test fun savedPinWorksWithoutMicrophoneOrVoiceSetup() = runTest {
        assertEquals("android-saved-pin", request(voice = false).backend)
        assertEquals(listOf("saved"), calls)
    }
    @Test fun cancellingSavedRoutePropagatesWithoutFallback() = runTest {
        try {
            UnlockRoutePolicy.request(true, true, true,
                savedPin = { throw CancellationException() },
                voice = { fail("Voice fallback is forbidden"); "" },
                native = { fail("Native fallback is forbidden"); "" })
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
    }
    @Test fun unavailableVoiceCanRequestPrivateLocalEntryWithoutAnotherCredentialAttempt() = runTest {
        val result = UnlockRoutePolicy.request(true, false, false,
            savedPin = { fail("No saved authorization"); "" },
            voice = { fail("Voice unavailable"); "" },
            native = { fail("Private entry should be selected"); "" },
            privateEntryAvailable = true,
            privateEntry = { "USER_ACTION_REQUIRED: local entry cancelled" })
        assertEquals("android-private-input", result.backend)
        assertTrue(result.output.contains("cancelled"))
    }
    @Test fun savedFailureNeverFallsBackToPrivateEntry() = runTest {
        val result = UnlockRoutePolicy.request(true, true, false,
            savedPin = { "USER_ACTION_REQUIRED: attempts paused" }, voice = { "" }, native = { "" },
            privateEntryAvailable = true, privateEntry = { fail("Second credential route forbidden"); "" })
        assertEquals("android-saved-pin", result.backend)
    }

}
