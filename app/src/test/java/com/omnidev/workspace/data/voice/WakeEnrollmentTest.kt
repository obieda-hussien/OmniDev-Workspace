package com.omnidev.workspace.data.voice

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WakeEnrollmentTest {
    private fun sample(frequency: Int = 1, speaker: Float = 0f) = WakeFeatures.Sample(
        Array(60) { n -> FloatArray(24) { c -> sin(2 * PI * frequency * n / 60 + c * .3).toFloat() } },
        FloatArray(12) { it * .1f + speaker })
    private fun trained() = WakeEnrollment().apply {
        repeat(5) { add(sample()) }; add(sample(3)); add(sample(4))
    }
    @Test fun exactlySevenTrainingSamplesAndOneCheckFinishTheFlow() {
        val enrollment = trained()
        assertEquals(WakeEnrollment.Phase.VALIDATION, enrollment.phase)
        assertEquals(7, enrollment.count)
        val heldOut = sample()
        assertNotNull(enrollment.validate(heldOut, true))
        assertTrue(heldOut.frames.all { f -> f.all { it == 0f } })
        enrollment.complete()
        assertEquals(WakeEnrollment.Phase.COMPLETE, enrollment.phase)
        assertFalse(enrollment.canRecord)
        assertEquals(0, enrollment.count)
        assertThrows(IllegalStateException::class.java) { enrollment.add(sample()) }
    }
    @Test fun threeFailedChecksPauseInsteadOfAnEndlessValidationLoop() {
        val enrollment = trained()
        repeat(3) { assertNull(enrollment.validate(sample(5), true)) }
        assertEquals(7, enrollment.count)
        assertEquals(WakeEnrollment.Phase.VALIDATION_FAILED, enrollment.phase)
        assertFalse(enrollment.canRecord)
        assertThrows(IllegalStateException::class.java) { enrollment.validate(sample(), true) }
        enrollment.retryValidation()
        assertEquals(0, enrollment.validationAttempts)
        assertNotNull(enrollment.validate(sample(), true))
    }
    @Test fun trainingFailureIsSeparateAndContrastCanBeReplaced() {
        val enrollment = WakeEnrollment()
        repeat(6) { enrollment.add(sample()) }
        assertThrows(IllegalArgumentException::class.java) { enrollment.add(sample()) }
        assertEquals(WakeEnrollment.Phase.TRAINING_FAILED, enrollment.phase)
        assertFalse(enrollment.canRecord)
        enrollment.redoContrast()
        assertEquals(5, enrollment.count)
        enrollment.add(sample(3)); enrollment.add(sample(4))
        assertNotNull(enrollment.validate(sample(), true))
    }
    @Test fun validationUsesTheSameVoicePreferenceAsListening() {
        assertNull(trained().validate(sample(speaker = 3f), true))
        assertNotNull(trained().validate(sample(speaker = 3f), false))
    }
    @Test fun undoAndRestartWipeRemovedSamplesAndAllowNewTraining() {
        val enrollment = WakeEnrollment()
        val a = sample(); val b = sample()
        enrollment.add(a); enrollment.add(b); enrollment.undo()
        assertTrue(b.voice.all { it == 0f })
        assertEquals(1, enrollment.count)
        enrollment.restart()
        assertTrue(a.frames.all { f -> f.all { it == 0f } })
        assertEquals(WakeEnrollment.Phase.EXAMPLES, enrollment.phase)
        assertEquals(0, enrollment.count)
    }
}
