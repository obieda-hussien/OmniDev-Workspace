package com.omnidev.workspace.domain.engine

import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

/** Minimum execution evidence, independent of the model's success prose. No authorization grants. */
internal class LauncherExecutionLedger(objective: String) {
    private val normalized = Normalizer.normalize(objective, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').lowercase(Locale.ROOT)
    private val words = normalized.split(Regex("[^\\p{L}\\p{N}]+")).toSet()
    private val explanatory = listOf("how do i", "how to", "how can i", "how would", "how does", "explain", "tell me how", "ازاي", "كيف", "اشرح", "علمني").any(normalized::contains)
    private val projectWork = listOf("app project", "launcher project", "source code", "repository", "codebase", "pull request", "gradle", "android studio", "implement", "build a launcher", "مشروع", "ريبو", "الكود", "برمجة").any(normalized::contains)
    private val imperative = words.any { it in setOf("arrange", "organize", "organise", "rearrange", "sort", "move", "group", "create", "set", "change") } ||
        listOf("رتب", "نظم", "ظبط", "انقل", "اعمل", "انشئ", "غير التخطيط").any(normalized::contains)
    val requiresAction = !explanatory && !projectWork && imperative &&
        listOf("launcher", "home screen", "homescreen", "home-screen", "اللانشر", "اللنشر", "التطبيقات", "المجلدات", "الهوم", "الشاشة الرئيسية").any(normalized::contains) &&
        listOf("do not execute", "plan only", "متنفذش", "لا تنفذ", "خطة فقط").none(normalized::contains)
    private val homeOrganization = "home" in words || "homescreen" in words || listOf("الشاشة", "الشاشه", "الهوم", "الصفحة الرئيسية").any(normalized::contains)
    private val explicitFileGoal = listOf("export", "save a file", "تصدير", "احفظ ملف").any(normalized::contains)
    private var successfulAction: String? = null
    private var mutated = false
    private var verified = false
    private var expectedRevision: String? = null
    private var latestLayoutRevision: String? = null
    private var layoutPagesComplete: Boolean = false
    private var pendingLayoutOffset: Int? = null
    private var layoutPageRevision: String? = null
    private var layoutRestartRequired: Boolean = false
    private var failure: String? = null
    private var recoveries = 0
    val exhausted get() = recoveries > 2
    val needsActionGuidance get() = requiresAction && (!mutated || !verified)

    fun checkTool(name: String, args: Map<String, String>): String? {
        if (!requiresAction || explicitFileGoal) return null
        val shell = name in setOf("run_terminal", "run_script", "direct_terminal", "advanced_terminal", "root_shell_tool", "shizuku_command")
        val unrelatedFile = name in setOf("create_file", "write_file", "append_to_file", "patch_file", "delete_file") ||
            (name == "agent_runtime" && (args["action"].orEmpty().startsWith("file_") || args["action"] in setOf("shell_script", "termux_run")))
        if (shell || unrelatedFile) {
            return "Launcher organization requires typed omni_link capabilities. A shell directory or JSON file does not change Home. Discover the actual provider; use its exact schema and normal approval flow."
        }
        if (name == "omni_link" &&
            args["action"] in setOf("execute_action", "execute_capability") &&
            args["action_name"] == "launcher.get_layout"
        ) {
            val payload = runCatching { JSONObject(args["json_payload"] ?: "{}") }.getOrNull() ?: JSONObject()
            val requestedOffset = payload.optInt("offset", 0)
            if (layoutRestartRequired && requestedOffset != 0) {
                return "Launcher layout changed while paging. Restart launcher.get_layout from offset 0; do not combine pages from different revisions."
            }
            val expectedOffset = pendingLayoutOffset
            if (expectedOffset != null && requestedOffset != expectedOffset) {
                return "Launcher layout pagination expected offset=$expectedOffset, but the request used offset=$requestedOffset. Follow nextOffset exactly before planning a mutation."
            }
            if (expectedOffset == null && layoutPageRevision == null && requestedOffset != 0) {
                return "Start launcher.get_layout at offset=0 before requesting later pages."
            }
        }
        if (name == "omni_link" &&
            args["action"] in setOf("execute_action", "execute_capability") &&
            args["action_name"] in homeMutations
        ) {
            val payload = runCatching { JSONObject(args["json_payload"] ?: "{}") }.getOrNull()
                ?: return "Invalid Launcher payload. Fetch launcher.get_layout and construct a valid JSON payload."
            val expected = payload.optString("expectedRevision").takeIf { it.isNotBlank() }
                ?: return "Launcher mutation blocked: include expectedRevision from the latest successful launcher.get_layout. Do not send revision; the required key is expectedRevision."
            val latest = latestLayoutRevision
                ?: return "Launcher mutation blocked: no current layout revision is in memory. Call launcher.get_layout first, then send its revision as expectedRevision."
            if (expected != latest) {
                return "Launcher mutation blocked: stale expectedRevision. Fetch launcher.get_layout again and use its newest revision as expectedRevision. No mutation was sent."
            }
            if (!layoutPagesComplete) {
                return "Launcher mutation blocked: get_layout is paginated. Fetch the remaining layout page using offset=${pendingLayoutOffset ?: "from nextOffset"} and its exact capability schema; do not mutate until nextOffset is null."
            }
        }
        return null
    }

    fun observe(name: String, args: Map<String, String>, output: String, isError: Boolean) {
        if (!requiresAction || name != "omni_link") return
        val action = args["action_name"].orEmpty()
        if (args["action"] !in setOf("execute_action", "execute_capability") || !action.startsWith("launcher.")) return
        val envelope = runCatching { JSONObject(output.substring(output.indexOf('{'))) }.getOrNull()
        if (isError || envelope?.optBoolean("ok") != true) {
            failure = envelope?.optString("code") ?: "tool_failure"
            if (action in homeMutations) {
                latestLayoutRevision = null
                layoutPagesComplete = false
                pendingLayoutOffset = null
                layoutPageRevision = null
                layoutRestartRequired = false
            }
            return
        }
        val outcome = runCatching { JSONObject(envelope.getString("result_json")) }.getOrNull() ?: return
        if (!outcome.optString("type").endsWith("ActionOutcome.Success")) return
        val data = outcome.optJSONObject("data") ?: return
        if (action == "launcher.get_layout") {
            val revision = data.optString("revision")
            val payload = runCatching { JSONObject(args["json_payload"] ?: "{}") }.getOrNull() ?: JSONObject()
            val requestedOffset = payload.optInt("offset", 0)
            val next = if (data.has("nextOffset") && !data.isNull("nextOffset")) {
                data.optInt("nextOffset").takeIf { it >= 0 }
            } else null

            if (next != null && next <= requestedOffset) {
                layoutRestartRequired = true
                pendingLayoutOffset = 0
                latestLayoutRevision = null
                layoutPagesComplete = false
                failure = "layout_pagination_did_not_advance"
                return
            }

            if (requestedOffset == 0) {
                layoutPageRevision = revision.takeIf { it.isNotBlank() }
                latestLayoutRevision = revision.takeIf { it.isNotBlank() }
                layoutRestartRequired = false
                pendingLayoutOffset = next
                layoutPagesComplete = next == null
            } else {
                val expectedOffset = pendingLayoutOffset
                if (layoutRestartRequired || expectedOffset != requestedOffset ||
                    revision.isBlank() || revision != layoutPageRevision
                ) {
                    layoutRestartRequired = true
                    pendingLayoutOffset = 0
                    latestLayoutRevision = null
                    layoutPagesComplete = false
                    failure = "layout_changed_during_pagination"
                    return
                }
                latestLayoutRevision = revision
                pendingLayoutOffset = next
                layoutPagesComplete = next == null
            }

            if (mutated && !verified && revision.isNotBlank() && revision != expectedRevision) verified = true
            failure = null
            return
        }
        val payload = runCatching { JSONObject(args["json_payload"] ?: "{}") }.getOrNull()
        if (args["dry_run"] == "true" || data.optBoolean("dryRun")) return
        if (action in mutations && (!homeOrganization || action in homeMutations)) {
            successfulAction = action
            mutated = true
            expectedRevision = payload?.optString("expectedRevision")?.takeIf { it.isNotBlank() }
            latestLayoutRevision = null
            layoutPagesComplete = false
            pendingLayoutOffset = null
            layoutPageRevision = null
            layoutRestartRequired = false
            verified = action !in homeMutations && !data.optBoolean("reloadScheduled")
            failure = null
        }
    }

    /** Carry acknowledged side effects across a correction; read them back instead of replaying. */
    fun redirected(objective: String): LauncherExecutionLedger = LauncherExecutionLedger(objective).also { next ->
        val action = successfulAction
        if (mutated && action != null && (!next.homeOrganization || action in homeMutations)) {
            next.successfulAction = action
            next.mutated = true
            next.expectedRevision = expectedRevision
            // A changed Home objective must inspect the committed layout again, never repeat the write.
            next.verified = if (action in homeMutations) false else verified
            next.failure = failure
        }
    }

    fun completionFeedback(): String? {
        if (!needsActionGuidance) return null
        recoveries++
        return if (mutated) "A Launcher mutation already succeeded. Read every launcher.get_layout page until nextOffset is null, then verify the fresh revision and requested arrangement before finishing. Do not repeat the mutation blindly."
        else "The user requested Launcher changes, but no real mutation succeeded. Read-only discovery/health/layout/apps is not completion. Fetch every get_layout page until nextOffset is null, fetch the exact capability schemas, then execute the appropriate approved capability. For Home folders use launcher.create_home_folder, not drawer rules or files. If denied, report the real blocker without claiming success."
    }
    fun failureMessage() = "Launcher changes remain unverified: " + (failure ?: if (mutated) "reload not verified" else "no successful Launcher mutation")

    companion object {
        private val homeMutations = setOf("launcher.move_items", "launcher.set_grid", "launcher.create_home_folder")
        private val mutations = homeMutations + setOf("launcher.create_folder", "launcher.set_folder_rule", "launcher.set_alias", "launcher.apply_scene", "launcher.save_scene", "launcher.set_preference", "launcher.widget_stacks.select")
    }
}
