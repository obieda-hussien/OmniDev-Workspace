package com.omnidev.workspace.data.tools

/** A missing executable before a pure Git read ran is a skipped capability, never successful evidence. */
object MissingGitCapability {
    private val read = "git\\s+(?:status|log|diff|show|rev-parse)(?:\\s+[A-Za-z0-9_./:=@%+\\-]+)*"
    private val commands = Regex("^\\s*$read(?:\\s*&&\\s*$read)*\\s*$")
    private val missing = Regex("(?im)^(?:/[^\\s:]+:\\s*)?(?:[0-9]+:\\s*)?git:\\s*(?:inaccessible or not found|not found)\\s*$")

    fun classify(command: String, exitCode: Int, stdout: String, stderr: String): ToolExecutionResult? {
        if (exitCode != 127 || stdout.isNotBlank() || !commands.matches(command) || !missing.matches(stderr.trim())) return null
        return ToolExecutionResult(
            "Skipped: TOOL_DEPENDENCY_MISSING: git is unavailable in app-shell (exit 127). The requested Git reads did not execute. Use github_manager for remote GitHub reads, or verify a developer runtime for a local checkout. This is not completion evidence.",
            true, classification = "TOOL_DEPENDENCY_MISSING", exitCode = 127, backend = "app-shell", retryable = false)
    }
}
