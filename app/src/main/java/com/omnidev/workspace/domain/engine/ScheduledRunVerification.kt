package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.domain.engine.completion.CompletionReport
import com.omnidev.workspace.domain.engine.completion.FinishStatus

/** Only P0 may attest task completion. Text, tool invocation or transport success cannot. */
internal object ScheduledRunVerification {
    data class Verdict(val succeeded: Boolean, val reason: String?)

    fun evaluate(result: String, error: String?, report: CompletionReport?): Verdict = when {
        error != null -> Verdict(false, error)
        result.isBlank() -> Verdict(false, "No final response received.")
        report == null -> Verdict(false, "SCHEDULED_EXECUTION_UNVERIFIED: No P0 completion report was produced.")
        report.status != FinishStatus.COMPLETED -> Verdict(false,
            "SCHEDULED_EXECUTION_${report.status.name}: P0 did not accept completion.")
        !report.verified -> Verdict(false, "SCHEDULED_EXECUTION_UNVERIFIED: P0 criteria were not verified.")
        else -> Verdict(true, null)
    }
}
