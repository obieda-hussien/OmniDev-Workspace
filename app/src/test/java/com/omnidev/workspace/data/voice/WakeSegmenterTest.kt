package com.omnidev.workspace.data.voice

import org.junit.Assert.*
import org.junit.Test

class WakeSegmenterTest {
    @Test fun endpointProducesOneBoundedUtteranceAndOwnsChunkCopies() {
        val segmenter = WakeSegmenter()
        repeat(10) { assertNull(segmenter.accept(ShortArray(320))) }
        val reused = ShortArray(320) { 2000 }
        repeat(30) { assertNull(segmenter.accept(reused)) }
        reused.fill(0)
        var result: ShortArray? = null
        repeat(20) { result = segmenter.accept(reused) ?: result }
        assertNotNull(result)
        assertTrue(result!!.size <= 48_000)
        assertTrue(result!!.count { it.toInt() == 2000 } >= 30 * 320)
        repeat(30) { assertNull(segmenter.accept(reused)) }
    }
    @Test fun longSpeechAndShortClicksDoNotBecomeWakeCandidates() {
        val segmenter = WakeSegmenter()
        repeat(400) { assertNull(segmenter.accept(ShortArray(320) { 1000 })) }
        repeat(30) { assertNull(segmenter.accept(ShortArray(320))) }
        repeat(3) { assertNull(segmenter.accept(ShortArray(320) { 1000 })) }
        repeat(30) { assertNull(segmenter.accept(ShortArray(320))) }
    }
    @Test fun resetDiscardsPrecedingSpeech() {
        val segmenter = WakeSegmenter()
        repeat(25) { segmenter.accept(ShortArray(320) { 1000 }) }
        segmenter.reset()
        repeat(30) { assertNull(segmenter.accept(ShortArray(320))) }
    }
    @Test fun calibratedBackgroundNoiseDoesNotBecomeSpeech() {
        val segmenter = WakeSegmenter(initialNoise = 800.0)
        repeat(100) { assertNull(segmenter.accept(ShortArray(320) { 800 })) }
        repeat(30) { assertNull(segmenter.accept(ShortArray(320) { 4000 })) }
        var found = false
        repeat(20) { found = segmenter.accept(ShortArray(320) { 800 }) != null || found }
        assertTrue(found)
    }
}
