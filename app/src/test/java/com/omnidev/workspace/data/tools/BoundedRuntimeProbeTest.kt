package com.omnidev.workspace.data.tools

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class BoundedRuntimeProbeTest {
    @Test fun `timeout returns while blocking work remains capped and late success is ignored`() = runBlocking {
        BoundedRuntimeProbe().use { probe ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val pending = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(30L) {
                        probe.check { entered.countDown(); release.await(); true }
                    }
                }
                assertTrue(entered.await(2L, TimeUnit.SECONDS))
                assertNull(withTimeout(1_000L) { pending.await() })
                var extraExecuted = false
                assertFalse(withTimeout(1_000L) { probe.check { extraExecuted = true; true } })
                assertFalse(extraExecuted)
                release.countDown()
                assertNull(pending.await())
            } finally { release.countDown() }
        }
    }

    @Test fun `caller cancellation does not wait for or replace blocked work`() = runBlocking {
        BoundedRuntimeProbe().use { probe ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val pending = async(start = CoroutineStart.UNDISPATCHED) {
                    probe.check { entered.countDown(); release.await(); true }
                }
                assertTrue(entered.await(2L, TimeUnit.SECONDS))
                withTimeout(1_000L) { pending.cancelAndJoin() }
                assertFalse(probe.check { fail("Queued another operation"); true })
            } finally { release.countDown() }
        }
    }

    @Test fun `healthy operation is reported without a fabricated cache result`() = runBlocking {
        BoundedRuntimeProbe().use { probe -> assertTrue(probe.check { true }) }
    }
}
