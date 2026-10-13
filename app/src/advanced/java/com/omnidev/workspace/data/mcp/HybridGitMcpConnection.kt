package com.omnidev.workspace.data.mcp

import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryBuilder
import java.io.File

/**
 * A "Hybrid" Git MCP handler that implements the McpConnection interface.
 *
 * - Fast Path (JGit): status, log, branch, diff
 * - Heavy Path (Shell Executor via PrivilegedExecutionManager): clone, push, pull, commit, rebase
 */
class HybridGitMcpConnection : McpConnection {

    override suspend fun getSupportedTools(serverName: String): List<ToolDefinition> {
        return listOf(
            ToolDefinition(
                name = "mcp_${serverName}_git_status",
                description = "Get the current git status of a repository (Fast Path).",
                parameters = listOf(
                    ToolParameter("repo_path", "string", "Absolute path to the local repository", true)
                )
            ),
            ToolDefinition(
                name = "mcp_${serverName}_git_log",
                description = "Get the recent git commit log (Fast Path).",
                parameters = listOf(
                    ToolParameter("repo_path", "string", "Absolute path to the local repository", true),
                    ToolParameter("max_count", "number", "Maximum number of commits to show (default 10)", false)
                )
            ),
            ToolDefinition(
                name = "mcp_${serverName}_git_branch",
                description = "List local branches (Fast Path).",
                parameters = listOf(
                    ToolParameter("repo_path", "string", "Absolute path to the local repository", true)
                )
            ),
            // Heavy path commands
            ToolDefinition(
                name = "mcp_${serverName}_git_clone",
                description = "Clone a remote repository (Heavy Path).",
                parameters = listOf(
                    ToolParameter("url", "string", "Repository URL", true),
                    ToolParameter("target_path", "string", "Target directory", true)
                )
            ),
            ToolDefinition(
                name = "mcp_${serverName}_git_commit",
                description = "Commit changes to the repository (Heavy Path).",
                parameters = listOf(
                    ToolParameter("repo_path", "string", "Local repository path", true),
                    ToolParameter("message", "string", "Commit message", true),
                    ToolParameter("add_all", "boolean", "Add all changes before committing", false)
                )
            ),
            ToolDefinition(
                name = "mcp_${serverName}_git_push",
                description = "Push commits to remote (Heavy Path).",
                parameters = listOf(
                    ToolParameter("repo_path", "string", "Local repository path", true)
                )
            ),
            ToolDefinition(
                name = "mcp_${serverName}_git_pull",
                description = "Pull commits from remote (Heavy Path).",
                parameters = listOf(
                    ToolParameter("repo_path", "string", "Local repository path", true)
                )
            )
        )
    }

    override suspend fun executeTool(serverName: String, originalToolName: String, arguments: Map<String, String>): String = withContext(Dispatchers.IO) {
        try {
            when (originalToolName) {
                // --- FAST PATH (JGit) ---
                "git_status" -> {
                    val repoPath = arguments["repo_path"] ?: return@withContext "Error: Missing repo_path"
                    executeJGitStatus(repoPath)
                }
                "git_log" -> {
                    val repoPath = arguments["repo_path"] ?: return@withContext "Error: Missing repo_path"
                    val maxCount = arguments["max_count"]?.toIntOrNull() ?: 10
                    executeJGitLog(repoPath, maxCount)
                }
                "git_branch" -> {
                    val repoPath = arguments["repo_path"] ?: return@withContext "Error: Missing repo_path"
                    executeJGitBranch(repoPath)
                }
                // --- HEAVY PATH (Shell Executor) ---
                "git_clone" -> {
                    val url = arguments["url"] ?: return@withContext "Error: Missing url"
                    val targetPath = arguments["target_path"] ?: return@withContext "Error: Missing target_path"
                    executeShellCommand("git clone $url $targetPath", targetPath)
                }
                "git_commit" -> {
                    val repoPath = arguments["repo_path"] ?: return@withContext "Error: Missing repo_path"
                    val message = arguments["message"] ?: return@withContext "Error: Missing message"
                    val addAll = arguments["add_all"]?.toBooleanStrictOrNull() ?: false

                    val cmds = mutableListOf<String>()
                    if (addAll) {
                        cmds.add("git add .")
                    }
                    val safeMessage = PrivilegedExecutionManager.shellQuote(message)
                    cmds.add("git commit -m $safeMessage")

                    executeShellCommand(cmds.joinToString(" && "), repoPath)
                }
                "git_push" -> {
                    val repoPath = arguments["repo_path"] ?: return@withContext "Error: Missing repo_path"
                    executeShellCommand("git push", repoPath)
                }
                "git_pull" -> {
                    val repoPath = arguments["repo_path"] ?: return@withContext "Error: Missing repo_path"
                    executeShellCommand("git pull", repoPath)
                }
                else -> "Error: Unsupported tool '$originalToolName'"
            }
        } catch (e: Exception) {
            "Error executing Git MCP tool: ${e.message}"
        }
    }

    // --- Fast Path Implementations ---

    private fun executeJGitStatus(repoPath: String): String {
        val repoDir = File(repoPath)
        val gitDir = File(repoDir, ".git")
        if (!gitDir.exists()) return "Error: Not a git repository."

        val repository = RepositoryBuilder().setGitDir(gitDir).readEnvironment().findGitDir().build()
        return Git(repository).use { git ->
            val status = git.status().call()
            buildString {
                if (status.isClean) {
                    appendLine("working tree clean")
                } else {
                    if (status.added.isNotEmpty()) appendLine("Added: ${status.added}")
                    if (status.changed.isNotEmpty()) appendLine("Changed: ${status.changed}")
                    if (status.removed.isNotEmpty()) appendLine("Removed: ${status.removed}")
                    if (status.missing.isNotEmpty()) appendLine("Missing: ${status.missing}")
                    if (status.modified.isNotEmpty()) appendLine("Modified: ${status.modified}")
                    if (status.untracked.isNotEmpty()) appendLine("Untracked: ${status.untracked}")
                }
            }
        }
    }

    private fun executeJGitLog(repoPath: String, maxCount: Int): String {
        val repoDir = File(repoPath)
        val gitDir = File(repoDir, ".git")
        if (!gitDir.exists()) return "Error: Not a git repository."

        val repository = RepositoryBuilder().setGitDir(gitDir).readEnvironment().findGitDir().build()
        return Git(repository).use { git ->
            val logs = git.log().setMaxCount(maxCount).call()
            buildString {
                for (commit in logs) {
                    appendLine("commit ${commit.name()}")
                    appendLine("Author: ${commit.authorIdent.name} <${commit.authorIdent.emailAddress}>")
                    appendLine("Date:   ${commit.authorIdent.getWhen()}")
                    appendLine()
                    appendLine("    ${commit.fullMessage.trim()}")
                    appendLine()
                }
            }
        }
    }

    private fun executeJGitBranch(repoPath: String): String {
        val repoDir = File(repoPath)
        val gitDir = File(repoDir, ".git")
        if (!gitDir.exists()) return "Error: Not a git repository."

        val repository = RepositoryBuilder().setGitDir(gitDir).readEnvironment().findGitDir().build()
        return Git(repository).use { git ->
            val branches = git.branchList().call()
            val currentBranch = repository.fullBranch
            buildString {
                for (branch in branches) {
                    val isCurrent = branch.name == currentBranch
                    appendLine("${if (isCurrent) "* " else "  "}${branch.name.removePrefix("refs/heads/")}")
                }
            }
        }
    }

    // --- Heavy Path Implementation ---

    private suspend fun executeShellCommand(command: String, workingDir: String): String {
        // Ensure directory exists or we'll get execution errors (unless cloning)
        val cmdStr = "cd ${PrivilegedExecutionManager.shellQuote(workingDir)} && $command"
        val result = PrivilegedExecutionManager.executeCommand(cmdStr)

        return if (result.isSuccess) {
            result.getOrThrow()
        } else {
            "Error: ${result.exceptionOrNull()?.message}"
        }
    }
}
