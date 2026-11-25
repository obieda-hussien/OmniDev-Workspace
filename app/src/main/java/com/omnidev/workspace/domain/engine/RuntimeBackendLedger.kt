package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.ToolCall
import com.omnidev.workspace.data.tools.ExecutionDomainGuard
import com.omnidev.workspace.data.tools.ToolExecutionResult

/** Per-run capability circuit. A healthy developer shell cannot heal an Android shell. */
class RuntimeBackendLedger {
    private val blocked = linkedMapOf<String, String>()
    private val observedReadiness = mutableMapOf<String, Boolean>()
    @Volatile var reportOnly: Boolean = false
        private set

    @Synchronized fun hasBlocks(): Boolean = blocked.isNotEmpty()

    @Synchronized fun requestReport() { reportOnly = true }
    @Synchronized fun allowRecovery() { reportOnly = false }

    @Synchronized fun before(call: ToolCall): ToolExecutionResult? {
        val domain = domain(call) ?: return null
        val failure = blocker(domain)
        if (failure == null) return null
        // One real failure already supplied the evidence. Cosmetic script/PATH changes and
        // terminal aliases must not replay the same unavailable capability.
        val nextStep = if (fileReadDomain(call) != null) {
            "For a known file in the authorized project, use the permitted scoped file reader if available. " +
                "Otherwise continue independent work or report the blocked search."
        } else {
            reportOnly = true
            "Report completed work and the blocker."
        }
        return ToolExecutionResult(
            "RUNTIME_BACKEND_BLOCKED: $domain remains unavailable ($failure). Command was NOT executed. " +
                "Changing PATH, package names, terminal aliases or installing binaries cannot restore this capability. " +
                "$nextStep Do not claim the blocked operation succeeded.",
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
            for (backend in observedDomains) {
                when {
                    "$backend=ready" in evidence -> {
                        observedReadiness[backend] = true
                        blocked.remove(backend)
                    }
                    "$backend=unavailable" in evidence -> observedReadiness[backend] = false
                }
            }
            if (blocked.isEmpty()) reportOnly = false
        }
        val domain = domain(call) ?: return
        if (isBackendFailure(result)) {
            blocked[domain] = result.classification.orEmpty()
            observedReadiness[domain] = false
        } else if (!result.isError && result.backend in setOf("shizuku-user-service", "rish", "termux")) {
            // Actual execution success heals only its own execution domain.
            if ((domain == "android-shell" && result.backend != "termux") || domain == result.backend) {
                blocked.remove(domain)
                observedReadiness[domain] = true
            }
            if (blocked.isEmpty()) reportOnly = false
        }
    }

    fun handles(call: ToolCall, result: ToolExecutionResult): Boolean = domain(call) != null && isBackendFailure(result)

    fun isBackendFailure(result: ToolExecutionResult): Boolean = result.isError && result.classification in setOf(
        "SHIZUKU_UNAVAILABLE", "SHIZUKU_PERMISSION_REQUIRED", "ANDROID_BACKEND_UNAVAILABLE",
        "RISH_UNAVAILABLE", "RISH_NATIVE_LOADER_FAILURE", "RISH_DEX_MISSING", "RISH_LAYOUT_BROKEN",
        "TERMUX_RUN_COMMAND_UNAVAILABLE", "TERMUX_EXTERNAL_APPS_DISABLED", "RUNTIME_BACKEND_BLOCKED"
    )

    /** Describe evidence already recorded in this run without triggering a probe. */
    @Synchronized internal fun availabilitySummary(): String? {
        if (observedReadiness.isEmpty()) return null
        return observedDomains.joinToString { backend ->
            val state = when {
                blocker(backend) != null -> "unavailable"
                observedReadiness[backend] == true -> "ready"
                observedReadiness[backend] == false -> "unavailable"
                else -> "unknown"
            }
            "$backend=$state"
        }
    }

    private fun blocker(domain: String): String? =
        blocked[domain] ?: if (domain == "rish") blocked["android-shell"]
            ?.takeIf { it in setOf("SHIZUKU_UNAVAILABLE", "SHIZUKU_PERMISSION_REQUIRED") } else null

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
        private val observedDomains = listOf("android-shell", "termux", "rish")
        private val shellActions = setOf("shell_script", "termux_run", "job_submit")
        private val termuxFileReads = setOf("file_read", "file_list", "file_find", "file_grep", "file_stat")

        private fun fileReadDomain(call: ToolCall): String? = when (call.name) {
            "find_files", "grep_search" -> "android-shell"
            "agent_runtime" -> "termux".takeIf { call.arguments["action"]?.trim()?.lowercase() in termuxFileReads }
            else -> null
        }

        fun domain(call: ToolCall): String? {
            fileReadDomain(call)?.let { return it }
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
