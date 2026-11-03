package com.omnidev.workspace.domain.engine.completion

import com.omnidev.workspace.domain.engine.IntentClassifier

/** Decides whether a run is bound by the finish protocol. Pure chat is never bound. */
data class FinalizationPolicy(
    /** finish_task is exposed and text-only finals are checked. */
    val enforce: Boolean,
    /** A final answer needs at least one successful tool call behind it. */
    val requireExecution: Boolean,
    val maxRejections: Int = 3,
    /** Implementation work needs at least one successful non-read action, not only reads. */
    val requireAction: Boolean = false
) {
    companion object {
        // Same thresholds already used by AdaptiveModeRouter ("explicit execution") and bucketKey ("exec").
        private const val EXECUTION = 0.34f
        private const val MUTATION = 0.16f
        private const val STRONG_EXECUTION = 0.55f

        fun resolveTaskKind(signals: IntentClassifier.TaskSignals, requested: TaskKind): TaskKind =
            if (requested == TaskKind.GENERAL && signals.mutationIntent >= MUTATION) TaskKind.IMPLEMENTATION
            else requested

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
            return FinalizationPolicy(enforce, enforce && require,
                requireAction = enforce && require && identity.taskKind == TaskKind.IMPLEMENTATION)
        }
    }
}
