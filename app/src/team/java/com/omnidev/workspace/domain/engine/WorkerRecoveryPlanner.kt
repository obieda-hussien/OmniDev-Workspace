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

    fun shouldRetry(error: String?, redirected: Boolean, requiresSerialReplan: Boolean, attempt: Int = 1): Boolean {
        if (error == null || redirected || requiresSerialReplan) return false
        // One read-only alternative attempt, never unbounded full-task replay.
        if (error.startsWith("WORKER_NO_PROGRESS:")) return attempt == 1
        // Running out of tokens is not fixed by trying again from scratch.
        return !error.contains("token budget", ignoreCase = true) && !error.contains("budget exhausted", ignoreCase = true)
    }

    /** Short pause so a frozen backend or rate limit can settle; longer when the runtime itself failed. */
    fun waitMs(attempt: Int, infrastructureBlocked: Boolean): Long =
        if (infrastructureBlocked) 8_000L * (attempt - 1) else 1_500L

    fun note(attempt: Int, error: String, previousResult: String, observedTools: String = ""): String = buildString {
        appendLine("[Recovery attempt $attempt/$MAX_ATTEMPTS]")
        appendLine("The previous attempt at this task failed: ${error.take(600)}")
        val evidence = previousResult.substringAfter("[Partial evidence]\n", "").trim()
        if (evidence.isNotEmpty()) appendLine("Previously reported partial result (not verification):\n${evidence.take(1_200)}")
        if (observedTools.isNotBlank()) appendLine("Actual prior tool observations (not completion proof):\n${observedTools.take(1_200)}")
        if (error.startsWith("WORKER_NO_PROGRESS:")) appendLine(
            "Execute concrete tools and verify each acceptance item. Metadata read alone does not prove a build or edits. " +
                "Do not call finish_task before verifying the requested outputs."
        )
        appendLine(
            "Do not repeat the failing call. Take a different route: if a shell or terminal backend fails or times out, " +
                "use another eligible route (github_manager for remote reads, a verified checkout cwd for Git, " +
                "or an available IDE/build tool). Do not infer a local checkout from a repository URL."
        )
        append("If no route works, report exactly what is blocked and what was completed. Never fabricate results.")
    }
}
