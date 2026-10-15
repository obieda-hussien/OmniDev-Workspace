package com.omnidev.workspace.data.accessibility

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ConflatedRefreshLoopTest {
    @Test fun eventStormRunsOneRefreshAndOneTrailingRefresh() = runBlocking {
        val started = Channel<Int>(Channel.UNLIMITED)
        val releaseFirst = CompletableDeferred<Unit>()
        var calls = 0
        val loop = ConflatedRefreshLoop(this, intervalMs = 1) {
            calls++
            started.send(calls)
            if (calls == 1) releaseFirst.await()
        }
        try {
            loop.request()
            assertEquals(1, withTimeout(2_000) { started.receive() })
            repeat(10_000) { loop.request() }
            assertEquals(1, calls)
            releaseFirst.complete(Unit)
            assertEquals(2, withTimeout(2_000) { started.receive() })
            delay(20)
            assertEquals(2, calls)
        } finally { loop.close() }
    }

    @Test fun closingDropsPendingWorkAndCancelsInFlightRefresh() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var calls = 0
        val loop = ConflatedRefreshLoop(this, intervalMs = 1) {
            calls++
            started.complete(Unit)
            try { CompletableDeferred<Unit>().await() }
            finally { cancelled.complete(Unit) }
        }
        loop.request()
        withTimeout(2_000) { started.await() }
        repeat(1_000) { loop.request() }
        loop.close()
        withTimeout(2_000) { cancelled.await() }
        loop.request()
        delay(20)
        assertEquals(1, calls)
    }
}
