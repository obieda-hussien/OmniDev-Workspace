package com.omnidev.workspace.data.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TermuxTransportCancellationTest {
    private val bridge = TermuxRunCommandBridge

    @Before
    @After
    fun resetTransportHealth() = bridge.resetTransportHealth()

    @Test
    fun callerCancellationCleansUpWithoutMarkingTermuxUnavailable() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var callbacksCancelled = 0
        val command = launch {
            bridge.awaitServiceResult(10_000L, cancelCallback = { callbacksCancelled++ }) {
                started.complete(Unit)
                true
            }
        }
        withTimeout(1_000L) { started.await() }
        command.cancelAndJoin()

        assertEquals(1, callbacksCancelled)
        assertEquals(TermuxRunCommandBridge.TransportHealth.UNKNOWN, bridge.cachedTransportHealth())
    }

    @Test
    fun outerDeadlineIsDistinctFromTheTermuxResultDeadline() = runBlocking {
        var started = false
        var callbacksCancelled = 0
        try {
            withTimeout(100L) {
                bridge.awaitServiceResult(10_000L, cancelCallback = { callbacksCancelled++ }) {
                    started = true
                    true
                }
            }
            fail("Expected the caller deadline to cancel the request")
        } catch (_: TimeoutCancellationException) {
            assertTrue(started)
            assertEquals(1, callbacksCancelled)
            assertEquals(TermuxRunCommandBridge.TransportHealth.UNKNOWN, bridge.cachedTransportHealth())
        }
    }

    @Test
    fun termuxResultDeadlineRemainsATransportFailure() = runBlocking {
        var callbacksCancelled = 0
        val result = bridge.awaitServiceResult(25L, cancelCallback = { callbacksCancelled++ }) { true }

        assertFalse(result.transportSucceeded)
        assertEquals("Timed out waiting for Termux result after 25ms", result.internalError)
        assertEquals(1, callbacksCancelled)
        assertEquals(TermuxRunCommandBridge.TransportHealth.UNAVAILABLE, bridge.cachedTransportHealth())
    }

    @Test
    fun unresolvedServiceIsATransportFailureAndCleansUpTheCallback() = runBlocking {
        var callbacksCancelled = 0
        val result = bridge.awaitServiceResult(10_000L, cancelCallback = { callbacksCancelled++ }) { false }

        assertFalse(result.transportSucceeded)
        assertEquals(1, callbacksCancelled)
        assertEquals(TermuxRunCommandBridge.TransportHealth.UNAVAILABLE, bridge.cachedTransportHealth())
    }
}
