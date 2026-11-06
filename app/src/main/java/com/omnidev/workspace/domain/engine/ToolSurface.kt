package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolSchemaCompactor
import com.omnidev.workspace.data.tools.ToolDefinition

/** Runtime-owned focus, reconstructed from the current persisted P3 step on every turn. */
data class ToolSurfaceFocus(
    val revision: String,
    val stepKey: String,
    val query: String,
    val domains: Set<IntentClassifier.ToolDomain> = emptySet(),
    val verificationTool: String = ""
)

/** Schema budget only. Tool eligibility, approvals and execution remain separate authorities. */
data class ToolSurfaceBudget(val maxTools: Int = 40, val maxSchemaChars: Int = 48_000) {
    init { require(maxTools in 1..40 && maxSchemaChars >= 1_500) }
    companion object {
        fun forContext(contextWindow: Int) = ToolSurfaceBudget(
            maxTools = if (contextWindow <= 8_192) 12 else 40,
            maxSchemaChars = (contextWindow.toLong() * 3 / 5).coerceIn(1_500, 48_000).toInt()
        )
    }
}

enum class ToolSurfaceSource { MENTION, VERIFICATION, DISCOVERY, ACTIVE_STEP, OBJECTIVE, OBSERVATION, PREFERENCE }
data class ToolSurfaceCandidate(val tool: ToolDefinition, val source: ToolSurfaceSource)
data class ToolSurfaceEntry(val name: String, val domain: IntentClassifier.ToolDomain, val source: ToolSurfaceSource)

/** Diagnostics contain registry names and counters, never queries, arguments or observations. */
data class ToolSurfaceSnapshot(val entries: List<ToolSurfaceEntry>, val schemaChars: Int, val omitted: Int) {
    fun summary() = "Tool surface: ${entries.size} capabilities, approximately $schemaChars schema characters" +
        if (omitted > 0) "; $omitted candidates deferred to discovery" else ""
}

/** Whole-schema selection: never remove required parameters, enums or action-specific requirements. */
object ToolSurfaceSelector {
    data class Selection(val definitions: List<ToolDefinition>, val snapshot: ToolSurfaceSnapshot)
    fun select(candidates: List<ToolSurfaceCandidate>, budget: ToolSurfaceBudget): Selection {
        val discovery = ToolSchemaCompactor.compact(listOf(RunToolCatalog.DISCOVER), emptyList()).orEmpty().single()
        val selected = mutableListOf(discovery)
        val entries = mutableListOf<ToolSurfaceEntry>()
        var cost = discovery.toString().length
        val unique = candidates.distinctBy { it.tool.name }
        for (candidate in unique) {
            val tool = ToolSchemaCompactor.compact(listOf(candidate.tool), emptyList()).orEmpty().single()
            val size = tool.toString().length
            if (entries.size >= budget.maxTools || cost + size > budget.maxSchemaChars) continue
            selected += tool
            entries += ToolSurfaceEntry(tool.name, IntentClassifier.getToolDomain(tool.name), candidate.source)
            cost += size
        }
        return Selection(selected, ToolSurfaceSnapshot(entries, cost, unique.size - entries.size))
    }
}
