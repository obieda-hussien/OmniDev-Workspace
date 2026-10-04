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
    @Test fun replacingOnlyTheProblemContrastRetainsSixOtherRecordings() {
        val enrollment = WakeEnrollment()
        val positives = List(5) { sample() }
        positives.forEach(enrollment::add)
        val firstContrast = sample(3); val badContrast = sample()
        enrollment.add(firstContrast)
        assertThrows(PersonalWakeModel.TrainingException::class.java) { enrollment.add(badContrast) }
        assertEquals(6, enrollment.trainingProblem!!.exampleIndex)
        enrollment.replaceProblemExample()
        assertEquals(WakeEnrollment.Phase.CONTRAST, enrollment.phase)
        assertEquals(6, enrollment.nextIndex)
        assertEquals(7, enrollment.count)
        enrollment.add(sample(4))
        assertTrue(positives.all(enrollment::owns)); assertTrue(enrollment.owns(firstContrast))
        assertFalse(enrollment.owns(badContrast))
        assertTrue(badContrast.frames.all { f -> f.all { it == 0f } })
        assertEquals(WakeEnrollment.Phase.VALIDATION, enrollment.phase)
        assertNotNull(enrollment.validate(sample(), true))
    }
    @Test fun replacingAnInconsistentWakeRecordingDoesNotRestartContrastTraining() {
        val enrollment = WakeEnrollment()
        val positives = List(5) { sample(if (it == 2) 5 else 1) }
        positives.forEach(enrollment::add)
        val firstContrast = sample(3); val secondContrast = sample(4)
        enrollment.add(firstContrast)
        assertThrows(PersonalWakeModel.TrainingException::class.java) { enrollment.add(secondContrast) }
        assertEquals(2, enrollment.trainingProblem!!.exampleIndex)
        enrollment.replaceProblemExample()
        assertEquals(WakeEnrollment.Phase.EXAMPLES, enrollment.phase)
        assertEquals(2, enrollment.nextIndex)
        enrollment.add(sample())
        assertEquals(7, enrollment.count)
        assertTrue(enrollment.owns(firstContrast)); assertTrue(enrollment.owns(secondContrast))
        assertTrue(positives[2].voice.all { it == 0f })
        assertEquals(WakeEnrollment.Phase.VALIDATION, enrollment.phase)
        assertNotNull(enrollment.validate(sample(), true))
    }
    @Test fun anotherBadReplacementDoesNotAppendAnEighthTrainingSample() {
        val enrollment = WakeEnrollment()
        repeat(6) { enrollment.add(sample()) }
        assertThrows(PersonalWakeModel.TrainingException::class.java) { enrollment.add(sample()) }
        repeat(2) {
            enrollment.replaceProblemExample()
            assertThrows(PersonalWakeModel.TrainingException::class.java) { enrollment.add(sample()) }
            assertEquals(7, enrollment.count)
            assertEquals(WakeEnrollment.Phase.TRAINING_FAILED, enrollment.phase)
            assertFalse(enrollment.canRecord)
        }
    }
}
