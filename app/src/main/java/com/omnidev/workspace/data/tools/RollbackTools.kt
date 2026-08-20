package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.rollback.RollbackManager

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * RollbackTools — System awareness note Agent System awareness note Rollback (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * System awareness note System awareness note [RollbackManager] System awareness note Agent System awareness note System awareness note System awareness note:
 *   - rollback_list_recent: System awareness note System awareness note snapshots
 *   - rollback_list_groups: System awareness note groups System awareness note System awareness note (System awareness note System awareness note rollback)
 *   - rollback_apply_group: System awareness note System awareness note System awareness note group System awareness note
 *   - rollback_apply_one: System awareness note snapshot System awareness note
 *
 * Mobile-first: System awareness note System awareness note System awareness note System awareness note root System awareness note System awareness note System awareness note File API.
 */
class RollbackTools(private val rollbackManager: RollbackManager) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "rollback_list_recent",
            description = "System awareness note System awareness note N rollback snapshots System awareness note System awareness note System awareness note. " +
                "System awareness note System awareness note rollback_apply_one System awareness note System awareness note snapshot id.",
            parameters = listOf(
                ToolParameter("limit", "integer", "System awareness note System awareness note (1-50System awareness note System awareness note 20)", required = false)
            )
        ),
        ToolDefinition(
            name = "rollback_list_groups",
            description = "System awareness note System awareness note action groups System awareness note System awareness note System awareness note System awareness note System awareness note group. " +
                "System awareness note System awareness note rollback_apply_group.",
            parameters = listOf(
                ToolParameter("limit", "integer", "System awareness note System awareness note (1-50System awareness note System awareness note 20)", required = false)
            )
        ),
        ToolDefinition(
            name = "rollback_apply_group",
            description = "System awareness note System awareness note System awareness note System awareness note action group System awareness note System awareness note. " +
                "System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note. System awareness note System awareness note System awareness note!",
            parameters = listOf(
                ToolParameter("group_id", "string", "System awareness note System awareness note action group System awareness note rollback_list_groups")
            )
        ),
        ToolDefinition(
            name = "rollback_apply_one",
            description = "System awareness note snapshot System awareness note System awareness note (System awareness note System awareness note) System awareness note System awareness note System awareness note rollback.",
            parameters = listOf(
                ToolParameter("snapshot_id", "integer", "System awareness note System awareness note snapshot System awareness note rollback_list_recent")
            )
        )
    )

    /** System awareness note null System awareness note System awareness note System awareness note System awareness note System awareness note System awareness note wrapper (System awareness note fall-through). */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            when (name) {
                "rollback_list_recent" -> {
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                    val items = rollbackManager.listRecent(limit)
                    if (items.isEmpty()) ToolExecutionResult("System awareness note System awareness note snapshots System awareness note.")
                    else ToolExecutionResult(buildString {
                        appendLine("📜 System awareness note ${items.size} snapshot:")
                        for (s in items) {
                            val rolled = if (s.rolledBack) " [System awareness note]" else ""
                            val pinned = if (s.pinned) " 📌" else ""
                            appendLine("  #${s.id}$pinned$rolled — ${s.toolName} → ${s.filePath}")
                            if (s.reason.isNotBlank()) appendLine("       System awareness note: ${s.reason.take(120)}")
                        }
                    })
                }
                "rollback_list_groups" -> {
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                    val groups = rollbackManager.listGroups(limit)
                    if (groups.isEmpty()) ToolExecutionResult("System awareness note System awareness note action groups System awareness note.")
                    else ToolExecutionResult(buildString {
                        appendLine("📦 System awareness note ${groups.size} action group:")
                        for (g in groups) {
                            appendLine("  ${g.actionGroupId} — ${g.fileCount} System awareness note | ${g.toolName ?: "?"}")
                            if (!g.reason.isNullOrBlank()) appendLine("       ${g.reason.take(120)}")
                        }
                    })
                }
                "rollback_apply_group" -> {
                    val gid = args["group_id"]?.trim()
                        ?: return ToolExecutionResult("group_id System awareness note", isError = true)
                    val res = rollbackManager.rollbackGroup(gid)
                    val errs = if (res.errors.isEmpty()) "" else
                        "\n⚠️ System awareness note (${res.errors.size}):\n${res.errors.joinToString("\n").take(800)}"
                    ToolExecutionResult(
                        "✅ System awareness note System awareness note ${res.restored}/${res.attempted} System awareness note System awareness note group $gid$errs",
                        isError = res.restored == 0
                    )
                }
                "rollback_apply_one" -> {
                    val sid = args["snapshot_id"]?.toLongOrNull()
                        ?: return ToolExecutionResult("snapshot_id System awareness note System awareness note", isError = true)
                    val ok = rollbackManager.rollbackById(sid)
                    if (ok) ToolExecutionResult("✅ System awareness note System awareness note snapshot #$sid")
                    else ToolExecutionResult("❌ System awareness note System awareness note snapshot #$sid", isError = true)
                }
                else -> ToolExecutionResult("Unknown tool: $name", isError = true)
            }
        } catch (t: Throwable) {
            ToolExecutionResult("Rollback tool error: ${t.message}", isError = true)
        }
    }

    fun handles(name: String): Boolean = name in HANDLED

    companion object {
        val HANDLED = setOf(
            "rollback_list_recent",
            "rollback_list_groups",
            "rollback_apply_group",
            "rollback_apply_one"
        )
    }
}
