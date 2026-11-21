package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.ToolExecutionResult

/** Recovery hints are advisory; existing authorization and execution contracts remain authoritative. */
internal object ToolFailurePivot {
    fun guidance(calls: List<ToolCall>, results: List<ToolExecutionResult>, repeatedGitHubFailure: Boolean): String? {
        val failed = calls.zip(results).filter { (_, result) -> result.isError }
        if (failed.isEmpty()) return null
        val hints = linkedSetOf<String>()
        for ((call, result) in failed) {
            val classification = result.classification
            when {
                classification == "MUTATION_OUTCOME_UNKNOWN" ->
                    hints += "A mutation may already have happened. Inspect its actual state using a read-only tool; do not resend it through another tool or format without verification."
                classification in setOf("USER_ACTION_REQUIRED", "CONFIRMATION_REQUIRED", "PERMISSION_DENIED", "TOOL_POLICY_DENIED", "ANDROID_PERMISSION_DENIED", "TIER_DENIED", "GITHUB_AUTH_REQUIRED", "GITHUB_PERMISSION_DENIED") ->
                    hints += "Respect the authorization boundary. Do not bypass it with a shell, another account or an alternate integration. Continue only permitted independent steps."
                AndroidCommandFailurePolicy.shouldStop(call.name, result) ->
                    hints += "This Android command is not supported by the current backend. Do not rerun the missing binary; discover a supported typed tool or a compatible command using its real schema."
                classification == "GITHUB_RATE_LIMITED" ->
                    hints += "Do not hammer the rate-limited GitHub endpoint. Continue independent work or use a permitted alternative only when it provides equivalent evidence."
                classification in setOf("INVALID_TOOL_ARGUMENTS", "INVALID_TOOL_BATCH", "BATCH_PREFLIGHT_BLOCKED", "TOOL_NOT_EXPOSED", "AMBIGUOUS_TOOL_CONTRACT") ->
                    hints += "No action ran for the rejected call. Correct the schema or discover an unambiguous registered capability before retrying."
                result.retryable && ToolBatchPolicy.isReadOnly(call) ->
                    hints += "A transient read failure allows a bounded retry with the same arguments. If that fails, switch to a different permitted route."
                else ->
                    hints += "Choose a genuinely different permitted tool or approach; do not merely repeat the same failed call."
            }
        }
        if (repeatedGitHubFailure) hints += "A GitHub resource failed repeatedly. Do not repeat identical lookups; verify the actual repository, ref or path, and retain the unresolved resource failure until evidence addresses it."
        return hints.joinToString(" ", prefix = "Recovery guidance: ") +
            " Continue independent unfinished steps, retain successful evidence and report any goal that remains unverified."
    }
}
