package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.rollback.RollbackManager

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * RollbackTools — أدوات Agent لإدارة Rollback (Brain 2.0)
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * تعرض قدرات [RollbackManager] للـ Agent عبر أربع أدوات:
 *   - rollback_list_recent: عرض آخر snapshots
 *   - rollback_list_groups: عرض groups مع ملخص (للاختيار قبل rollback)
 *   - rollback_apply_group: استعادة كل ملفات group ذرّياً
 *   - rollback_apply_one: استعادة snapshot بعينه
 *
 * Mobile-first: كل العمليات تعمل بدون root وتعتمد فقط على File API.
 */
class RollbackTools(private val rollbackManager: RollbackManager) {

    fun getDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "rollback_list_recent",
            description = "عرض آخر N rollback snapshots مع المسار والسبب. " +
                "استخدم قبل rollback_apply_one لمعرفة الـ snapshot id.",
            parameters = listOf(
                ToolParameter("limit", "integer", "عدد العناصر (1-50، الافتراضي 20)", required = false)
            )
        ),
        ToolDefinition(
            name = "rollback_list_groups",
            description = "عرض آخر action groups مع عدد الملفات في كل group. " +
                "استخدم قبل rollback_apply_group.",
            parameters = listOf(
                ToolParameter("limit", "integer", "عدد العناصر (1-50، الافتراضي 20)", required = false)
            )
        ),
        ToolDefinition(
            name = "rollback_apply_group",
            description = "إلغاء كل التغييرات في action group واحد ذرّياً. " +
                "يستعيد كل الملفات إلى حالتها قبل العملية. تأكد قبل الاستخدام!",
            parameters = listOf(
                ToolParameter("group_id", "string", "معرّف الـ action group من rollback_list_groups")
            )
        ),
        ToolDefinition(
            name = "rollback_apply_one",
            description = "استعادة snapshot واحد فقط (ملف واحد) من سجل الـ rollback.",
            parameters = listOf(
                ToolParameter("snapshot_id", "integer", "معرّف الـ snapshot من rollback_list_recent")
            )
        )
    )

    /** يُرجع null إذا الأداة ليست مملوكة لهذا الـ wrapper (لتمرير fall-through). */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            when (name) {
                "rollback_list_recent" -> {
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                    val items = rollbackManager.listRecent(limit)
                    if (items.isEmpty()) ToolExecutionResult("لا توجد snapshots مسجّلة.")
                    else ToolExecutionResult(buildString {
                        appendLine("📜 آخر ${items.size} snapshot:")
                        for (s in items) {
                            val rolled = if (s.rolledBack) " [مُستعاد]" else ""
                            val pinned = if (s.pinned) " 📌" else ""
                            appendLine("  #${s.id}$pinned$rolled — ${s.toolName} → ${s.filePath}")
                            if (s.reason.isNotBlank()) appendLine("       السبب: ${s.reason.take(120)}")
                        }
                    })
                }
                "rollback_list_groups" -> {
                    val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                    val groups = rollbackManager.listGroups(limit)
                    if (groups.isEmpty()) ToolExecutionResult("لا توجد action groups مسجّلة.")
                    else ToolExecutionResult(buildString {
                        appendLine("📦 آخر ${groups.size} action group:")
                        for (g in groups) {
                            appendLine("  ${g.actionGroupId} — ${g.fileCount} ملف | ${g.toolName ?: "?"}")
                            if (!g.reason.isNullOrBlank()) appendLine("       ${g.reason.take(120)}")
                        }
                    })
                }
                "rollback_apply_group" -> {
                    val gid = args["group_id"]?.trim()
                        ?: return ToolExecutionResult("group_id مطلوب", isError = true)
                    val res = rollbackManager.rollbackGroup(gid)
                    val errs = if (res.errors.isEmpty()) "" else
                        "\n⚠️ أخطاء (${res.errors.size}):\n${res.errors.joinToString("\n").take(800)}"
                    ToolExecutionResult(
                        "✅ تمت استعادة ${res.restored}/${res.attempted} ملف من group $gid$errs",
                        isError = res.restored == 0
                    )
                }
                "rollback_apply_one" -> {
                    val sid = args["snapshot_id"]?.toLongOrNull()
                        ?: return ToolExecutionResult("snapshot_id رقمي مطلوب", isError = true)
                    val ok = rollbackManager.rollbackById(sid)
                    if (ok) ToolExecutionResult("✅ تمت استعادة snapshot #$sid")
                    else ToolExecutionResult("❌ فشل استعادة snapshot #$sid", isError = true)
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
