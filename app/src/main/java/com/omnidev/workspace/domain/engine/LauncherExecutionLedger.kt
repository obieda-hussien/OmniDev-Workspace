package com.omnidev.workspace.domain.engine

import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

/** Minimum execution evidence, independent of the model's success prose. No authorization grants. */
internal class LauncherExecutionLedger(objective: String) {
    private val normalized = Normalizer.normalize(objective, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').lowercase(Locale.ROOT)
    val requiresAction = listOf("launcher", "home screen", "اللانشر", "اللنشر", "التطبيقات", "المجلدات", "الهوم").any(normalized::contains) &&
        listOf("arrange", "organize", "rearrange", "move", "create", "set grid", "change layout", "رتب", "نظم", "ظبط", "انقل", "اعمل", "انشئ", "غير التخطيط").any(normalized::contains) &&
        listOf("do not execute", "plan only", "متنفذش", "لا تنفذ", "خطة فقط").none(normalized::contains)
    private val homeOrganization = listOf("home screen", "الشاشة", "الشاشه", "الهوم").any(normalized::contains)
    private val explicitFileGoal = listOf("export", "save a file", "تصدير", "احفظ ملف").any(normalized::contains)
    private var mutated = false
    private var verified = false
    private var expectedRevision: String? = null
    private var failure: String? = null
    private var recoveries = 0
    val exhausted get() = recoveries > 2
    val needsActionGuidance get() = requiresAction && (!mutated || !verified)

    fun checkTool(name: String, args: Map<String, String>): String? {
        if (!requiresAction || explicitFileGoal) return null
        val shell = name in setOf("run_terminal", "run_script", "direct_terminal", "advanced_terminal", "root_shell_tool", "shizuku_command")
        val unrelatedFile = name in setOf("create_file", "write_file", "append_to_file", "patch_file", "delete_file") ||
            (name == "agent_runtime" && (args["action"].orEmpty().startsWith("file_") || args["action"] in setOf("shell_script", "termux_run")))
        return if (shell || unrelatedFile) "Launcher organization requires typed omni_link capabilities. A shell directory or JSON file does not change Home. Discover the actual provider; use its exact schema and normal approval flow." else null
    }

    fun observe(name: String, args: Map<String, String>, output: String, isError: Boolean) {
        if (!requiresAction || name != "omni_link") return
        val action = args["action_name"].orEmpty()
        if (args["action"] !in setOf("execute_action", "execute_capability") || !action.startsWith("launcher.")) return
        val envelope = runCatching { JSONObject(output.substring(output.indexOf('{'))) }.getOrNull()
        if (isError || envelope?.optBoolean("ok") != true) { failure = envelope?.optString("code") ?: "tool_failure"; return }
        val outcome = runCatching { JSONObject(envelope.getString("result_json")) }.getOrNull() ?: return
        if (!outcome.optString("type").endsWith("ActionOutcome.Success")) return
        val data = outcome.optJSONObject("data") ?: return
        if (action == "launcher.get_layout" && mutated && !verified) {
            val revision = data.optString("revision")
            if (revision.isNotBlank() && revision != expectedRevision) verified = true
            return
        }
        val payload = runCatching { JSONObject(args["json_payload"] ?: "{}") }.getOrNull()
        if (args["dry_run"] == "true" || data.optBoolean("dryRun")) return
        if (action in mutations && (!homeOrganization || action in homeMutations)) {
            mutated = true
            expectedRevision = payload?.optString("expectedRevision")?.takeIf { it.isNotBlank() }
            verified = !data.optBoolean("reloadScheduled")
            failure = null
        }
    }

    fun completionFeedback(): String? {
        if (!needsActionGuidance) return null
        recoveries++
        return if (mutated) "Launcher mutation only scheduled a reload. Read launcher.get_layout again and verify a fresh revision and the requested arrangement before finishing. Do not repeat the mutation blindly."
        else "The user requested Launcher changes, but no real mutation succeeded. Read-only discovery/health/layout/apps is not completion. Discover the actual extension (never guess package IDs), fetch needed pages/schemas, then execute the appropriate approved capability. For Home folders use launcher.create_home_folder, not drawer rules or files. If denied, report the real blocker without claiming success."
    }
    fun failureMessage() = "Launcher changes remain unverified: " + (failure ?: if (mutated) "reload not verified" else "no successful Launcher mutation")

    companion object {
        private val homeMutations = setOf("launcher.move_items", "launcher.set_grid", "launcher.create_home_folder")
        private val mutations = homeMutations + setOf("launcher.create_folder", "launcher.set_folder_rule", "launcher.set_alias", "launcher.apply_scene", "launcher.save_scene", "launcher.set_preference", "launcher.widget_stacks.select")
    }
}
