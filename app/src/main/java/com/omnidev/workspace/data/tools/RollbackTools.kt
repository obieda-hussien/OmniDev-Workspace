package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.rollback.RollbackManager

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * RollbackTools — Context note Agent Context note Rollback (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Context note Context note [RollbackManager] Context note Agent Context note Context note Context note:
 *   - rollback_list_recent: Context note Context note snapshots
 *   - rollback_list_groups: Context note groups Context note Context note (Context note Context note rollback)
 *   - rollback_apply_group: Context note Context note Context note group Context note
 *   - rollback_apply_one: Context note snapshot Context note
 *
 * Mobile-first: Context note Context note Context note Context note root Context note Context note Context note File API.
 */
class RollbackTools(private val rollbackManager: RollbackManager) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "rollback_list_recent",
            description = "Info Info N rollback snapshots Info Info Info. " +
                "Info Info rollback_apply_one Info Info snapshot id.",
            parameters = listOf(
                ToolParameter("limit", "integer", "Info Info (1-50Info Info 20)", required = false)
            )
        ),
        ToolDefinition(
            name = "rollback_list_groups",
            description = "Info Info action groups Info Info Info Info Info group. " +
                "Info Info rollback_apply_group.",
            parameters = listOf(
                ToolParameter("limit", "integer", "Info Info (1-50Info Info 20)", required = false)
            )
        ),
        ToolDefinition(
            name = "rollback_apply_group",
            description = "Info Info Info Info action group Info Info. " +
                "Info Info Info Info Info Info Info. Info Info Info!",
            parameters = listOf(
                ToolParameter("group_id", "string", "Info Info action group Info rollback_list_groups")
            )
        ),
        ToolDefinition(
            name = "rollback_apply_one",
            description = "Info snapshot Info Info (Info Info) Info Info Info rollback.",
            parameters = listOf(
                ToolParameter("snapshot_id", "integer", "Info Info snapshot Info rollback_list_recent")
            )
        )
    )

    /** Context note null Context note Context note Context note Context note Context note Context note wrapper (Context note fall-through). */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            when (name) {
                "rollback_list_recent" -> {
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                    val items = rollbackManager.listRecent(limit)
                    if (items.isEmpty()) ToolExecutionResult("Info Info snapshots Info.")
                    else ToolExecutionResult(buildString {
                        appendLine("📜 Info ${items.size} snapshot:")
                        for (s in items) {
                            val rolled = if (s.rolledBack) " [Info]" else ""
                            val pinned = if (s.pinned) " 📌" else ""
                            appendLine("  #${s.id}$pinned$rolled — ${s.toolName} → ${s.filePath}")
                            if (s.reason.isNotBlank()) appendLine("       Info: ${s.reason.take(120)}")
                        }
                    })
                }
                "rollback_list_groups" -> {
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                    val groups = rollbackManager.listGroups(limit)
                    if (groups.isEmpty()) ToolExecutionResult("Info Info action groups Info.")
                    else ToolExecutionResult(buildString {
                        appendLine("📦 Info ${groups.size} action group:")
                        for (g in groups) {
                            appendLine("  ${g.actionGroupId} — ${g.fileCount} Info | ${g.toolName ?: "?"}")
                            if (!g.reason.isNullOrBlank()) appendLine("       ${g.reason.take(120)}")
                        }
                    })
                }
                "rollback_apply_group" -> {
                    val gid = args["group_id"]?.trim()
                        ?: return ToolExecutionResult("group_id Info", isError = true)
                    val res = rollbackManager.rollbackGroup(gid)
                    val errs = if (res.errors.isEmpty()) "" else
                        "\n⚠️ Info (${res.errors.size}):\n${res.errors.joinToString("\n").take(800)}"
                    ToolExecutionResult(
                        "✅ Info Info ${res.restored}/${res.attempted} Info Info group $gid$errs",
                        isError = res.restored == 0
                    )
                }
                "rollback_apply_one" -> {
                    val sid = args["snapshot_id"]?.toLongOrNull()
                        ?: return ToolExecutionResult("snapshot_id Info Info", isError = true)
                    val ok = rollbackManager.rollbackById(sid)
                    if (ok) ToolExecutionResult("✅ Info Info snapshot #$sid")
                    else ToolExecutionResult("❌ Info Info snapshot #$sid", isError = true)
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
