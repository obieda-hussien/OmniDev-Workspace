package com.omnidev.workspace.data.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Executes Git operations scoped to the user's active Target Context directory.
 *
 * Supported actions: `status`, `diff`, `log`, `commit`, `branch`, `checkout`,
 * `stash`, and `add`. All commands are executed through [ProcessBuilder] with
 * the working directory pinned to the supplied scope path, ensuring the agent
 * operates inside the correct repository.
 *
 * Uses the same API-24-compatible timeout pattern as
 * [FileToolManager.runTerminal]: a dedicated wait-thread joined with a
 * millisecond timeout, followed by [Process.destroy] on timeout (avoids
 * [Process.destroyForcibly] which requires API 26).
 */
object GitManagerTool {

    /** Timeout in seconds for any single git command. */
    private const val GIT_TIMEOUT_SECONDS = 30L

    /** Maximum characters captured from git command output. */
    private const val MAX_OUTPUT_CHARS = 8_000

    // ──────────────────────────────────────────────
    //  Tool Definitions
    // ──────────────────────────────────────────────

    /**
     * Returns the tool definitions for inclusion in the AI function-calling schema.
     */
    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "git_manager",
            description = "Execute Git operations in the Target Context directory. " +
                "Supports status, diff, commit, log, branch, and checkout operations.",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "Git action: 'status', 'diff', 'log', 'commit', 'branch', 'checkout', 'stash', 'add'",
                    required = true
                ),
                ToolParameter(
                    name = "commitMessage",
                    type = "string",
                    description = "Commit message (required for 'commit' action)",
                    required = false
                ),
                ToolParameter(
                    name = "branch",
                    type = "string",
                    description = "Branch name (for 'checkout' or 'branch' actions)",
                    required = false
                ),
                ToolParameter(
                    name = "files",
                    type = "string",
                    description = "Specific files for 'add' or 'diff' (space-separated, default: '.' for add)",
                    required = false
                )
            )
        )
    )

    // ──────────────────────────────────────────────
    //  Execution
    // ──────────────────────────────────────────────

    /**
     * Executes a git [action] inside the [scopePath] directory.
     *
     * @param action        One of `status`, `diff`, `log`, `commit`, `branch`,
     *                      `checkout`, `stash`, `add`.
     * @param scopePath     Absolute path to the working directory (must be inside a git repo).
     * @param commitMessage Required when [action] is `"commit"`.
     * @param branch        Required when [action] is `"checkout"`; optional for `"branch"` to
     *                      create a new branch (omit to list branches).
     * @param files         Space-separated file paths for `"add"` or `"diff"`. Defaults to
     *                      `"."` for `add`.
     * @return The result of the git command.
     */
    suspend fun execute(
        action: String,
        scopePath: String,
        commitMessage: String? = null,
        branch: String? = null,
        files: String? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val workDir = File(scopePath)
        if (!workDir.exists() || !workDir.isDirectory) {
            return@withContext ToolExecutionResult(
                output = "Target Context directory not found: $scopePath",
                isError = true
            )
        }

        try {
            when (action.lowercase()) {
                "status" -> runGit(listOf("git", "status", "--short"), workDir)

                "diff" -> {
                    val cmd = mutableListOf("git", "diff")
                    if (!files.isNullOrBlank()) {
                        cmd.add("--")
                        cmd.addAll(files.split(" ").filter { it.isNotBlank() })
                    }
                    runGit(cmd, workDir)
                }

                "log" -> runGit(listOf("git", "log", "--oneline", "-20"), workDir)

                "commit" -> {
                    if (commitMessage.isNullOrBlank()) {
                        return@withContext ToolExecutionResult(
                            output = "Missing required parameter: commitMessage is required for 'commit' action.",
                            isError = true
                        )
                    }
                    // Stage all changes then commit.
                    val addResult = runGit(listOf("git", "add", "."), workDir)
                    if (addResult.isError) return@withContext addResult

                    runGit(listOf("git", "commit", "-m", commitMessage), workDir)
                }

                "branch" -> {
                    if (!branch.isNullOrBlank()) {
                        runGit(listOf("git", "branch", branch), workDir)
                    } else {
                        runGit(listOf("git", "branch"), workDir)
                    }
                }

                "checkout" -> {
                    if (branch.isNullOrBlank()) {
                        return@withContext ToolExecutionResult(
                            output = "Missing required parameter: branch is required for 'checkout' action.",
                            isError = true
                        )
                    }
                    runGit(listOf("git", "checkout", branch), workDir)
                }

                "stash" -> runGit(listOf("git", "stash"), workDir)

                "add" -> {
                    val targets = if (!files.isNullOrBlank()) {
                        files.split(" ").filter { it.isNotBlank() }
                    } else {
                        listOf(".")
                    }
                    val cmd = mutableListOf("git", "add")
                    cmd.addAll(targets)
                    runGit(cmd, workDir)
                }

                else -> ToolExecutionResult(
                    output = "Unknown git action: $action. " +
                        "Supported: status, diff, log, commit, branch, checkout, stash, add",
                    isError = true
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult(
                output = "Failed to execute git $action: ${e.message}",
                isError = true
            )
        }
    }

    // ──────────────────────────────────────────────
    //  Dispatcher
    // ──────────────────────────────────────────────

    /**
     * Dispatches a tool call by [name] with the given [arguments] map.
     *
     * @param name      Must be `"git_manager"`.
     * @param arguments Key-value argument map from the AI model.
     * @param scopePath The user's active Target Context directory.
     * @return The result of the tool execution.
     */
    suspend fun executeTool(
        name: String,
        arguments: Map<String, String>,
        scopePath: String
    ): ToolExecutionResult {
        if (name != "git_manager") {
            return ToolExecutionResult(
                output = "Unknown tool: $name",
                isError = true
            )
        }

        val action = arguments["action"]
            ?: return ToolExecutionResult(
                output = "Missing required parameter: action",
                isError = true
            )

        return execute(
            action = action,
            scopePath = scopePath,
            commitMessage = arguments["commitMessage"],
            branch = arguments["branch"],
            files = arguments["files"]
        )
    }

    // ──────────────────────────────────────────────
    //  Internal helpers
    // ──────────────────────────────────────────────

    /**
     * Runs a git command via [ProcessBuilder] with the working directory set to [workDir].
     *
     * Uses the API-24-compatible timeout pattern: a dedicated wait-thread joined with a
     * millisecond timeout, followed by [Process.destroy] on timeout. stdout and stderr
     * are merged and read on a separate thread to prevent pipe-buffer deadlock.
     * [StringBuffer] (vs StringBuilder) provides thread safety.
     */
    private fun runGit(command: List<String>, workDir: File): ToolExecutionResult {
        val process = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()

        // Read output on a dedicated thread to prevent pipe-buffer deadlock.
        val outputBuffer = StringBuffer()
        val readerThread = Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    reader.lineSequence().forEach { line ->
                        if (outputBuffer.length < MAX_OUTPUT_CHARS) {
                            outputBuffer.appendLine(line)
                        }
                    }
                }
            } catch (_: Exception) { /* process killed — exit gracefully */ }
        }
        readerThread.start()

        // API-24-compatible timeout: Thread.join(millis) has been available since API 1.
        val waitThread = Thread {
            try { process.waitFor() } catch (_: InterruptedException) { /* timeout handling */ }
        }
        waitThread.start()
        waitThread.join(GIT_TIMEOUT_SECONDS * 1000L)

        val completed = !waitThread.isAlive
        if (!completed) {
            process.destroy()
            readerThread.interrupt()
            return ToolExecutionResult(
                output = "⏱ Git command timed out after ${GIT_TIMEOUT_SECONDS}s: ${command.joinToString(" ")}",
                isError = true
            )
        }

        readerThread.join(2_000L) // wait for reader to drain (max 2s)

        val exitCode = process.exitValue()
        val rawOutput = outputBuffer.toString().trimEnd()
        val truncated = outputBuffer.length >= MAX_OUTPUT_CHARS
        val truncationNote = if (truncated) "\n[TRUNCATED]" else ""

        val resultText = buildString {
            appendLine("$ ${command.joinToString(" ")}")
            if (rawOutput.isNotEmpty()) appendLine(rawOutput)
            append("[exit: $exitCode]$truncationNote")
        }

        return ToolExecutionResult(
            output = resultText,
            isError = exitCode != 0,
            truncated = truncated
        )
    }
}
