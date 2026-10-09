package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.rollback.RollbackManager

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * RollbackTools — Agent tools for rollback management (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Exposes [RollbackManager] capabilities to the agent through four tools:
 *   - rollback_list_recent: List recent snapshots
 *   - rollback_list_groups: List groups with summaries for rollback selection
 *   - rollback_apply_group: Restore all files in a group atomically
 *   - rollback_apply_one: Restore a specific snapshot
 *
 * Mobile-first: All operations use the File API without requiring root.
 */
class RollbackTools(private val rollbackManager: RollbackManager) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "rollback_list_recent",
            description = "List the last N rollback snapshots with their paths and reasons. " +
                "Use before rollback_apply_one to obtain a snapshot ID.",
            parameters = listOf(
                ToolParameter("limit", "integer", "Number of items (1-50; default: 20)", required = false)
            )
        ),
        ToolDefinition(
            name = "rollback_list_groups",
            description = "List recent action groups with their file counts. " +
                "Use before rollback_apply_group.",
            parameters = listOf(
                ToolParameter("limit", "integer", "Number of items (1-50; default: 20)", required = false)
            )
        ),
        ToolDefinition(
            name = "rollback_apply_group",
            description = "Atomically undo all changes in one action group. " +
                "Restores every file to its state before the action. Confirm before use!",
            parameters = listOf(
                ToolParameter("group_id", "string", "Action group ID from rollback_list_groups")
            )
        ),
        ToolDefinition(
            name = "rollback_apply_one",
            description = "Restore one file snapshot from the rollback history.",
            parameters = listOf(
                ToolParameter("snapshot_id", "integer", "Snapshot ID from rollback_list_recent")
            )
        )
    )

    /** Returns null for tools not handled by this wrapper, allowing fall-through. */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            when (name) {
                "rollback_list_recent" -> {
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                    val items = rollbackManager.listRecent(limit)
                    if (items.isEmpty()) ToolExecutionResult("No snapshots have been recorded.")
                    else ToolExecutionResult(buildString {
                        appendLine("📜 Last ${items.size} snapshots:")
                        for (s in items) {
                            val rolled = if (s.rolledBack) " [restored]" else ""
                            val pinned = if (s.pinned) " 📌" else ""
                            appendLine("  #${s.id}$pinned$rolled — ${s.toolName} → ${s.filePath}")
                            if (s.reason.isNotBlank()) appendLine("       Reason: ${s.reason.take(120)}")
                        }
                    })
                }
                "rollback_list_groups" -> {
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                    val groups = rollbackManager.listGroups(limit)
                    if (groups.isEmpty()) ToolExecutionResult("No action groups have been recorded.")
                    else ToolExecutionResult(buildString {
                        appendLine("📦 Last ${groups.size} action groups:")
                        for (g in groups) {
                            appendLine("  ${g.actionGroupId} — ${g.fileCount} files | ${g.toolName ?: "?"}")
                            if (!g.reason.isNullOrBlank()) appendLine("       ${g.reason.take(120)}")
                        }
                    })
                }
                "rollback_apply_group" -> {
                    val gid = args["group_id"]?.trim()
                        ?: return ToolExecutionResult("group_id is required", isError = true)
                    val res = rollbackManager.rollbackGroup(gid)
                    val errs = if (res.errors.isEmpty()) "" else
                        "\n⚠️ Errors (${res.errors.size}):\n${res.errors.joinToString("\n").take(800)}"
                    ToolExecutionResult(
                        "✅ Restored ${res.restored}/${res.attempted} files from group $gid$errs",
                        isError = res.restored == 0
                    )
                }
                "rollback_apply_one" -> {
                    val sid = args["snapshot_id"]?.toLongOrNull()
                        ?: return ToolExecutionResult("A numeric snapshot_id is required", isError = true)
                    val ok = rollbackManager.rollbackById(sid)
                    if (ok) ToolExecutionResult("✅ Restored snapshot #$sid")
                    else ToolExecutionResult("❌ Failed to restore snapshot #$sid", isError = true)
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
