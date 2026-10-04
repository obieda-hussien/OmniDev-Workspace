package com.omnidev.workspace.data.assistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BubbleAttachmentAwaiterTest {
    @Test fun acceptedServiceStartDoesNotAuthorizeUntilAttached() = runTest {
        val signal = CompletableDeferred<Boolean>()
        val wait = async { awaitBubbleAttachment(signal) { true } }
        runCurrent()
        assertFalse(wait.isCompleted)
        signal.complete(true)
        assertTrue(wait.await())
    }
    @Test fun windowManagerRejectionDeniesAnAcceptedServiceStart() = runTest {
        val signal = CompletableDeferred<Boolean>()
        val wait = async { awaitBubbleAttachment(signal) { true } }
        runCurrent()
        signal.complete(false)
        assertFalse(wait.await())
    }
    @Test fun missingAttachmentTimesOutAndLateSignalCannotAuthorize() = runTest {
        val signal = CompletableDeferred<Boolean>()
        val wait = async { awaitBubbleAttachment(signal, timeoutMs = 100) { true } }
        assertFalse(wait.await())
        signal.complete(true)
        assertFalse(wait.await())
    }
    @Test fun failedServiceStartDoesNotWaitForAnAttachment() = runTest {
        val signal = CompletableDeferred<Boolean>()
        assertFalse(awaitBubbleAttachment(signal) { false })
        assertFalse(signal.isCompleted)
    }
    @Test fun cancelledRequestCannotContinueAfterLateAttachment() = runTest {
        val signal = CompletableDeferred<Boolean>()
        var actionStarted = false
        val wait = async {
            if (awaitBubbleAttachment(signal) { true }) actionStarted = true
        }
        runCurrent()
        wait.cancel()
        runCurrent()
        signal.complete(true)
        runCurrent()
        assertTrue(wait.isCancelled)
        assertFalse(actionStarted)
    }
}
