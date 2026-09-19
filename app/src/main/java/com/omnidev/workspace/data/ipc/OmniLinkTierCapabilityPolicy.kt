package com.omnidev.workspace.data.ipc

/**
 * Host-owned outbound policy. The peer's manifest advertises capabilities but never authorizes
 * their execution. Caller-side checks supplement (never replace) the remote service's own ACL.
 */
object OmniLinkTierCapabilityPolicy {
    private const val IDE_PACKAGE = "dev.mutwakil.androidide"

    private val status = setOf(
        "ide.health", "ide.get_project_context", "ide.get_active_document",
        "ide.get_diagnostics", "ide.get_active_diagnostics",
        "ide.get_build_output", "ide.get_ide_logs", "ide.get_app_logs",
        "ide.get_job", "ide.list_jobs"
    )
    private val read = status + setOf(
        "ide.get_file_info", "ide.read_lines", "ide.read_file", "ide.search_text",
        "ide.list_files", "ide.git_status", "ide.git_diff", "ide.git_history",
        "ide.git_branches", "ide.preview_line_patch"
    )
    private val nonDestructiveJobs = setOf(
        "ide.sync_project", "ide.start_build", "ide.start_tests", "ide.start_lint"
    )

    fun allowed(tier: String, extensionId: String, capability: String): Boolean {
        if (!capability.startsWith("ide.")) return true
        // The SDK also verifies the actual service signer and permission; package is an
        // additional scope constraint, never sufficient evidence on its own.
        if (extensionId.substringBefore('/') != IDE_PACKAGE) return false
        return when (tier.uppercase()) {
            "ADMIN" -> true
            "PRO" -> capability in read || capability in nonDestructiveJobs
            "OEM" -> capability in read || capability in nonDestructiveJobs
            "NORM" -> capability in status || capability in setOf(
                "ide.read_lines", "ide.search_text", "ide.git_status"
            )
            "LITE" -> capability in setOf("ide.health", "ide.get_project_context")
            else -> false
        }
    }
}
