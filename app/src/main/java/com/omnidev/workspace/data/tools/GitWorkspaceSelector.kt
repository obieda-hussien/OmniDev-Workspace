package com.omnidev.workspace.data.tools

import java.io.File

/**
 * Uses an already selected, physically present Git checkout only when the model
 * omits cwd. Never discovers a new repository, clones, initializes, or changes
 * an explicit path. Git itself remains authoritative for worktree validity.
 */
internal object GitWorkspaceSelector {
    fun injectVerifiedScope(action: String, arguments: Map<String, String>, scopePath: String?): Map<String, String> {
        if (!action.startsWith("git_") || action in setOf("git_clone", "git_init") ||
            !arguments["cwd"].isNullOrBlank() || scopePath.isNullOrBlank()) return arguments
        val directory = File(scopePath)
        if (!directory.isDirectory || !File(directory, ".git").exists()) return arguments
        return arguments + ("cwd" to directory.absolutePath)
    }
}
