package com.omnidev.workspace.domain.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RunSteeringTest {
    @Test fun `every consumer sees all follow ups in order`() {
        val control = RunSteering()
        control.submit("Use Kotlin")
        control.submit("Also test Arabic layout")
        assertEquals(listOf(1L, 2L), control.after(0).map { it.revision })
        assertEquals(control.after(0), control.after(0))
        assertEquals("Also test Arabic layout", control.after(1).single().text)
        assertTrue(control.objective("Build the app").contains("Use Kotlin"))
    }

    @Test fun `model call is cancelled as soon as a correction arrives`() = runTest {
        val control = RunSteering()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val reasoning = async {
            try {
                control.reasoning(0) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
                false
            } catch (_: RunRedirected) { true }
        }
        started.await()
        control.submit("Correct the objective")
        assertTrue(reasoning.await())
        cancelled.await()
    }

    @Test fun `stale revision cannot start model work or seal completion`() = runTest {
        val control = RunSteering()
        control.submit("Correction")
        var started = false
        try { control.reasoning(0) { started = true } } catch (_: RunRedirected) { }
        assertFalse(started)
        assertFalse(control.finish(0))
        assertTrue(control.finish(1))
        assertThrows(IllegalStateException::class.java) { control.submit("Too late") }
    }

    @Test fun `new runs have isolated queues`() {
        val first = RunSteering()
        first.submit("Private task correction")
        assertEquals(0L, RunSteering().revision)
        assertTrue(RunSteering().after(0).isEmpty())
    }

    @Test fun `oversized follow ups fail without silently truncating or consuming a revision`() {
        val control = RunSteering()
        assertThrows(IllegalArgumentException::class.java) { control.submit("x".repeat(16_001)) }
        assertEquals(0L, control.revision)
    }
}
