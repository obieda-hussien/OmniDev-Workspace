package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.model.ToolCallResult
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolExecutionResult

/**
 * Recovery logic used by the agent loop, kept outside the loop on purpose. The loop is one very large
 * suspend lambda; every inline block or suspension point added there is multiplied by the number of live
 * locals the compiler must spill, and the JVM rejects a method over 64 KB ("Method too large"). These
 * plain functions return small values, so the loop adds only a call and a few assignments per site.
 */
internal data class RecoveryStep(
    val recovered: Boolean,
    /** Messages to append to the history when [recovered]. */
    val messages: List<ChatMessage> = emptyList(),
    /** Short status shown once at the top of the next iteration. */
    val note: String = "",
    val waitMs: Long = 0L,
    /** Text to deliver together with this turn's tool results (invalid batches, stagnation). */
    val guidance: String? = null,
    /** Visible text to publish instead of a blank answer when recovery gave up. */
    val fallbackContent: String? = null
)

internal object PipelineRecovery {
    private val GUIDANCE_ONLY = setOf(
        AgentRecoveryGovernor.Kind.INVALID_BATCHES,
        AgentRecoveryGovernor.Kind.STAGNATION
    )

    fun step(
        governor: AgentRecoveryGovernor,
        kind: AgentRecoveryGovernor.Kind,
        detail: String = "",
        assistantText: String = "",
        digest: String = "",
        toolCalls: List<ToolCall> = emptyList()
    ): RecoveryStep = when (val decision = governor.decide(kind, detail, digest)) {
        is AgentRecoveryGovernor.Decision.Recover -> {
            val messages = when {
                kind in GUIDANCE_ONLY -> emptyList()
                kind == AgentRecoveryGovernor.Kind.DUPLICATE_CALLS -> listOf(
                    // Every call needs a result in the history; none of this batch is executed.
                    ChatMessage(
                        MessageRole.TOOL,
                        decision.message,
                        toolResults = toolCalls.map { ToolCallResult(it.id, it.name, decision.message, true) }
                    )
                )
                else -> listOf(
                    ChatMessage(MessageRole.ASSISTANT, assistantText.ifBlank { "(no output)" }),
                    ChatMessage(MessageRole.USER, decision.message)
                )
            }
            RecoveryStep(
                recovered = true,
                messages = messages,
                note = "Recovering: ${kind.name.lowercase().replace('_', ' ')} (${decision.attempt}/${decision.limit})",
                waitMs = decision.waitMs,
                guidance = decision.message
            )
        }
        is AgentRecoveryGovernor.Decision.GiveUp -> RecoveryStep(recovered = false)
    }

    /** A blank reply, or one that only announces work. Null when the reply is a normal final answer. */
    fun stall(governor: AgentRecoveryGovernor, content: String, lastObservation: String?): RecoveryStep? {
        val kind = when {
            content.isBlank() -> AgentRecoveryGovernor.Kind.EMPTY_RESPONSE
            UnfulfilledPromiseDetector.isUnfulfilled(content) -> AgentRecoveryGovernor.Kind.UNKEPT_PROMISE
            else -> return null
        }
        val step = step(governor, kind, assistantText = content)
        if (step.recovered || content.isNotBlank()) return step
        // Never publish a blank answer: say what is known instead.
        return step.copy(
            fallbackContent = "The model returned no answer after several attempts. " +
                (lastObservation?.let { "Latest observation: $it" } ?: "No tool evidence was collected.")
        )
    }

    /** Guidance for a stalled run, or null once the stagnation recoveries are spent. */
    fun stagnationGuidance(governor: AgentRecoveryGovernor, reasons: List<String>): String? =
        step(governor, AgentRecoveryGovernor.Kind.STAGNATION, reasons.joinToString("; ")).takeIf { it.recovered }?.guidance

    /**
     * Loads permitted tools the model named but that were not exposed yet, so the strict argument contract
     * validates and executes the call in this step. Returns [current] untouched when nothing was loaded.
     */
    fun loadOnDemand(calls: List<ToolCall>, current: List<ToolDefinition>, catalog: RunToolCatalog): List<ToolDefinition> {
        var loaded = false
        for (name in calls.map { it.name }.distinct()) {
            if (current.none { it.name == name } && catalog.ensureLoaded(name)) loaded = true
        }
        return if (loaded) catalog.definitions() else current
    }

    /** A successful, non-discovery tool result is real progress: it starts a fresh recovery episode. */
    fun noteProgress(
        calls: List<ToolCall>,
        results: List<ToolExecutionResult>,
        governor: AgentRecoveryGovernor,
        router: AutomaticToolRouter
    ) {
        val progressed = calls.zip(results).any { (call, result) -> !result.isError && call.name != RunToolCatalog.DISCOVER.name }
        if (progressed) {
            governor.onProgress()
            router.resetRecoveries()
        }
    }
}
