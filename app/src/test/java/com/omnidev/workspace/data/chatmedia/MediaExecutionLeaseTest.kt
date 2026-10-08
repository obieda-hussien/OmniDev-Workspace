package com.omnidev.workspace.data.chatmedia

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MediaExecutionLeaseTest {
    @Test fun immediateStartAndWorkerCannotSubmitTheSameJobTogether() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var requests = 0
        val direct = async { MediaExecutionLease.run("same-request") { requests++; entered.complete(Unit); finish.await(); "done" } }
        entered.await()
        assertNull(MediaExecutionLease.run("same-request") { requests++; "duplicate" })
        assertEquals("other", MediaExecutionLease.run("other-request") { "other" })
        finish.complete(Unit)
        assertEquals("done", direct.await())
        assertEquals(1, requests)
        assertEquals("status", MediaExecutionLease.run("same-request") { "status" })
    }

    @Test fun cancellationReleasesTheLeaseForRecoveryWithoutReplayingTheCreate() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val direct = launch { MediaExecutionLease.run("cancelled-request") { entered.complete(Unit); awaitCancellation() } }
        entered.await(); direct.cancelAndJoin()
        assertEquals("verify-existing", MediaExecutionLease.run("cancelled-request") { "verify-existing" })
    }
}
