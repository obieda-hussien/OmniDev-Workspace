package com.omnidev.workspace.data.voice

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import java.nio.ByteBuffer

class PersonalWakeModelTest {
    private fun sample(length: Int = 60, frequency: Int = 1, speaker: Float = 0f): WakeFeatures.Sample = WakeFeatures.Sample(
        Array(length) { n -> FloatArray(24) { c -> sin(2 * PI * frequency * n / length + c * .3).toFloat() } },
        FloatArray(12) { it * .1f + speaker },
    )
    private fun model() = PersonalWakeModel.train(List(5) { sample(58 + it) }, listOf(sample(frequency = 3), sample(frequency = 4)))
    private fun shifted(offset: Float) = sample().let { s ->
        WakeFeatures.Sample(Array(s.frames.size) { n -> FloatArray(24) { c -> s.frames[n][c] + offset } }, s.voice)
    }

    @Test fun separableExamplesAboveTheOldFixedDistanceCapCanTrain() {
        val model = PersonalWakeModel.train(List(5) { shifted(it * .4f) }, listOf(shifted(8f), shifted(10f)))
        assertTrue(model.threshold > .5f)
        assertTrue(model.match(shifted(.65f)).accepted)
        assertFalse(model.match(shifted(8f)).accepted)
        assertTrue(PersonalWakeModel.decode(model.encode()).match(shifted(.65f)).accepted)
    }
    @Test fun validSeparationIsNotRejectedBecauseExtraHeadroomOverlapsContrasts() {
        val positives = listOf(-.06f, -.03f, 0f, .03f, .06f).map(::shifted)
        val model = PersonalWakeModel.train(positives, listOf(shifted(.14f), shifted(-.14f)))
        assertTrue(model.match(shifted(.015f)).accepted)
        assertFalse(model.match(shifted(.14f)).accepted)
        assertFalse(model.match(shifted(-.14f)).accepted)
    }

    @Test fun freshPhraseAndTimeWarpMatchButOtherWordsDoNot() {
        val model = model()
        assertTrue(model.match(sample(65)).accepted)
        assertFalse(model.match(sample(frequency = 3)).accepted)
        assertFalse(model.match(sample(frequency = 5)).accepted)
        assertFalse(model.match(sample(140)).accepted)
    }
    @Test fun speakerPreferenceIsIndependentAndNeverAnIdentityGrant() {
        val otherVoice = sample(speaker = 3f)
        assertFalse(model().match(otherVoice).accepted)
        assertTrue(model().match(otherVoice, personalVoice = false).accepted)
    }
    @Test fun indistinguishableNegativeEnrollmentIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PersonalWakeModel.train(List(5) { sample() }, listOf(sample(), sample()))
        }
    }
    @Test fun oneInconsistentWakeRecordingIsIdentifiedByIndex() {
        val positives = List(5) { if (it == 2) sample(frequency = 5) else sample() }
        val error = assertThrows(PersonalWakeModel.TrainingException::class.java) {
            PersonalWakeModel.train(positives, listOf(sample(frequency = 3), sample(frequency = 4)))
        }
        assertEquals(PersonalWakeModel.TrainingIssue.INCONSISTENT_WAKE, error.issue)
        assertEquals(2, error.exampleIndex)
    }
    @Test fun overlappingContrastRecordingIsIdentifiedByIndex() {
        val error = assertThrows(PersonalWakeModel.TrainingException::class.java) {
            PersonalWakeModel.train(List(5) { sample() }, listOf(sample(frequency = 3), sample()))
        }
        assertEquals(PersonalWakeModel.TrainingIssue.CONTRAST_TOO_SIMILAR, error.issue)
        assertEquals(6, error.exampleIndex)
    }
    @Test fun legacyProfilesKeepTheirSavedThresholdAndFormat() {
        val bytes = model().encode()
        val header = ByteBuffer.wrap(bytes)
        header.putInt(0, 0x4F574D31)
        header.putFloat(4, .025f)
        val legacy = PersonalWakeModel.decode(bytes)
        assertEquals(.025f, legacy.threshold, 0f)
        assertTrue(legacy.match(sample()).accepted)
        assertFalse(legacy.match(sample(frequency = 3)).accepted)
        assertArrayEquals(bytes, legacy.encode())
    }
    @Test fun newProfilesRejectInvalidCalibratedThresholds() {
        val bytes = model().encode()
        for (threshold in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 401f)) {
            val altered = bytes.clone().also { ByteBuffer.wrap(it).putFloat(4, threshold) }
            assertThrows(IllegalArgumentException::class.java) { PersonalWakeModel.decode(altered) }
        }
        val legacyWithTooHighThreshold = bytes.clone().also { ByteBuffer.wrap(it).putInt(0, 0x4F574D31).putFloat(4, .6f) }
        assertThrows(IllegalArgumentException::class.java) { PersonalWakeModel.decode(legacyWithTooHighThreshold) }
    }
    @Test fun persistencePreservesDecisionAndRejectsMalformedProfiles() {
        val bytes = model().encode()
        assertTrue(PersonalWakeModel.decode(bytes).match(sample(65)).accepted)
        assertThrows(IllegalArgumentException::class.java) { PersonalWakeModel.decode(bytes + 0) }
        assertThrows(IllegalArgumentException::class.java) { PersonalWakeModel.decode(bytes.clone().apply { this[0] = 0 }) }
        assertThrows(IllegalArgumentException::class.java) { PersonalWakeModel.decode(ByteArray(250_000)) }
    }
    @Test fun nonFiniteFeaturesFailClosed() {
        val sample = sample().apply { frames[0][0] = Float.NaN }
        assertThrows(IllegalArgumentException::class.java) { model().match(sample) }
    }
    @Test fun mfccIsFiniteGainInvariantAndSilenceIsRejected() {
        val pcm = ShortArray(16_000) { (3000 * sin(2 * PI * 300 * it / 16000) + 1000 * sin(2 * PI * 700 * it / 16000)).toInt().toShort() }
        val a = WakeFeatures.extract(pcm)
        val b = WakeFeatures.extract(ShortArray(pcm.size) { (pcm[it] * .5).toInt().toShort() })
        assertEquals(98, a.frames.size)
        assertTrue(a.frames.all { f -> f.all { it.isFinite() } })
        assertTrue(PersonalWakeModel.dtw(a.frames, b.frames) < .02f)
        assertThrows(IllegalArgumentException::class.java) { WakeFeatures.extract(ShortArray(16_000)) }
        assertThrows(IllegalArgumentException::class.java) { WakeFeatures.extract(ShortArray(800)) }
    }
}
