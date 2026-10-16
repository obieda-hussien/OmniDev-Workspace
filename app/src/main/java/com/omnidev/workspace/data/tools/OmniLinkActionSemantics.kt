package com.omnidev.workspace.data.tools

/** Exact public read contracts; unknown actions remain serialized and never auto-retry. */
object OmniLinkActionSemantics {
    val launcherReads = setOf("launcher.health", "launcher.get_settings", "launcher.list_apps", "launcher.get_layout",
        "launcher.list_actions", "launcher.list_scenes", "launcher.list_folders", "launcher.shelf.list",
        "launcher.widget_stacks.list", "launcher.oem.status")
    fun isReadOnly(args: Map<String, String>): Boolean = when (args["action"]) {
        "discover", "list_extensions", "discover_capabilities", "find_capability", "get_manifest", "get_events" -> true
        "execute_action", "execute_capability" -> args["action_name"] in launcherReads
        else -> false
    }
}
