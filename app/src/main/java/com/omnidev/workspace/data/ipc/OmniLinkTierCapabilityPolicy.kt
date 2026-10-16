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

    private val launcherPackages = setOf(
        "app.lawnchair", "app.lawnchair.debug", "app.lawnchair.nightly",
        "app.lawnchair.nightly.debug", "app.lawnchair.play", "app.lawnchair.play.debug"
    )
    private val launcherRead = setOf("launcher.health", "launcher.get_settings", "launcher.list_apps")
    private val experienceRead = setOf(
        "launcher.get_layout", "launcher.list_actions", "launcher.list_scenes", "launcher.list_folders", "launcher.shelf.list",
        "launcher.widget_stacks.list", "launcher.oem.status"
    )
    private val experienceControl = setOf(
        "launcher.move_items", "launcher.set_grid", "launcher.create_folder", "launcher.open_action", "launcher.save_scene", "launcher.apply_scene", "launcher.set_folder_rule",
        "launcher.set_alias", "launcher.shelf.publish", "launcher.widget_stacks.select"
    )
    private val enterpriseControl = setOf("launcher.oem.suspend_app")
    private val launcherControl = setOf("launcher.set_preference", "launcher.open_app", "launcher.open_drawer")

    fun allowed(tier: String, extensionId: String, capability: String): Boolean {
        if (capability.startsWith("launcher.")) {
            if (extensionId.substringBefore('/') !in launcherPackages) return false
            return when (tier.uppercase()) {
                "ADMIN", "OEM" -> capability in launcherRead || capability in launcherControl || capability in experienceRead || capability in experienceControl || capability in enterpriseControl
                "PRO" -> capability in launcherRead || capability in launcherControl || capability in experienceRead || capability in experienceControl
                "NORM" -> capability in launcherRead || capability in experienceRead || capability in setOf("launcher.open_app", "launcher.open_drawer", "launcher.open_action")
                "LITE" -> capability in launcherRead || capability in launcherControl
                else -> false
            }
        }
        if (!capability.startsWith("ide.")) return tier.uppercase() in setOf("LITE", "NORM", "PRO", "OEM", "ADMIN")
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
            "LITE" -> capability in read || capability in setOf("ide.apply_line_patch", "ide.write_file", "ide.create_file")
            else -> false
        }
    }
}

