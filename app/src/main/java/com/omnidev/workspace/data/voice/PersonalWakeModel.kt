package com.omnidev.workspace.data.voice

import java.io.*
import kotlin.math.*

/** Few-shot acoustic template training, not a pretrained ASR or secure voice authenticator. */
class PersonalWakeModel private constructor(
    private val positive: List<WakeFeatures.Sample>,
    private val negative: List<WakeFeatures.Sample>,
    val threshold: Float,
    private val centroid: FloatArray,
    private val variance: FloatArray,
) {
    data class Match(val accepted: Boolean, val phraseDistance: Float, val voiceDistance: Float)
    fun match(sample: WakeFeatures.Sample, personalVoice: Boolean = true): Match {
        validate(sample)
        val distance = score(sample, positive)
        val voice = sqrt(centroid.indices.sumOf { c ->
            (sample.voice[c] - centroid[c]).toDouble().pow(2) / variance[c]
        } / centroid.size).toFloat()
        val rejectDistance = negative.minOf { dtw(sample.frames, it.frames) }
        return Match(distance <= threshold && distance < rejectDistance * .8f && (!personalVoice || voice <= 2.5f), distance, voice)
    }
    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
        out.writeInt(0x4F574D31); out.writeFloat(threshold)
        centroid.forEach(out::writeFloat); variance.forEach(out::writeFloat)
        for (samples in listOf(positive, negative)) {
            out.writeInt(samples.size)
            samples.forEach { s ->
                out.writeInt(s.frames.size)
                s.frames.forEach { f -> f.forEach(out::writeFloat) }
                s.voice.forEach(out::writeFloat)
            }
        }
    } }.toByteArray()

    companion object {
        fun train(positive: List<WakeFeatures.Sample>, negative: List<WakeFeatures.Sample>): PersonalWakeModel {
            require(positive.size == 5 && negative.size == 2) { "Record five wake phrases and two different phrases." }
            (positive + negative).forEach(::validate)
            val worstPositive = positive.indices.maxOf { i -> score(positive[i], positive.filterIndexed { j, _ -> i != j }) }
            val nearestNegative = negative.minOf { score(it, positive) }
            val threshold = max(.025f, worstPositive * 1.4f + .015f).coerceAtMost(.5f)
            require(worstPositive < threshold && threshold < nearestNegative * .8f) {
                "The phrases are not distinct enough. Re-record Hi Omni consistently and use different words for the negative examples."
            }
            val mean = FloatArray(WakeFeatures.DIM) { c -> positive.sumOf { it.voice[c].toDouble() }.toFloat() / positive.size }
            val variance = FloatArray(mean.size) { c -> max(.01f, positive.sumOf { (it.voice[c] - mean[c]).toDouble().pow(2) }.toFloat() / positive.size) }
            return PersonalWakeModel(positive, negative, threshold, mean, variance)
        }
        fun decode(bytes: ByteArray): PersonalWakeModel {
            require(bytes.size in 100..220_000)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == 0x4F574D31)
                val threshold = input.readFloat().also { require(it.isFinite() && it in .025f.. .5f) }
                val mean = FloatArray(WakeFeatures.DIM) { input.readFloat().also { f -> require(f.isFinite() && abs(f) <= 100) } }
                val variance = FloatArray(mean.size) { input.readFloat().also { f -> require(f.isFinite() && f in .01f..10000f) } }
                fun read(expected: Int): List<WakeFeatures.Sample> {
                    require(input.readInt() == expected)
                    return List(expected) {
                        val count = input.readInt().also { require(it in 30..299) }
                        WakeFeatures.Sample(Array(count) { FloatArray(WakeFeatures.DIM * 2) { input.readFloat() } },
                            FloatArray(WakeFeatures.DIM) { input.readFloat() }).also(::validate)
                    }
                }
                val positives = read(5); val negatives = read(2)
                require(input.available() == 0)
                PersonalWakeModel(positives, negatives, threshold, mean, variance)
            }
        }
        private fun validate(sample: WakeFeatures.Sample) {
            require(sample.frames.size in 30..299 && sample.voice.size == WakeFeatures.DIM)
            require(sample.voice.all { it.isFinite() && abs(it) <= 100 })
            require(sample.frames.all { f -> f.size == WakeFeatures.DIM * 2 && f.all { it.isFinite() && abs(it) <= 100 } })
        }
        private fun score(sample: WakeFeatures.Sample, templates: List<WakeFeatures.Sample>): Float =
            templates.map { dtw(sample.frames, it.frames) }.sorted().take(2).average().toFloat()

        /** Bounded DTW with a 25% time-warp band; linear memory. */
        internal fun dtw(a: Array<FloatArray>, b: Array<FloatArray>): Float {
            if (a.size.toFloat() / b.size !in .55f..1.8f) return Float.POSITIVE_INFINITY
            val band = max(abs(a.size - b.size), max(a.size, b.size) / 4)
            var previous = FloatArray(b.size + 1) { Float.POSITIVE_INFINITY }; previous[0] = 0f
            for (i in 1..a.size) {
                val current = FloatArray(b.size + 1) { Float.POSITIVE_INFINITY }
                for (j in max(1, i - band)..min(b.size, i + band)) {
                    val cost = sqrt(a[i - 1].indices.sumOf { k -> (a[i - 1][k] - b[j - 1][k]).toDouble().pow(2) } / a[i - 1].size).toFloat()
                    current[j] = cost + min(previous[j], min(current[j - 1], previous[j - 1]))
                }
                previous = current
            }
            return previous[b.size] / max(a.size, b.size)
        }
    }
}
