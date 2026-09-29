package com.omnidev.workspace.data.tools

import com.omnidev.workspace.OmniDevApp
import com.omnidev.workspace.data.auth.GitHubAgentAccessStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI

/**
 * Target Context Git operations backed by JGit, so Android does not need a
 * system git executable or a Termux shell. Only remote operations use the
 * separately authorized GitHub Agent Access token.
 */
object GitManagerTool {

    private const val REMOTE_TIMEOUT_SECONDS = 120

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
            description = "Git in the Target Context. Local: status, diff, log, add, commit, branch, checkout, stash. " +
                "GitHub remote: remote_status, fetch, pull, push. Remote actions require GitHub Agent Access in Settings; push also requires Write. " +
                "Only a validated github.com HTTPS remote is accepted. Pull is fast-forward only; push sends the current branch.",
            parameters = listOf(
                ToolParameter(
                    name = "action",
                    type = "string",
                    description = "status | diff | log | add | commit | branch | checkout | stash | remote_status | fetch | pull | push",
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
                ),
                ToolParameter(
                    name = "remote",
                    type = "string",
                    description = "Configured Git remote name for remote_status/fetch/pull/push (default origin). Never pass a URL or token.",
                    required = false
                ),
                ToolParameter(
                    name = "authorName", type = "string",
                    description = "Commit author name if Git user.name is not configured; use the user's provided identity.",
                    required = false
                ),
                ToolParameter(
                    name = "authorEmail", type = "string",
                    description = "Commit author email if Git user.email is not configured; do not invent one.",
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
     * @param remote        Configured remote name for GitHub transport; defaults to origin.
     * @return The result of the git command.
     */
    suspend fun execute(
        action: String,
        scopePath: String,
        commitMessage: String? = null,
        branch: String? = null,
        files: String? = null,
        remote: String? = null,
        authorName: String? = null,
        authorEmail: String? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val workDir = File(scopePath)
        if (!workDir.exists() || !workDir.isDirectory) {
            return@withContext ToolExecutionResult(
                output = "Target Context directory not found: $scopePath",
                isError = true
            )
        }

        try {
            if (action.lowercase() in setOf("remote_status", "fetch", "pull", "push")) {
                executeRemote(action.lowercase(), workDir, remote ?: "origin", branch)
            } else {
                executeLocal(action.lowercase(), workDir, commitMessage, branch, files, authorName, authorEmail)
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
            files = arguments["files"],
            remote = arguments["remote"],
            authorName = arguments["authorName"],
            authorEmail = arguments["authorEmail"]
        )
    }

    private fun executeLocal(
        action: String, workDir: File, commitMessage: String?, branch: String?, files: String?,
        authorName: String?, authorEmail: String?
    ): ToolExecutionResult = Git.open(workDir).use { git ->
        val repository = git.repository
        if (repository.workTree.canonicalFile != workDir.canonicalFile) return ToolExecutionResult(
            "Select the Git repository root as Target Context.", isError = true)
        when (action) {
            "status" -> {
                val status = git.status().call()
                val lines = listOf(
                    "Added" to status.added, "Changed" to status.changed,
                    "Modified" to status.modified, "Deleted" to status.removed,
                    "Missing" to status.missing, "Untracked" to status.untracked,
                    "Conflicting" to status.conflicting
                ).filter { it.second.isNotEmpty() }
                output(if (lines.isEmpty()) "Working tree clean" else
                    lines.joinToString("\n") { it.first + ": " + it.second.sorted().joinToString(", ") })
            }
            "diff" -> {
                val paths = safePaths(files, defaultAll = false)
                    ?: return ToolExecutionResult("Invalid diff file path.", isError = true)
                val command = git.diff()
                if (paths.isNotEmpty()) command.setPathFilter(PathFilterGroup.createFromStrings(paths))
                val stream = ByteArrayOutputStream()
                // DiffCommand retains the working-tree content source. Reformatting its
                // entries with a new repository-only DiffFormatter can request a blob
                // that exists only in the working tree (Missing blob on unstaged edits).
                command.setOutputStream(stream).call()
                output(stream.toString("UTF-8").ifBlank { "No unstaged changes." })
            }
            "log" -> output(git.log().setMaxCount(20).call().joinToString("\n") {
                it.name.take(8) + " " + it.shortMessage
            })
            "add" -> {
                val paths = safePaths(files, defaultAll = true)
                    ?: return ToolExecutionResult("Invalid add file path.", isError = true)
                paths.forEach { path ->
                    git.add().addFilepattern(path).call()
                    git.add().setUpdate(true).addFilepattern(path).call()
                }
                output("Staged " + paths.joinToString(", "))
            }
            "commit" -> {
                if (commitMessage.isNullOrBlank()) return ToolExecutionResult(
                    "Missing required parameter: commitMessage.", isError = true)
                val name = authorName?.takeIf(String::isNotBlank)
                    ?: repository.config.getString("user", null, "name")?.takeIf(String::isNotBlank)
                val email = authorEmail?.takeIf(String::isNotBlank)
                    ?: repository.config.getString("user", null, "email")?.takeIf(String::isNotBlank)
                if (name == null || email == null) return ToolExecutionResult(
                    "Git author identity is missing. Configure user.name/user.email in this repository or supply authorName and authorEmail from the user.",
                    isError = true, classification = "USER_ACTION_REQUIRED")
                git.add().addFilepattern(".").call()
                git.add().setUpdate(true).addFilepattern(".").call()
                val identity = PersonIdent(name, email)
                val commit = git.commit().setMessage(commitMessage)
                    .setAuthor(identity).setCommitter(identity).call()
                output("Committed " + commit.name.take(12) + ": " + commit.shortMessage)
            }
            "branch" -> {
                if (!branch.isNullOrBlank()) {
                    if (!isSafeBranch(branch)) return ToolExecutionResult("Invalid branch name.", isError = true)
                    val created = git.branchCreate().setName(branch).call()
                    output("Created " + created.name)
                } else output(git.branchList().call().joinToString("\n") { it.name })
            }
            "checkout" -> {
                if (branch.isNullOrBlank() || !isSafeBranch(branch)) return ToolExecutionResult(
                    "A valid existing branch is required for checkout.", isError = true)
                output("Checked out " + git.checkout().setName(branch).call().name)
            }
            "stash" -> output(git.stashCreate().call()?.let { "Stashed " + it.name } ?: "No changes to stash.")
            else -> ToolExecutionResult(
                "Unknown git action: $action. Supported: status, diff, log, add, commit, branch, checkout, stash, remote_status, fetch, pull, push.",
                isError = true)
        }
    }

    private fun safePaths(files: String?, defaultAll: Boolean): List<String>? {
        if (files.isNullOrBlank()) return if (defaultAll) listOf(".") else emptyList()
        val paths = files.split(' ').filter(String::isNotBlank)
        return paths.takeIf { items -> items.isNotEmpty() && items.all { path ->
            path == "." || (path.isNotBlank() && !path.startsWith('-') && !path.startsWith('/') &&
                path.split('/').none { it == ".." || it == ".git" } && '\\' !in path)
        } }
    }

    private fun output(value: String): ToolExecutionResult = ToolExecutionResult(
        output = value.take(MAX_OUTPUT_CHARS),
        truncated = value.length > MAX_OUTPUT_CHARS
    )

    private fun executeRemote(action: String, workDir: File, remote: String, branch: String?): ToolExecutionResult {
        if (!remote.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,100}"))) {
            return ToolExecutionResult("Invalid remote name. Use a configured remote such as origin.", isError = true)
        }
        val policy = runCatching { GitHubAgentAccessStore(OmniDevApp.instance.applicationContext).policy() }
            .getOrElse { return ToolExecutionResult("GITHUB_SECURE_STORAGE_ERROR: GitHub Agent Access is unavailable.", isError = true) }
        if (!policy.enabled) return ToolExecutionResult(
            "GITHUB_AGENT_ACCESS_DISABLED: Enable GitHub Agent Access in Settings → Integrations & Linked Accounts.",
            isError = true, classification = "USER_ACTION_REQUIRED")
        if (!policy.connected) return ToolExecutionResult(
            "GITHUB_AGENT_NOT_AUTHORIZED: Connect a GitHub account in GitHub Agent Access settings.",
            isError = true, classification = "USER_ACTION_REQUIRED")
        if (action == "push" && !policy.writeEnabled) return ToolExecutionResult(
            "GITHUB_WRITE_DISABLED: Enable GitHub write operations in GitHub Agent Access settings before pushing.",
            isError = true, classification = "USER_ACTION_REQUIRED")

        Git.open(workDir).use { git ->
            val repository = git.repository
            if (repository.workTree.canonicalFile != workDir.canonicalFile) return ToolExecutionResult(
                "GITHUB_TARGET_CONTEXT_MISMATCH: Select the Git repository root as Target Context.",
                isError = true
            )
            val config = repository.config
            val url = (if (action == "push") config.getString("remote", remote, "pushurl") else null)
                ?: config.getString("remote", remote, "url")
                ?: return ToolExecutionResult("Git remote '$remote' is not configured.", isError = true)
            if (!isSafeGitHubRemote(url)) return ToolExecutionResult(
                "GITHUB_REMOTE_REJECTED: '$remote' must be an HTTPS github.com/owner/repo remote without embedded credentials.",
                isError = true
            )
            if (action == "remote_status") return ToolExecutionResult(
                "GitHub Agent Access: connected as @" + (policy.accountLogin ?: "unknown") +
                    "; remote $remote → $url; push " +
                    (if (policy.writeEnabled) "enabled" else "disabled") +
                    " by local policy. GitHub repository permissions are checked when a transfer runs."
            )

            val currentBranch = repository.branch.takeUnless { repository.isBare || it == "HEAD" }
                ?: return ToolExecutionResult("A current branch is required; detached HEAD is unsupported.", isError = true)
            val selectedBranch = branch?.takeIf(String::isNotBlank) ?: currentBranch
            if (!isSafeBranch(selectedBranch) || (action == "push" && selectedBranch != currentBranch)) {
                return ToolExecutionResult("Invalid branch or push target: push is restricted to the current branch.", isError = true)
            }
            val token = policy.token ?: return ToolExecutionResult("GITHUB_AGENT_NOT_AUTHORIZED", isError = true)
            if (token.any { it == '\r' || it == '\n' }) return ToolExecutionResult(
                "Invalid GitHub Agent Access credential; reconnect in Settings.", isError = true)
            val credentials = scopedCredentials(url, token)
            return when (action) {
                "push" -> {
                    val updates = git.push().setRemote(url)
                        .setRefSpecs(RefSpec("HEAD:refs/heads/$selectedBranch"))
                        .setCredentialsProvider(credentials).setTimeout(REMOTE_TIMEOUT_SECONDS)
                        .call().flatMap { it.remoteUpdates }.toList()
                    val rejected = updates.filter { it.status != RemoteRefUpdate.Status.OK &&
                        it.status != RemoteRefUpdate.Status.UP_TO_DATE }
                    if (rejected.isNotEmpty()) ToolExecutionResult(
                        "GitHub push rejected: " + rejected.joinToString { it.remoteName + ": " + it.status } +
                            ". Fetch and review the remote branch or check repository write permission.",
                        isError = true
                    ) else ToolExecutionResult("Pushed $currentBranch to $url (" + updates.size + " ref update(s)).")
                }
                else -> {
                    val trackingRef = "refs/remotes/$remote/$selectedBranch"
                    git.fetch().setRemote(url)
                        .setRefSpecs(RefSpec("+refs/heads/$selectedBranch:$trackingRef"))
                        .setTagOpt(TagOpt.NO_TAGS).setCredentialsProvider(credentials)
                        .setTimeout(REMOTE_TIMEOUT_SECONDS).call()
                    if (action == "fetch") return ToolExecutionResult("Fetched $selectedBranch from $url into $trackingRef.")
                    if (repository.branch != currentBranch) return ToolExecutionResult(
                        "Local branch changed during fetch; inspect status before merging.", isError = true)
                    val target = repository.findRef(trackingRef) ?: return ToolExecutionResult(
                        "Fetched branch has no local tracking ref.", isError = true)
                    val merge = git.merge().include(target)
                        .setFastForward(MergeCommand.FastForwardMode.FF_ONLY).call()
                    if (!merge.mergeStatus.isSuccessful) ToolExecutionResult(
                        "Pull stopped: " + merge.mergeStatus + ". Review divergent history; no merge commit was created.",
                        isError = true
                    ) else ToolExecutionResult("Pulled $selectedBranch from $url: " + merge.mergeStatus + ".")
                }
            }
        }
    }

    internal fun scopedCredentials(expectedUrl: String, token: String): CredentialsProvider {
        val delegate = UsernamePasswordCredentialsProvider("x-access-token", token)
        val expectedPath = URI(expectedUrl).rawPath
        return object : CredentialsProvider() {
            override fun isInteractive() = false
            override fun supports(vararg items: CredentialItem) = delegate.supports(*items)
            override fun get(uri: URIish, vararg items: CredentialItem): Boolean {
                val actual = uri.toString()
                return isSafeGitHubRemote(actual) && URI(actual).rawPath == expectedPath &&
                    delegate.get(uri, *items)
            }
        }
    }

    /** Do not pass account credentials to an untrusted remote, URL rewrite, or redirect. */
    internal fun isSafeGitHubRemote(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("github.com", ignoreCase = true) &&
            uri.port == -1 && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.matches(Regex("/[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*(?:\\.git)?")) &&
            !uri.rawPath.contains("..")
    }.getOrDefault(false)

    internal fun isSafeBranch(value: String): Boolean =
        value.isNotBlank() && value != "@" && value.length <= 200 && !value.startsWith('-') &&
            !value.startsWith('/') && !value.endsWith('/') && !value.endsWith('.') &&
            !value.contains("..") && !value.contains("@{") && !value.contains("//") &&
            value.none { it.isWhitespace() || it in setOf('~', '^', ':', '?', '*', '[', '\\') || it.code < 32 || it.code == 127 } &&
            value.split('/').none { it.isEmpty() || it.startsWith('.') || it.endsWith(".lock") }

}
