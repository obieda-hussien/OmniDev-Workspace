package com.omnidev.workspace.data.routines

import org.junit.Assert.*
import org.junit.Test

class RoutineVideoObservationTest {
    private val sample = "[SCREENSHOT_BASE64]\ndata:image/jpeg;base64,YWJjZA==\n[/SCREENSHOT_BASE64]\nSparse sample"
    @Test fun imageIsAttachedAndRemovedFromText() {
        val result = RoutineVideoObservation.extract(sample, true)
        assertEquals("YWJjZA==", result.image?.base64Data)
        assertFalse(result.observation.contains("YWJjZA=="))
        assertTrue(result.observation.contains("Sparse sample"))
    }
    @Test fun nonVisionModelGetsAnExplicitLimit() {
        val result = RoutineVideoObservation.extract(sample, false)
        assertNull(result.image); assertTrue(result.observation.contains("cannot view images"))
        assertFalse(result.observation.contains("YWJjZA=="))
    }
    @Test fun excessivePayloadDoesNotEnterImageOrTextContext() {
        val result = RoutineVideoObservation.extract(sample.replace("YWJjZA==", "A".repeat(600_001)), true)
        assertNull(result.image); assertTrue(result.observation.length < 150)
    }
    @Test fun malformedPayloadDoesNotBecomeEvidence() {
        assertNull(RoutineVideoObservation.extract("No sample decoded", true).image)
    }
}
