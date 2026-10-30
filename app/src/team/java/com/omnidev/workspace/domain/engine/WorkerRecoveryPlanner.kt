package com.omnidev.workspace.domain.engine

/**
 * Decides whether a failed team worker is retried and with what guidance. A failed worker used to mark
 * every dependent task as blocked at once; now it first gets up to [MAX_ATTEMPTS] total attempts, each with
 * the previous failure and any partial evidence, so a transient or route-specific failure does not end the
 * whole team run.
 */
internal object WorkerRecoveryPlanner {
    const val MAX_ATTEMPTS = 3

    /** Marks a worker result the agent loop published as "announced work, never performed" (see PipelineRecovery). */
    const val INCOMPLETE_PREFIX = "[Incomplete]"

    fun shouldRetry(error: String?, redirected: Boolean, requiresSerialReplan: Boolean): Boolean {
        if (error == null || redirected || requiresSerialReplan) return false
        // Running out of tokens is not fixed by trying again from scratch.
        return !error.contains("token budget", ignoreCase = true) && !error.contains("budget exhausted", ignoreCase = true)
    }

    /** Short pause so a frozen backend or rate limit can settle; longer when the runtime itself failed. */
    fun waitMs(attempt: Int, infrastructureBlocked: Boolean): Long =
        if (infrastructureBlocked) 8_000L * (attempt - 1) else 1_500L

    fun note(attempt: Int, error: String, previousResult: String): String = buildString {
        appendLine("[Recovery attempt $attempt/$MAX_ATTEMPTS]")
        appendLine("The previous attempt at this task failed: ${error.take(600)}")
        val evidence = previousResult.substringAfter("[Partial evidence]\n", "").trim()
        if (evidence.isNotEmpty()) appendLine("Evidence the previous attempt left behind:\n${evidence.take(1_500)}")
        appendLine(
            "Do not repeat the failing call. Take a different route: if a shell or terminal backend fails or times out, " +
                "use another available execution route (the privileged Android shell, file tools, a different runtime) " +
                "before concluding that something is blocked."
        )
        append("If no route works, report exactly what is blocked and what was completed. Never fabricate results.")
    }
}
