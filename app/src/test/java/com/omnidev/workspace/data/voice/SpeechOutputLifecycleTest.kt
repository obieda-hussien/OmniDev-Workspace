package com.omnidev.workspace.data.voice

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SpeechOutputLifecycleTest {
    @Test fun `reported Android service not registered exception cannot crash close`() {
        var stops = 0
        var shutdowns = 0
        var reported = 0
        val life = SpeechOutputLifecycle<Any>(
            { stops++ }, { shutdowns++; throw IllegalArgumentException("Service not registered: TextToSpeech.Connection") },
            { reported++ }
        )
        assertTrue(life.beginInitialization())
        life.install(Any())
        life.close(); life.close(); life.stop()
        assertEquals(1, stops)
        assertEquals(1, shutdowns)
        assertEquals(1, reported)
        assertNull(life.current())
    }
    @Test fun `stop failure still permits shutdown`() {
        var shutdowns = 0
        val life = SpeechOutputLifecycle<Any>({ throw IllegalStateException("Disconnected") }, { shutdowns++ })
        life.install(Any()); life.close()
        assertEquals(1, shutdowns)
    }
    @Test fun `engine arriving after close is immediately released exactly once`() {
        var shutdowns = 0
        val life = SpeechOutputLifecycle<Any>({}, { shutdowns++ })
        assertTrue(life.beginInitialization())
        life.close()
        assertFalse(life.install(Any()))
        life.close()
        assertEquals(1, shutdowns)
        assertFalse(life.beginInitialization())
    }
    @Test fun `late utterance cleanup cannot touch a closed connection`() {
        var stops = 0
        val engine = Any()
        val life = SpeechOutputLifecycle<Any>({ stops++ }, {})
        life.install(engine); life.close(); life.stopIfCurrent(engine)
        assertEquals(1, stops)
        assertNull(life.withCurrent(engine) { fail("Closed engine must not be used") })
    }
    @Test fun `simultaneous closes release one connection once`() {
        val shutdowns = AtomicInteger(0)
        val life = SpeechOutputLifecycle<Any>({}, { shutdowns.incrementAndGet() })
        life.install(Any())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..8).map { executor.submit { life.close() } }
            futures.forEach { it.get() }
        } finally { executor.shutdownNow() }
        assertEquals(1, shutdowns.get())
    }
    @Test fun `initialization is claimed once`() {
        val life = SpeechOutputLifecycle<Any>({}, {})
        assertTrue(life.beginInitialization())
        assertFalse(life.beginInitialization())
    }
}
