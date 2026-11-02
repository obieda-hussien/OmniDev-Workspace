package com.omnidev.workspace.domain.engine.completion

import com.omnidev.workspace.domain.engine.IntentClassifier

/** Decides whether a run is bound by the finish protocol. Pure chat is never bound. */
data class FinalizationPolicy(
    /** finish_task is exposed and text-only finals are checked. */
    val enforce: Boolean,
    /** A final answer needs at least one successful tool call behind it. */
    val requireExecution: Boolean,
    val maxRejections: Int = 3
) {
    companion object {
        // Same thresholds already used by AdaptiveModeRouter ("explicit execution") and bucketKey ("exec").
        private const val EXECUTION = 0.34f
        private const val MUTATION = 0.16f
        private const val STRONG_EXECUTION = 0.55f

        fun decide(
            signals: IntentClassifier.TaskSignals,
            isWorker: Boolean,
            toolsAvailable: Boolean,
            identity: RunIdentity = RunIdentity()
        ): FinalizationPolicy {
            if (!toolsAvailable) return FinalizationPolicy(enforce = false, requireExecution = false)
            val actionTask = signals.executionIntent >= EXECUTION || signals.mutationIntent >= MUTATION
            val enforce = isWorker || actionTask
            val require = identity.requireExecution
                ?: (isWorker || signals.mutationIntent >= MUTATION || signals.executionIntent >= STRONG_EXECUTION)
            return FinalizationPolicy(enforce, enforce && require)
        }
    }
}
