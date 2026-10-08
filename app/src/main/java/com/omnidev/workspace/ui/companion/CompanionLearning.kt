package com.omnidev.workspace.ui.companion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

internal enum class CompanionAction { REST, PEEK, SNIFF, STRETCH, YAWN, LEFT, RIGHT, CONSOLE, MESSAGE, GROOM }
internal enum class CompanionPersonality { CALM, CURIOUS, PLAYFUL }
internal enum class CompanionRestCorner { LEARNED, LEFT, RIGHT }

/** No text, message IDs, session identifiers, screenshots or absolute screen coordinates. */
internal data class CompanionMindContext(
    val working: Boolean = false, val typing: Boolean = false, val roaming: Boolean = true,
    val reduced: Boolean = false, val hasConsole: Boolean = false, val hasMessages: Boolean = false,
    val x: Float = .5f, val y: Float = .5f, val perch: Int = 0,
    val feeling: CompanionFeeling = CompanionFeeling(), val personality: Int = 1
) {
    fun features(action: CompanionAction): FloatArray {
        val values = FloatArray(INPUTS)
        fun flag(value: Boolean) = if (value) 1f else 0f
        values[0] = flag(working); values[1] = flag(typing); values[2] = flag(roaming)
        values[3] = flag(reduced); values[4] = flag(hasConsole); values[5] = flag(hasMessages)
        values[6] = x.coerceIn(0f, 1f); values[7] = y.coerceIn(0f, 1f)
        values[8 + perch.coerceIn(0, 2)] = 1f
        values[11] = feeling.energy; values[12] = feeling.familiarity
        values[13] = feeling.irritation; values[14] = feeling.sadness; values[15] = feeling.joy
        values[16] = 1f
        values[17 + personality.coerceIn(0, 2)] = 1f
        values[CONTEXTS + action.ordinal] = 1f
        val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1f)
        return values.map { it / norm }.toFloatArray()
    }
    companion object { const val CONTEXTS = 20; const val INPUTS = CONTEXTS + 10 }
}

/** A compact, trainable tanh MLP. Explicit gradients keep the two-network runtime JVM-only. */
internal class CompanionNetwork(val inputs: Int, val hidden: Int, seed: Int, bias: Float = 0f) {
    val weights = FloatArray(inputs * hidden + hidden + hidden + 1)
    private val outputStart = inputs * hidden + hidden
    init {
        val random = Random(seed)
        for (i in 0 until inputs * hidden) weights[i] = (random.nextFloat() - .5f) * .4f
        for (i in 0 until hidden) weights[outputStart + i] = (random.nextFloat() - .5f) * .2f
        weights[weights.lastIndex] = bias
    }
    fun evaluate(x: FloatArray): Pair<Float, FloatArray> {
        require(x.size == inputs)
        val gradient = FloatArray(weights.size)
        var output = weights.last()
        for (h in 0 until hidden) {
            var z = weights[inputs * hidden + h]
            for (i in 0 until inputs) z += x[i] * weights[h * inputs + i]
            val activation = tanh(z)
            output += activation * weights[outputStart + h]
            val dz = weights[outputStart + h] * (1f - activation * activation)
            for (i in 0 until inputs) gradient[h * inputs + i] = dz * x[i]
            gradient[inputs * hidden + h] = dz
            gradient[outputStart + h] = activation
        }
        gradient[gradient.lastIndex] = 1f
        return output to gradient
    }
    fun train(x: FloatArray, target: Float, rate: Float = .035f) {
        val (output, gradient) = evaluate(x)
        val error = (output - target).coerceIn(-1f, 1f)
        val norm = sqrt(gradient.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1f)
        for (i in weights.indices) weights[i] = (weights[i] - rate * error * gradient[i] / norm).coerceIn(-4f, 4f)
    }
    fun copy(): CompanionNetwork = CompanionNetwork(inputs, hidden, 0).also { weights.copyInto(it.weights) }
}

internal data class CompanionDecision(
    val action: CompanionAction, val features: FloatArray, val gradient: FloatArray,
    val prediction: Float, val score: Float
)

/**
 * Mobile adaptation of EE-Net (JMLR 27:55, 2026), Algorithm 1:
 * f1 predicts reward, f2 learns signed (reward - pre-update f1) from f1's gradient.
 * We use a fixed 24-dimensional signed gradient sketch instead of the paper's LLE,
 * two small tanh networks, bounded replay and a cold-start behavioral prior.
 * This adaptation does not inherit the paper's overparameterized-network guarantees.
 */
internal class CompanionPolicy(
    val exploitation: CompanionNetwork = CompanionNetwork(CompanionMindContext.INPUTS, 16, 719, .5f),
    val exploration: CompanionNetwork = CompanionNetwork(SKETCH, 12, 911),
    val observations: Int = 0
) {
    fun evaluate(context: CompanionMindContext, action: CompanionAction): CompanionDecision {
        val features = context.features(action)
        val (prediction, fullGradient) = exploitation.evaluate(features)
        val gradient = sketch(fullGradient)
        val correction = exploration.evaluate(gradient).first.coerceIn(-.5f, .5f)
        return CompanionDecision(action, features, gradient, prediction, prediction + correction)
    }
    fun choose(context: CompanionMindContext, allowed: List<CompanionAction>): CompanionDecision? = allowed
        .map { evaluate(context, it) }.maxByOrNull { it.score }
    companion object {
        const val SKETCH = 24
        fun sketch(gradient: FloatArray): FloatArray {
            val result = FloatArray(SKETCH)
            for (i in gradient.indices) result[(i * 17 + i / SKETCH) % SKETCH] += gradient[i] * if (i % 2 == 0) 1f else -1f
            val norm = sqrt(result.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(.0001f)
            for (i in result.indices) result[i] /= norm
            return result
        }
    }
}

internal data class CompanionExperience(val decision: CompanionDecision, val reward: Float)

/** Only the background store owns this mutable learner; UI receives immutable network copies. */
internal class CompanionLearner {
    private val f1 = CompanionNetwork(CompanionMindContext.INPUTS, 16, 719, .5f)
    private val f2 = CompanionNetwork(CompanionPolicy.SKETCH, 12, 911)
    var observations = 0; private set
    val experiences = ArrayList<CompanionExperience>()
    var feeling = CompanionFeeling()
    var savedAt = 0L
    private var replayCursor = 0
    fun policy() = CompanionPolicy(f1.copy(), f2.copy(), observations)
    fun learn(decision: CompanionDecision, reward: Float) {
        if (!reward.isFinite() || decision.features.size != CompanionMindContext.INPUTS ||
            decision.gradient.size != CompanionPolicy.SKETCH || !decision.prediction.isFinite() ||
            (decision.features + decision.gradient).any { !it.isFinite() || abs(it) > 2f }) return
        val sample = CompanionExperience(decision.copy(features = decision.features.copyOf(), gradient = decision.gradient.copyOf()), reward.coerceIn(0f, 1f))
        if (experiences.size == CAPACITY) experiences.removeAt(0)
        experiences.add(sample)
        // Labels and projected gradients are captured before either network changes.
        update(sample)
        repeat(minOf(3, experiences.size - 1)) {
            replayCursor = (replayCursor + 1) % experiences.size
            update(experiences[replayCursor])
        }
        observations = (observations + 1).coerceAtMost(1_000_000)
    }
    private fun update(sample: CompanionExperience) {
        f2.train(sample.decision.gradient, (sample.reward - sample.decision.prediction).coerceIn(-1f, 1f), .025f)
        f1.train(sample.decision.features, sample.reward)
    }

    fun encode(): ByteArray {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            out.writeInt(VERSION); out.writeInt(observations); out.writeLong(savedAt)
            for (v in feeling.values()) out.writeFloat(v)
            for (network in listOf(f1, f2)) { out.writeInt(network.weights.size); for (v in network.weights) out.writeFloat(v) }
            out.writeInt(experiences.size)
            for (sample in experiences) {
                val d = sample.decision
                out.writeInt(d.action.ordinal); out.writeFloat(sample.reward); out.writeFloat(d.prediction)
                for (v in d.features) out.writeFloat(v)
                for (v in d.gradient) out.writeFloat(v)
            }
        }
        val bytes = payload.toByteArray()
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { it.writeInt(MAGIC); it.writeLong(CRC32().apply { update(bytes) }.value); it.write(bytes) }
        }.toByteArray()
    }
    companion object {
        const val CAPACITY = 128
        const val MAX_BYTES = 64 * 1024
        private const val MAGIC = 0x4F4D4E49
        private const val VERSION = 1
        fun decode(bytes: ByteArray): CompanionLearner? = runCatching {
            require(bytes.size in 12..MAX_BYTES)
            val input = DataInputStream(ByteArrayInputStream(bytes))
            require(input.readInt() == MAGIC)
            val checksum = input.readLong()
            require(checksum == CRC32().apply { update(bytes, 12, bytes.size - 12) }.value)
            require(input.readInt() == VERSION)
            val learner = CompanionLearner()
            learner.observations = input.readInt().also { require(it in 0..1_000_000) }
            learner.savedAt = input.readLong().also { require(it >= 0) }
            fun number(limit: Float = 4f) = input.readFloat().also { require(it.isFinite() && abs(it) <= limit) }
            val feelings = FloatArray(5) { number(1f).also { require(it >= 0f) } }
            learner.feeling = CompanionFeeling.from(feelings)
            for (network in listOf(learner.f1, learner.f2)) {
                require(input.readInt() == network.weights.size)
                for (i in network.weights.indices) network.weights[i] = number()
            }
            val count = input.readInt().also { require(it in 0..CAPACITY) }
            repeat(count) {
                val action = CompanionAction.entries[input.readInt().also { require(it in CompanionAction.entries.indices) }]
                val reward = number(1f).also { require(it >= 0f) }; val prediction = number()
                val features = FloatArray(CompanionMindContext.INPUTS) { number(2f) }
                val gradient = FloatArray(CompanionPolicy.SKETCH) { number(2f) }
                learner.experiences.add(CompanionExperience(CompanionDecision(action, features, gradient, prediction, prediction), reward))
            }
            require(input.available() == 0)
            learner
        }.getOrNull()
    }
}
