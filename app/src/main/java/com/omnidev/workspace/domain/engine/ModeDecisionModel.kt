package com.omnidev.workspace.domain.engine

import kotlin.math.exp

/** Tiny bounded online model. Weights are per mode and contain no user text. */
internal object ModeDecisionModel {
    const val DIMENSIONS = 12
    private const val RATE = 0.06f
    private const val REGULARIZATION = 0.0005f

    fun features(s: IntentClassifier.TaskSignals): FloatArray = floatArrayOf(
        1f, s.executionIntent, s.mutationIntent, s.verificationIntent,
        s.parallelism, s.breadth, s.structuralComplexity, s.codeIntent,
        s.deviceIntent, s.researchIntent, (s.domainCount / 7f).coerceIn(0f, 1f),
        (s.wordCount / 120f).coerceIn(0f, 1f)
    )

    fun probability(weights: FloatArray, features: FloatArray): Float {
        require(weights.size == DIMENSIONS && features.size == DIMENSIONS)
        val logit = weights.indices.sumOf { (weights[it] * features[it]).toDouble() }.coerceIn(-8.0, 8.0)
        return (1.0 / (1.0 + exp(-logit))).toFloat()
    }

    fun updated(weights: FloatArray, features: FloatArray, success: Boolean, weight: Float): FloatArray {
        val error = ((if (success) 1f else 0f) - probability(weights, features)) *
            weight.coerceIn(0f, 1f)
        return FloatArray(DIMENSIONS) { index ->
            (weights[index] + RATE * (error * features[index] - REGULARIZATION * weights[index]))
                .coerceIn(-3f, 3f)
        }
    }

    fun encode(weights: FloatArray): String = weights.joinToString(",") { it.toString() }

    fun decode(value: String?): FloatArray {
        val parts = value?.split(',')?.mapNotNull { it.toFloatOrNull()?.takeIf { number -> number.isFinite() } }
        return if (parts?.size == DIMENSIONS) parts.toFloatArray() else FloatArray(DIMENSIONS)
    }
}
