package com.omnidev.workspace.ui.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.LibVosk

/** Paired with the release APK gate: load the actual JNI bridge on the API 30 CI device. */
@RunWith(AndroidJUnit4::class)
class VoiceNativeLoadingTest {
    @Test fun jnaPeerLookupAndVoskInitializationWorkWithoutAModelDownload() {
        assertEquals(java.lang.Long.TYPE, Pointer::class.java.getDeclaredField("peer").type)
        assertTrue(Native.POINTER_SIZE == 4 || Native.POINTER_SIZE == 8)
        LibVosk.vosk_set_log_level(-1)
        // Exercise a second access too; a poisoned class initializer fails differently on retries.
        LibVosk.vosk_set_log_level(-1)
    }
}
