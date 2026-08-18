package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.rollback.RollbackManager

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * RollbackTools — [Localized] Agent [Localized] Rollback (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * [Localized] [Localized] [RollbackManager] [Localized] Agent [Localized] [Localized] [Localized]:
 *   - rollback_list_recent: [Localized] [Localized] snapshots
 *   - rollback_list_groups: [Localized] groups [Localized] [Localized] ([Localized] [Localized] rollback)
 *   - rollback_apply_group: [Localized] [Localized] [Localized] group [Localized]
 *   - rollback_apply_one: [Localized] snapshot [Localized]
 *
 * Mobile-first: [Localized] [Localized] [Localized] [Localized] root [Localized] [Localized] [Localized] File API.
 */
class RollbackTools(private val rollbackManager: RollbackManager) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "rollback_list_recent",
            description = "[Localized] [Localized] N rollback snapshots [Localized] [Localized] [Localized]. " +
                "[Localized] [Localized] rollback_apply_one [Localized] [Localized] snapshot id.",
            parameters = listOf(
                ToolParameter("limit", "integer", "[Localized] [Localized] (1-50[Localized] [Localized] 20)", required = false)
            )
        ),
        ToolDefinition(
            name = "rollback_list_groups",
            description = "[Localized] [Localized] action groups [Localized] [Localized] [Localized] [Localized] [Localized] group. " +
                "[Localized] [Localized] rollback_apply_group.",
            parameters = listOf(
                ToolParameter("limit", "integer", "[Localized] [Localized] (1-50[Localized] [Localized] 20)", required = false)
            )
        ),
        ToolDefinition(
            name = "rollback_apply_group",
            description = "[Localized] [Localized] [Localized] [Localized] action group [Localized] [Localized]. " +
                "[Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized]. [Localized] [Localized] [Localized]!",
            parameters = listOf(
                ToolParameter("group_id", "string", "[Localized] [Localized] action group [Localized] rollback_list_groups")
            )
        ),
        ToolDefinition(
            name = "rollback_apply_one",
            description = "[Localized] snapshot [Localized] [Localized] ([Localized] [Localized]) [Localized] [Localized] [Localized] rollback.",
            parameters = listOf(
                ToolParameter("snapshot_id", "integer", "[Localized] [Localized] snapshot [Localized] rollback_list_recent")
            )
        )
    )

    /** [Localized] null [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] wrapper ([Localized] fall-through). */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            when (name) {
                "rollback_list_recent" -> {
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                    val items = rollbackManager.listRecent(limit)
                    if (items.isEmpty()) ToolExecutionResult("[Localized] [Localized] snapshots [Localized].")
                    else ToolExecutionResult(buildString {
                        appendLine("📜 [Localized] ${items.size} snapshot:")
                        for (s in items) {
                            val rolled = if (s.rolledBack) " [[Localized]]" else ""
                            val pinned = if (s.pinned) " 📌" else ""
                            appendLine("  #${s.id}$pinned$rolled — ${s.toolName} → ${s.filePath}")
                            if (s.reason.isNotBlank()) appendLine("       [Localized]: ${s.reason.take(120)}")
                        }
                    })
                }
                "rollback_list_groups" -> {
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                    val groups = rollbackManager.listGroups(limit)
                    if (groups.isEmpty()) ToolExecutionResult("[Localized] [Localized] action groups [Localized].")
                    else ToolExecutionResult(buildString {
                        appendLine("📦 [Localized] ${groups.size} action group:")
                        for (g in groups) {
                            appendLine("  ${g.actionGroupId} — ${g.fileCount} [Localized] | ${g.toolName ?: "?"}")
                            if (!g.reason.isNullOrBlank()) appendLine("       ${g.reason.take(120)}")
                        }
                    })
                }
                "rollback_apply_group" -> {
                    val gid = args["group_id"]?.trim()
                        ?: return ToolExecutionResult("group_id [Localized]", isError = true)
                    val res = rollbackManager.rollbackGroup(gid)
                    val errs = if (res.errors.isEmpty()) "" else
                        "\n⚠️ [Localized] (${res.errors.size}):\n${res.errors.joinToString("\n").take(800)}"
                    ToolExecutionResult(
                        "✅ [Localized] [Localized] ${res.restored}/${res.attempted} [Localized] [Localized] group $gid$errs",
                        isError = res.restored == 0
                    )
                }
                "rollback_apply_one" -> {
                    val sid = args["snapshot_id"]?.toLongOrNull()
                        ?: return ToolExecutionResult("snapshot_id [Localized] [Localized]", isError = true)
                    val ok = rollbackManager.rollbackById(sid)
                    if (ok) ToolExecutionResult("✅ [Localized] [Localized] snapshot #$sid")
                    else ToolExecutionResult("❌ [Localized] [Localized] snapshot #$sid", isError = true)
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
