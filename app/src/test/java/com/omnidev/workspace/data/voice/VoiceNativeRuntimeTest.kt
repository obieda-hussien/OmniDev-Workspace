package com.omnidev.workspace.data.voice

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class VoiceNativeRuntimeTest {
    @Test fun missingPeerBecomesRecoverableFailure() {
        val cause = UnsatisfiedLinkError("Can't obtain peer field ID for class com.sun.jna.Pointer")
        val error = assertThrows(VoiceNativeUnavailableException::class.java) { voiceNativeCall { throw cause } }
        assertSame(cause, error.cause)
        assertTrue(error.message!!.contains("retained"))
    }

    @Test fun failedClassInitializationRemainsRecoverableOnRetry() {
        for (cause in listOf(ExceptionInInitializerError("JNA"), NoClassDefFoundError("com.sun.jna.Native"))) {
            val error = assertThrows(VoiceNativeUnavailableException::class.java) { voiceNativeCall { throw cause } }
            assertSame(cause, error.cause)
        }
    }

    @Test fun cancellationAndUnrelatedFailuresPropagateUnchanged() {
        val cancellation = CancellationException("paused")
        assertSame(cancellation, assertThrows(CancellationException::class.java) { voiceNativeCall { throw cancellation } })
        val fatal = OutOfMemoryError("memory")
        assertSame(fatal, assertThrows(OutOfMemoryError::class.java) { voiceNativeCall { throw fatal } })
        val ordinary = IllegalArgumentException("model")
        assertSame(ordinary, assertThrows(IllegalArgumentException::class.java) { voiceNativeCall { throw ordinary } })
        assertEquals(42, voiceNativeCall { 42 })
    }
}
