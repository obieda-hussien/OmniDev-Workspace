package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCallResult

/**
 * Text delivered to the model with one turn's tool results. Extracted from the agent loop (one very large
 * suspend lambda that is close to the JVM's 64 KB method limit) without changing its content.
 */
internal object ToolTurnContent {
    fun build(
        results: List<ToolCallResult>,
        runtimeGuidance: String?,
        recoveryFeedback: String?,
        launcherNeedsGuidance: Boolean,
        readOnlyHeavy: Boolean,
        extraGuidance: String?
    ): String {
        val content = buildString {
            if (runtimeGuidance != null) appendLine(runtimeGuidance)
            append(
                results.joinToString("\n\n") { result ->
                    "[${result.toolName}] ${if (result.isError) "ERROR: " else ""}${result.output}"
                }
            )
            if (results.any { it.isError }) {
                append("\n\nOne or more tools failed. Do not claim those operations succeeded; pivot strategy or report the blocker explicitly.")
                recoveryFeedback?.let { append("\n\n$it") }
            } else if (launcherNeedsGuidance) {
                append("\n\n[Omni runtime guidance] Inspection has not completed the requested Launcher changes. Use approved typed mutation capabilities, then verify the resulting layout. Files and successful read-only probes cannot substitute for Home actions.")
            } else if (readOnlyHeavy) {
                append(
                    "\n\n[Omni runtime guidance] You already have multiple successful read-only " +
                        "observations. Prefer answering/synthesizing from the evidence now. Run another " +
                        "read-only probe only if you can name a specific missing fact that the next query " +
                        "will materially resolve; do not re-query the same dataset with cosmetic filters."
                )
            }
        }
        return if (extraGuidance != null) content + "\n\n" + extraGuidance else content
    }
}
