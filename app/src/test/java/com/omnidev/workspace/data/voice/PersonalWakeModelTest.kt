package com.omnidev.workspace.data.voice

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PersonalWakeModelTest {
    private fun sample(length: Int = 60, frequency: Int = 1, speaker: Float = 0f): WakeFeatures.Sample = WakeFeatures.Sample(
        Array(length) { n -> FloatArray(24) { c -> sin(2 * PI * frequency * n / length + c * .3).toFloat() } },
        FloatArray(12) { it * .1f + speaker },
    )
    private fun model() = PersonalWakeModel.train(List(5) { sample(58 + it) }, listOf(sample(frequency = 3), sample(frequency = 4)))

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
