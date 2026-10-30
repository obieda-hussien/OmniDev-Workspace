package com.omnidev.workspace.domain.engine

/**
 * Turns "stop the run" exits into bounded, escalating recovery steps.
 *
 * A run used to end the moment a model repeated itself, invented tool names, answered with nothing,
 * or announced work it never performed. Each of those is now a recovery *episode*: the model gets a
 * precise corrective instruction (escalating with every attempt) and, for failures, a short wait.
 * Real progress (a successful, non-discovery tool result) starts a fresh episode, so a long task
 * that keeps working never runs out of recoveries. Without progress every kind still has a hard
 * limit, plus a global cap, so a model that cannot recover is stopped instead of looping forever.
 */
class AgentRecoveryGovernor(
    private val limits: Map<Kind, Int> = DEFAULT_LIMITS,
    private val maxTotalRecoveries: Int = MAX_TOTAL_RECOVERIES
) {
    enum class Kind { EMPTY_RESPONSE, UNKEPT_PROMISE, UNRESOLVED_FAILURE, INVALID_BATCHES, DUPLICATE_CALLS, STAGNATION }

    sealed interface Decision {
        data class Recover(val message: String, val waitMs: Long, val attempt: Int, val limit: Int) : Decision
        data class GiveUp(val attempts: Int) : Decision
    }

    private val used = mutableMapOf<Kind, Int>()
    private var totalRecoveries = 0

    val total: Int get() = totalRecoveries

    /** Real progress: the next stall begins a new episode with a full set of recoveries. */
    fun onProgress() {
        used.clear()
    }

    fun decide(kind: Kind, detail: String = "", toolDigest: String = ""): Decision {
        val limit = limits[kind] ?: 0
        val attempt = (used[kind] ?: 0) + 1
        if (attempt > limit || totalRecoveries >= maxTotalRecoveries) return Decision.GiveUp(attempt - 1)
        used[kind] = attempt
        totalRecoveries += 1
        return Decision.Recover(
            message = message(kind, attempt, limit, detail.trim().take(DETAIL_CHARS), toolDigest),
            waitMs = waitMs(kind, attempt),
            attempt = attempt,
            limit = limit
        )
    }

    private fun waitMs(kind: Kind, attempt: Int): Long = when (kind) {
        Kind.UNRESOLVED_FAILURE -> 1_500L * attempt
        Kind.EMPTY_RESPONSE -> 1_000L * attempt
        else -> 0L
    }

    private fun message(kind: Kind, attempt: Int, limit: Int, detail: String, toolDigest: String): String {
        val tag = "[Omni runtime recovery $attempt/$limit]"
        return when (kind) {
            Kind.EMPTY_RESPONSE -> if (attempt == 1) {
                "$tag Your last response was empty. Either call a tool to make progress, or write the final answer now. " +
                    "Never return an empty message."
            } else {
                "$tag Still empty. State in one short paragraph what is verified so far and what is still missing, " +
                    "then either call the next tool or give the final answer."
            }
            Kind.UNKEPT_PROMISE -> if (attempt == 1) {
                "$tag You described what you would do but did not do it. Perform the next action now with a tool call. " +
                    "If the work is already complete, restate the verified result as the final answer without promises."
            } else {
                "$tag Do not announce actions. Call the tool for the next concrete step now, or give the final answer " +
                    "with only what was actually verified."
            }
            Kind.UNRESOLVED_FAILURE -> {
                val base = "$tag A tool call failed and the failure is still unresolved" +
                    (if (detail.isNotEmpty()) ": $detail" else ".") + " Do not claim success."
                if (attempt == 1) {
                    "$base Fix the call using the tool's schema, or choose a different tool or approach that reaches the same goal. " +
                        "If the cause is environmental (a blocked backend), say precisely what is blocked and use another route."
                } else {
                    "$base Use a materially different approach now: name the alternative, then call it. If no alternative exists, " +
                        "give a final answer that states what was completed, what failed, and why."
                }
            }
            Kind.INVALID_BATCHES -> {
                val tools = if (toolDigest.isNotBlank()) " Use ONLY these exact tool names and parameters (* = required): $toolDigest." else ""
                "$tag Your recent tool calls were rejected as invalid.$tools Make one valid call at a time."
            }
            Kind.DUPLICATE_CALLS ->
                "DUPLICATE_CALL_BLOCKED: this exact call already ran with the same result, so repeating it cannot help. " +
                    "Change the inputs or the approach, or answer from the evidence you already have. $tag"
            Kind.STAGNATION ->
                "$tag Progress has stalled" + (if (detail.isNotEmpty()) " ($detail)" else "") +
                    ". Stop repeating similar probes. Take a different concrete action, or answer from the evidence you already have."
        }
    }

    companion object {
        const val MAX_TOTAL_RECOVERIES = 24
        private const val DETAIL_CHARS = 480

        val DEFAULT_LIMITS: Map<Kind, Int> = mapOf(
            Kind.EMPTY_RESPONSE to 3,
            Kind.UNKEPT_PROMISE to 2,
            Kind.UNRESOLVED_FAILURE to 1, // the tool router already spends two retrieval retries first
            Kind.INVALID_BATCHES to 2,
            Kind.DUPLICATE_CALLS to 3,
            Kind.STAGNATION to 2
        )
    }
}
