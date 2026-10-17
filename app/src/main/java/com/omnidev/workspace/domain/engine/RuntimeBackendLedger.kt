package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.ExecutionDomainGuard
import com.omnidev.workspace.data.tools.ToolExecutionResult

/** Per-run capability circuit. A healthy developer shell cannot heal an Android shell. */
class RuntimeBackendLedger {
    private val blocked = linkedMapOf<String, String>()
    @Volatile var reportOnly: Boolean = false
        private set

    @Synchronized fun hasBlocks(): Boolean = blocked.isNotEmpty()

    @Synchronized fun requestReport() { reportOnly = true }
    @Synchronized fun allowRecovery() { reportOnly = false }

    @Synchronized fun before(call: ToolCall): ToolExecutionResult? {
        val domain = domain(call) ?: return null
        val failure = blocked[domain] ?: if (domain == "rish") blocked["android-shell"]
            ?.takeIf { it in setOf("SHIZUKU_UNAVAILABLE", "SHIZUKU_PERMISSION_REQUIRED") } else null
        if (failure == null) return null
        // One real failure already supplied the evidence. Cosmetic script/PATH changes and
        // terminal aliases must not replay the same unavailable capability.
        reportOnly = true
        return ToolExecutionResult(
            "RUNTIME_BACKEND_BLOCKED: $domain remains unavailable ($failure). Command was NOT executed. " +
                "Changing PATH, package names, terminal aliases or installing binaries cannot restore this capability. " +
                "Report completed work and the blocker. Do not claim the blocked operation succeeded.",
            isError = true, classification = "RUNTIME_BACKEND_BLOCKED", backend = domain,
            retryable = false, persistentFailure = true
        )
    }

    @Synchronized fun observe(call: ToolCall, result: ToolExecutionResult) {
        // Only runtime-owned probe metadata can reopen a domain; never infer health from
        // stdout containing a backend name, a successful `which`, or a generic PASS.
        if (call.name == "agent_runtime" && call.arguments["action"] in setOf("env_check", "termux_check", "env_probe") &&
            !result.isError && result.backend == "runtime-probe") {
            val evidence = result.verification.orEmpty().split(';').map(String::trim).toSet()
            if ("android-shell=ready" in evidence) blocked.remove("android-shell")
            if ("rish=ready" in evidence) blocked.remove("rish")
            if ("termux=ready" in evidence) blocked.remove("termux")
            if (blocked.isEmpty()) reportOnly = false
        }
        val domain = domain(call) ?: return
        if (isBackendFailure(result)) {
            blocked[domain] = result.classification.orEmpty()
        } else if (!result.isError && result.backend in setOf("shizuku-user-service", "rish", "termux")) {
            // Actual execution success heals only its own execution domain.
            if ((domain == "android-shell" && result.backend != "termux") || domain == result.backend)
                blocked.remove(domain)
            if (blocked.isEmpty()) reportOnly = false
        }
    }

    fun handles(call: ToolCall, result: ToolExecutionResult): Boolean = domain(call) != null && isBackendFailure(result)

    fun isBackendFailure(result: ToolExecutionResult): Boolean = result.isError && result.classification in setOf(
        "SHIZUKU_UNAVAILABLE", "SHIZUKU_PERMISSION_REQUIRED", "ANDROID_BACKEND_UNAVAILABLE",
        "RISH_UNAVAILABLE", "RISH_NATIVE_LOADER_FAILURE", "RISH_DEX_MISSING", "RISH_LAYOUT_BROKEN",
        "TERMUX_RUN_COMMAND_UNAVAILABLE", "TERMUX_EXTERNAL_APPS_DISABLED", "RUNTIME_BACKEND_BLOCKED"
    )

    @Synchronized fun reportSummary(): String = if (blocked.isEmpty()) "" else
        "Runtime limitation: " + blocked.entries.joinToString { "${it.key} (${it.value})" } +
            ". Blocked operations were not completed or verified."

    @Synchronized fun guidance(): String = if (blocked.isEmpty()) "" else buildString {
        append("[Runtime capability limitation] ")
        append(blocked.entries.joinToString { "${it.key}: ${it.value}" })
        append(". These blocked operations were not completed or verified. Preserve completed work. ")
        append("Use independent available tools only when they can satisfy the actual request; otherwise explain the blocker in the user's language. ")
        append("Android shell recovery requires starting Shizuku and granting OmniDev access, followed by a fresh environment probe. ")
        append("Termux recovery requires its RunCommandService permission and allow-external-apps opt-in. ")
        append("For extension control, discover typed omni_link capabilities that may work independently of shell access. ")
        append("Do not repeat backend probes, guess package IDs, change PATH, install unrelated packages, or claim task completion.")
    }

    companion object {
        private val shellActions = setOf("shell_script", "termux_run", "job_submit")
        fun domain(call: ToolCall): String? {
            val args = call.arguments
            val command = when (call.name) {
                "agent_runtime" -> if (args["action"]?.trim()?.lowercase() in shellActions) args["script"] ?: args["command"] else return null
                "run_terminal", "advanced_terminal", "direct_terminal", "shizuku_command", "privileged_tool" -> args["command"]
                else -> return null
            }.orEmpty()
            val family = ExecutionDomainGuard.findViolation(command)?.commandFamily
            if (family == "su") return null // Root is an explicit, separate capability.
            if (family == "rish") return "rish"
            if (family != null || call.name in setOf("shizuku_command", "privileged_tool")) return "android-shell"
            return if (call.name in setOf("agent_runtime", "advanced_terminal", "direct_terminal")) "termux" else null
        }
    }
}
