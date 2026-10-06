package com.omnidev.workspace.data.admin

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Observe preparation only; never consume authorization or enter credentials while waiting. */
internal object SavedPinKeypadWait {
    enum class Outcome { UNLOCKED, PROMPT_FINISHED, REVOKED, READY, UNSUPPORTED }

    suspend fun await(
        locked: () -> Boolean,
        authorized: () -> Boolean,
        promptFinished: () -> Boolean,
        ready: () -> Boolean
    ): Outcome = withTimeoutOrNull(8_000) {
        var outcome: Outcome? = null
        while (outcome == null) {
            outcome = when {
                !locked() -> Outcome.UNLOCKED
                promptFinished() -> Outcome.PROMPT_FINISHED
                !authorized() -> Outcome.REVOKED
                ready() -> Outcome.READY
                else -> null
            }
            if (outcome == null) delay(150)
        }
        outcome
    } ?: Outcome.UNSUPPORTED
}
