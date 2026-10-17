package com.omnidev.workspace.data.tools

enum class SetupPhase { UNINITIALIZED, PROBING, READY, DEGRADED, FAILED }

data class RuntimeStatus(
    val name: String,
    val available: Boolean,
    val path: String? = null,
    val version: String? = null,
    val source: String? = null
)

data class SetupState(
    val phase: SetupPhase = SetupPhase.UNINITIALIZED,
    val runtimes: Map<String, RuntimeStatus> = emptyMap(),
    val privilegeBackend: String = "none",
    val termuxPrefix: String? = null,
    val probeTimeMs: Long = 0L,
    val error: String? = null
) {
    val isReady: Boolean get() = phase == SetupPhase.READY
    val isDegraded: Boolean get() = phase == SetupPhase.DEGRADED
    val androidShellReady: Boolean get() =
        privilegeBackend.contains("shizuku-user-service") || privilegeBackend == "rish"

    /** Machine-readable probe evidence; root readiness never authorizes the automatic shell route. */
    fun probeEvidence(): String =
        "android-shell=${if (androidShellReady) "ready" else "unavailable"};" +
            "termux=${if (termuxPrefix != null) "ready" else "unavailable"};" +
            "rish=${if (privilegeBackend.contains("rish")) "ready" else "unavailable"}"

    fun runtime(name: String): RuntimeStatus =
        runtimes[name] ?: RuntimeStatus(name, available = false)

    fun summaryLine(): String = buildString {
        append("[${phase.name}] backend=$privilegeBackend ")
        append("runtimes=${runtimes.values.count { it.available }}/${runtimes.size}")
        if (error != null) append(" ⚠️ $error")
    }
}

data class BootstrapStep(
    val id: String,
    val description: String,
    val dependsOn: List<String> = emptyList(),
    val checkFn: suspend () -> Boolean,
    val installFn: suspend () -> BootstrapResult
)

sealed class BootstrapResult {
    data class Success(val message: String) : BootstrapResult()
    data class AlreadyDone(val message: String) : BootstrapResult()
    data class Failed(val reason: String, val hint: String = "") : BootstrapResult()
    data class Skipped(val reason: String) : BootstrapResult()
}

