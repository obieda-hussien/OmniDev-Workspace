package com.omnidev.workspace.domain.engine

/** Cheap request-time caps. These bound spend; they never grant tool or mode permissions. */
internal object AgentDecisionPolicy {
    fun outputCap(
        signals: IntentClassifier.TaskSignals,
        modelMaximum: Int,
        remaining: Int?
    ): Int {
        val taskCap = when {
            signals.structuralComplexity >= 0.65f || signals.breadth >= 0.62f -> 8_192
            signals.mutationIntent >= 0.16f || signals.verificationIntent >= 0.18f -> 4_096
            else -> 2_048
        }
        return minOf(modelMaximum, taskCap, remaining ?: taskCap).coerceAtLeast(1)
    }

    fun useModelCompaction(used: Int, total: Int?, remaining: Int?): Boolean {
        if (remaining != null && remaining < 2_500) return false
        return total == null || used < (total * 0.70f).toInt()
    }
}
