package com.omnidev.workspace.data.tools

import android.content.Context
import android.net.Uri
import android.util.Log
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

// ═══════════════════════════════════════════════════════════════════════════════
// AGENT RUNTIME TOOL — v3.0
//
// The master execution router for the OmniDev autonomous agent.
//
// Architecture compared to v2:
//
//  v2 (old):                          v3 (new):
//  ┌──────────────────────┐           ┌────────────────────────────────────────┐
//  │ AgentRuntimeTool     │           │ AgentRuntimeTool                       │
//  │  • flat when/else    │  ──────►  │  ┌─────────────────────────────────┐  │
//  │  • no job tracking   │           │  │ JobQueue (priority + background) │  │
//  │  • no process mgmt   │           │  └─────────────────────────────────┘  │
//  │  • no git ops        │           │  ┌──────────┐  ┌──────────────────┐  │
//  │  • no script store   │           │  │ ScriptStore │  │ ProcessRegistry │  │
//  │  • no env vars       │           │  └──────────┘  └──────────────────┘  │
//  │  • no pipe compose   │           │  ┌──────────────────────────────────┐  │
//  └──────────────────────┘           │  │ EnvVarRegistry (named profiles)  │  │
//                                     │  └──────────────────────────────────┘  │
//                                     │  • Git ops (clone/pull/commit/status)  │
//                                     │  • File ops (read/write/list/copy)     │
//                                     │  • Pipe composition                    │
//                                     │  • Background jobs with IDs            │
//                                     └────────────────────────────────────────┘
//
// All shell execution delegates to EnvironmentSetupManager.executeShell()
// which handles Termux env injection and Base64 script injection automatically.
// ═══════════════════════════════════════════════════════════════════════════════

// ── Job Queue ─────────────────────────────────────────────────────────────────

enum class JobPriority(val order: Int) {
    LOW(3), NORMAL(2), HIGH(1), CRITICAL(0)
}

enum class JobStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

/**
 * A background job submitted to [AgentRuntimeTool.JobQueue].
 * The agent can fire-and-forget long-running commands and poll [status] later.
 */
data class RuntimeJob(
    val id:          String,
    val description: String,
    val priority:    JobPriority      = JobPriority.NORMAL,
    val createdAt:   Long             = System.currentTimeMillis(),
    @Volatile var status: JobStatus  = JobStatus.PENDING,
    @Volatile var result: ToolExecutionResult? = null,
    @Volatile var startedAt:   Long? = null,
    @Volatile var completedAt: Long? = null
)

// ── Script Store ──────────────────────────────────────────────────────────────

/**
 * Named scripts persisted in memory for the session.
 * The agent can save, list, and run named scripts without re-supplying source code.
 */
data class StoredScript(
    val name:        String,
    val language:    String,           // "sh" | "python" | "node" | "bash"
    val code:        String,
    val description: String = "",
    val savedAt:     Long   = System.currentTimeMillis()
)

// ── Environment Variable Registry ────────────────────────────────────────────

/**
 * Named environment variable profiles.
 * e.g. "my_api_project" → {"OPENAI_API_KEY": "sk-...", "DEBUG": "1"}
 *
 * Env vars are injected as `KEY=VALUE ` prefixes when executing scripts.
 */
object EnvVarRegistry {
    private val profiles = ConcurrentHashMap<String, MutableMap<String, String>>()

    fun set(profile: String, key: String, value: String) {
        profiles.getOrPut(profile) { mutableMapOf() }[key] = value
    }

    fun get(profile: String, key: String): String? = profiles[profile]?.get(key)

    fun getAll(profile: String): Map<String, String> = profiles[profile]?.toMap() ?: emptyMap()

    fun delete(profile: String, key: String): Boolean = profiles[profile]?.remove(key) != null

    fun deleteProfile(profile: String): Boolean = profiles.remove(profile) != null

    fun listProfiles(): List<String> = profiles.keys.toList().sorted()

    fun buildPrefix(profile: String): String =
        profiles[profile]?.entries
            ?.joinToString(" ") { (k, v) ->
                val safeKey = k.replace(Regex("[^a-zA-Z0-9_]"), "")
                "$safeKey=${EnvironmentSetupManager.shellQuote(v)}"
            }
            ?.let { if (it.isNotBlank()) "$it " else "" }
            ?: ""
}

// ═══════════════════════════════════════════════════════════════════════════════
// MAIN OBJECT
// ═══════════════════════════════════════════════════════════════════════════════

object AgentRuntimeTool {

    private const val TAG = "AgentRuntimeV3"
    private const val MAX_OUTPUT = 10_000

    // ── Job Queue ──────────────────────────────────────────────────────────

    object JobQueue {
        private val jobs      = ConcurrentHashMap<String, RuntimeJob>()
        private val idCounter = AtomicLong(1L)
        private val scope     = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Submit a background job. Returns the job ID immediately. */
        fun submit(
            description: String,
            priority:    JobPriority = JobPriority.NORMAL,
            block:       suspend () -> ToolExecutionResult
        ): String {
            val id = "job_${idCounter.getAndIncrement()}"
            val job = RuntimeJob(id, description, priority)
            jobs[id] = job
            scope.launch {
                job.status    = JobStatus.RUNNING
                job.startedAt = System.currentTimeMillis()
                try {
                    val result        = block()
                    job.result        = result
                    job.status        = if (result.isError) JobStatus.FAILED else JobStatus.COMPLETED
                } catch (e: CancellationException) {
                    job.status = JobStatus.CANCELLED
                } catch (e: Exception) {
                    job.result = ToolExecutionResult("Job exception: ${e.message}", isError = true)
                    job.status = JobStatus.FAILED
                } finally {
                    job.completedAt = System.currentTimeMillis()
                    Log.i(TAG, "Job $id [${job.status}]: ${description.take(60)}")
                }
            }
            Log.i(TAG, "Job $id queued: $description")
            return id
        }

        fun get(id: String): RuntimeJob? = jobs[id]

        fun cancel(id: String): Boolean {
            val job = jobs[id] ?: return false
            if (job.status == JobStatus.PENDING || job.status == JobStatus.RUNNING) {
                job.status = JobStatus.CANCELLED
                return true
            }
            return false
        }

        fun listAll(): List<RuntimeJob> = jobs.values
            .sortedWith(compareBy({ it.priority.order }, { it.createdAt }))

        fun purgeCompleted() = jobs.entries.removeIf {
            it.value.status in listOf(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED)
        }
    }

    // ── Script Store ───────────────────────────────────────────────────────

    object ScriptStore {
        private val scripts = ConcurrentHashMap<String, StoredScript>()

        fun save(script: StoredScript) { scripts[script.name] = script }
        fun get(name: String): StoredScript? = scripts[name]
        fun delete(name: String): Boolean = scripts.remove(name) != null
        fun list(): List<StoredScript> = scripts.values.sortedBy { it.name }
    }

    // ── Tool Definitions ───────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "agent_runtime",
            description = """
Execute the full OmniDev autonomous runtime. Backed by Shizuku/Root + Termux.

━━ ENVIRONMENT ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
• env_check        — Full runtime survey (Python, Node, Git, Termux, backend).
• env_probe        — Force re-probe of all runtimes.
• bootstrap        — Run the full staged bootstrap (Termux → Python → Node → Git).
• bootstrap_status — Show bootstrap plan completion status.
• tool_which       — Locate a binary (param: 'tool').
• install_python   — Install Python via Termux.
• install_node     — Install Node.js via Termux.
• install_git      — Install Git via Termux.

━━ CODE EXECUTION ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
• shell_script     — Execute multi-line shell (param: 'script', optional: 'cwd', 'interpreter', 'env_profile').
• python_run       — Execute Python code (param: 'code', optional: 'args', 'cwd', 'venv_path', 'env_profile').
• node_run         — Execute JavaScript (param: 'code', optional: 'cwd', 'env_profile').
• run_script       — Run a named stored script (param: 'name', optional: 'args', 'cwd').
• pipe_exec        — Execute a pipeline of commands (param: 'pipeline' as JSON array of strings).

━━ BACKGROUND JOBS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
• job_submit       — Submit a shell command as a background job (param: 'script'). Returns job ID.
• job_status       — Check job status (param: 'job_id').
• job_list         — List all jobs and their statuses.
• job_cancel       — Cancel a pending/running job (param: 'job_id').
• job_purge        — Purge completed/failed/cancelled jobs from queue.

━━ PACKAGES ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
• pip_install      — Install Python packages (param: 'packages', optional: 'upgrade', 'venv_path').
• pip_list         — List installed Python packages.
• npm_install      — Install npm packages (param: 'packages', optional: 'global', 'cwd').
• pkg_install      — Install Termux packages (param: 'packages').
• pkg_update       — Update Termux package index.
• pkg_upgrade      — Upgrade all installed Termux packages.

━━ FILE OPERATIONS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
• file_read        — Read file content (param: 'path', optional: 'max_lines').
• file_write       — Write content to file (param: 'path', 'content', optional: 'append').
• file_list        — List directory contents (param: 'path', optional: 'recursive').
• file_delete      — Delete file or directory (param: 'path').
• file_copy        — Copy file/dir (param: 'src', 'dst').
• file_move        — Move file/dir (param: 'src', 'dst').
• file_stat        — Show file metadata (param: 'path').
• file_find        — Find files by pattern (param: 'path', 'pattern', optional: 'type').
• file_grep        — Search file content (param: 'path', 'pattern', optional: 'recursive').
• file_chmod       — Change file permissions (param: 'path', 'mode').
• file_mkdir       — Create directory (param: 'path').

━━ GIT OPERATIONS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
• git_clone        — Clone a repository (param: 'url', optional: 'dest', 'branch', 'depth').
• git_pull         — Pull latest changes (optional: 'cwd', 'remote', 'branch').
• git_push         — Push commits (optional: 'cwd', 'remote', 'branch').
• git_status       — Show git status (optional: 'cwd').
• git_log          — Show commit log (optional: 'cwd', 'limit').
• git_diff         — Show uncommitted changes (optional: 'cwd').
• git_commit       — Stage all and commit (param: 'message', optional: 'cwd').
• git_checkout     — Checkout branch (param: 'branch', optional: 'cwd', 'create').
• git_stash        — Stash changes (optional: 'cwd', 'action': push/pop/list).
• git_reset        — Reset to HEAD (optional: 'cwd', 'mode': soft/hard/mixed).
• git_branch       — List or manage branches (optional: 'cwd', 'action': list/delete).
• git_init         — Init a new repository (optional: 'cwd').
• git_remote       — Show/manage remotes (optional: 'cwd').
• git_tag          — Create or list tags (optional: 'cwd', 'name').

━━ PROCESS MANAGEMENT ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
• proc_list        — List running processes (optional: 'filter', 'sort': cpu/mem/pid).
• proc_kill        — Kill a process (param: 'target' as PID or name, optional: 'signal').
• proc_top         — Show top CPU/memory consumers (optional: 'count').
• proc_find        — Find processes by name pattern (param: 'pattern').

━━ SCRIPT STORE ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
• script_save      — Save a named script (param: 'name', 'code', 'language', optional: 'description').
• script_list      — List saved scripts.
• script_get       — Get a saved script's source code (param: 'name').
• script_delete    — Delete a saved script (param: 'name').
• script_run       — Run a saved script (param: 'name', optional: 'args', 'cwd', 'env_profile').

━━ ENVIRONMENT VARIABLES ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
• env_set          — Set an env var in a named profile (param: 'profile', 'key', 'value').
• env_get          — Get a single env var (param: 'profile', 'key').
• env_list         — List all vars in a profile (param: 'profile').
• env_delete       — Delete an env var (param: 'profile', 'key').
• env_delete_profile — Delete an entire env profile (param: 'profile').
• env_list_profiles — List all env profiles.

━━ DOWNLOADS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
• download_file    — Download a file (param: 'url', optional: 'dest_path').
• download_exec    — Download and execute a script (param: 'url', optional: 'args', 'cwd').
• download_verify  — Download and check SHA256 checksum (param: 'url', 'checksum', optional: 'dest').
• install_tool     — Install/provision a tool (param: 'tool', optional provisioning hints).
• tools_status     — Show runtime/tools status.
            """.trimIndent(),
            parameters = listOf(
                ToolParameter("action",      "string", "Action to perform (see description above).", required = true),
                // Execution
                ToolParameter("code",        "string", "Source code for python_run / node_run.", required = false),
                ToolParameter("script",      "string", "Shell script for shell_script / job_submit.", required = false),
                ToolParameter("interpreter", "string", "Interpreter: sh, bash, python3, node (default: sh).", required = false),
                ToolParameter("pipeline",    "string", "JSON array of commands to pipe together.", required = false),
                ToolParameter("args",        "string", "Extra args (space-separated).", required = false),
                ToolParameter("cwd",         "string", "Working directory.", required = false),
                ToolParameter("venv_path",   "string", "Absolute path to a Python venv.", required = false),
                ToolParameter("env_profile", "string", "Named env var profile to inject.", required = false),
                // Jobs
                ToolParameter("job_id",      "string", "Job ID for job_status / job_cancel.", required = false),
                // Packages
                ToolParameter("packages",    "string", "Space-separated package list.", required = false),
                ToolParameter("upgrade",     "string", "true to pip install --upgrade.", required = false),
                ToolParameter("global",      "string", "false to drop npm -g flag.", required = false),
                // Files
                ToolParameter("path",        "string", "File or directory path.", required = false),
                ToolParameter("src",         "string", "Source path for copy/move.", required = false),
                ToolParameter("dst",         "string", "Destination path for copy/move.", required = false),
                ToolParameter("content",     "string", "Content to write.", required = false),
                ToolParameter("append",      "string", "true to append instead of overwrite.", required = false),
                ToolParameter("pattern",     "string", "Search pattern for file_find / file_grep.", required = false),
                ToolParameter("mode",        "string", "Permission mode for chmod / git reset mode.", required = false),
                ToolParameter("max_lines",   "string", "Max lines to read.", required = false),
                ToolParameter("recursive",   "string", "true for recursive operations.", required = false),
                ToolParameter("type",        "string", "File type filter: f (file), d (dir).", required = false),
                // Git
                ToolParameter("url",         "string", "URL for git_clone / download_file.", required = false),
                ToolParameter("dest",        "string", "Destination path for git_clone.", required = false),
                ToolParameter("branch",      "string", "Git branch name.", required = false),
                ToolParameter("depth",       "string", "Shallow clone depth.", required = false),
                ToolParameter("remote",      "string", "Git remote name (default: origin).", required = false),
                ToolParameter("message",     "string", "Commit message.", required = false),
                ToolParameter("create",      "string", "true to create branch on checkout.", required = false),
                // Process
                ToolParameter("target",      "string", "PID or process name.", required = false),
                ToolParameter("signal",      "string", "Signal for kill (default: 15).", required = false),
                ToolParameter("filter",      "string", "Process name filter.", required = false),
                ToolParameter("sort",        "string", "Sort order: cpu, mem, pid.", required = false),
                ToolParameter("count",       "string", "Number of processes to show.", required = false),
                // Scripts
                ToolParameter("name",        "string", "Script name for script_save / script_get.", required = false),
                ToolParameter("language",    "string", "Script language: sh, bash, python, node.", required = false),
                ToolParameter("description", "string", "Script description.", required = false),
                // Env vars
                ToolParameter("profile",     "string", "Env var profile name.", required = false),
                ToolParameter("key",         "string", "Env var key.", required = false),
                ToolParameter("value",       "string", "Env var value.", required = false),
                // Tool
                ToolParameter("tool",        "string", "Binary name for tool_which.", required = false),
                // Download
                ToolParameter("dest_path",   "string", "Destination path for download_file.", required = false),
                ToolParameter("checksum",    "string", "Expected SHA256 for download_verify.", required = false),
                // Misc
                ToolParameter("action_sub",  "string", "Sub-action for commands with variants (list/push/pop etc).", required = false),
                ToolParameter("limit",       "string", "Result limit.", required = false)
            )
        )
    )

    // ── Execution Router ──────────────────────────────────────────────────

    suspend fun execute(
        context: Context,
        action:  String,
        args:    Map<String, String>
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val cwd = args["cwd"]
        val envProfile = args["env_profile"]

        when (action.lowercase().trim()) {

            // ── Environment ─────────────────────────────────────────────

            "env_check", "termux_check" ->
                EnvironmentSetupManager.statusReport()

            "env_probe" -> {
                val state = EnvironmentSetupManager.probe(force = true)
                ToolExecutionResult("Probe complete: ${state.summaryLine()}")
            }

            "bootstrap" -> runBootstrap(args)

            "bootstrap_status" -> bootstrapStatus()

            "tool_which" -> {
                val tool = args["tool"] ?: return@withContext err("tool_which requires 'tool'")
                val path = EnvironmentSetupManager.findBinary(sanitizeName(tool))
                if (path != null) ToolExecutionResult("✅ $tool → $path")
                else ToolExecutionResult("❌ '$tool' not found.", isError = true)
            }

            "install_python" -> pkgInstallSingle("python")
            "install_node"   -> pkgInstallSingle("nodejs")
            "install_git"    -> pkgInstallSingle("git")

            // ── Code Execution ───────────────────────────────────────────

            "shell_script", "termux_run" -> {
                val script = args["script"] ?: args["command"]
                    ?: return@withContext err("Requires 'script' or 'command'")
                val interpreter = args["interpreter"]?.lowercase()?.trim()
                    ?.takeIf { it in ALLOWED_INTERPRETERS } ?: "sh"
                val envPfx = envProfile?.let { EnvVarRegistry.buildPrefix(it) } ?: ""
                EnvironmentSetupManager.executeShell(
                    script    = "${envPfx}${if (interpreter != "sh") "$interpreter -c " else ""}$script",
                    cwd       = cwd
                )
            }

            "python_run" -> {
                val code = args["code"] ?: return@withContext err("python_run requires 'code'")
                val envPfx = envProfile?.let { EnvVarRegistry.buildPrefix(it) } ?: ""
                EnvironmentSetupManager.runPython(
                    code      = "${envPfx}$code",
                    extraArgs = args["args"],
                    cwd       = cwd,
                    venvPath  = args["venv_path"]
                )
            }

            "node_run" -> {
                val code = args["code"] ?: return@withContext err("node_run requires 'code'")
                nodeRun(code, cwd, envProfile)
            }

            "run_script", "script_run" -> {
                val name = args["name"] ?: return@withContext err("Requires 'name'")
                runStoredScript(name, args["args"], cwd, envProfile)
            }

            "pipe_exec" -> {
                val pipeline = args["pipeline"]
                    ?: return@withContext err("pipe_exec requires 'pipeline' as JSON array")
                pipeExec(pipeline, cwd)
            }

            // ── Background Jobs ──────────────────────────────────────────

            "job_submit" -> {
                val script = args["script"] ?: return@withContext err("job_submit requires 'script'")
                val desc = script.take(80)
                val id = JobQueue.submit(desc) {
                    EnvironmentSetupManager.executeShell(script, cwd)
                }
                ToolExecutionResult("✅ Job submitted: $id\nUse action=job_status job_id=$id to check progress.")
            }

            "job_status" -> {
                val id = args["job_id"] ?: return@withContext err("job_status requires 'job_id'")
                val job = JobQueue.get(id)
                    ?: return@withContext ToolExecutionResult("❌ Job not found: $id", isError = true)
                val duration = when {
                    job.completedAt != null && job.startedAt != null ->
                        "${job.completedAt!! - job.startedAt!!}ms"
                    job.startedAt != null ->
                        "${System.currentTimeMillis() - job.startedAt!!}ms (running)"
                    else -> "queued"
                }
                ToolExecutionResult(
                    "Job: ${job.id}\n" +
                    "Status: ${job.status}\n" +
                    "Description: ${job.description}\n" +
                    "Duration: $duration\n" +
                    "Result:\n${job.result?.output ?: "(pending)"}"
                )
            }

            "job_list" -> {
                val jobs = JobQueue.listAll()
                if (jobs.isEmpty()) return@withContext ToolExecutionResult("No jobs in queue.")
                val sb = StringBuilder("Jobs (${jobs.size}):\n")
                jobs.forEach { job ->
                    sb.appendLine("  [${job.id}] ${job.status} | ${job.description.take(60)}")
                }
                ToolExecutionResult(sb.toString().trimEnd())
            }

            "job_cancel" -> {
                val id = args["job_id"] ?: return@withContext err("job_cancel requires 'job_id'")
                if (JobQueue.cancel(id)) ToolExecutionResult("✅ Job $id cancelled.")
                else ToolExecutionResult("❌ Cannot cancel job $id (not found or already finished).", isError = true)
            }

            "job_purge" -> {
                JobQueue.purgeCompleted()
                ToolExecutionResult("✅ Completed/failed/cancelled jobs purged from queue.")
            }

            // ── Packages ─────────────────────────────────────────────────

            "pip_install" -> {
                val pkgs = args["packages"] ?: return@withContext err("pip_install requires 'packages'")
                EnvironmentSetupManager.pipInstall(
                    packages  = pkgs,
                    upgrade   = args["upgrade"]?.lowercase() == "true",
                    venvPath  = args["venv_path"]
                )
            }

            "pip_list" -> EnvironmentSetupManager.executeShell(
                "${EnvironmentSetupManager.buildEnvPrefix()}pip3 list --format=columns 2>&1 || pip list --format=columns 2>&1"
            )

            "npm_install" -> {
                val pkgs = args["packages"] ?: return@withContext err("npm_install requires 'packages'")
                val isGlobal = args["global"]?.lowercase() != "false"
                EnvironmentSetupManager.npmCommand(
                    subcommand = "install ${if (isGlobal) "-g " else ""}$pkgs",
                    cwd        = cwd
                )
            }

            "pkg_install" -> {
                val pkgs = args["packages"] ?: return@withContext err("pkg_install requires 'packages'")
                EnvironmentSetupManager.pkgInstall(pkgs)
            }

            "pkg_update" -> EnvironmentSetupManager.executeShell(
                "${EnvironmentSetupManager.buildEnvPrefix()}DEBIAN_FRONTEND=noninteractive " +
                "${EnvironmentSetupManager.TERMUX_APT} update -y 2>&1"
            )

            "pkg_upgrade" -> EnvironmentSetupManager.executeShell(
                "${EnvironmentSetupManager.buildEnvPrefix()}DEBIAN_FRONTEND=noninteractive " +
                "${EnvironmentSetupManager.TERMUX_APT} upgrade -y 2>&1"
            )

            // ── File Operations ───────────────────────────────────────────

            "file_read" -> {
                val path = args["path"] ?: return@withContext err("file_read requires 'path'")
                validatePath(path)?.let { return@withContext it }
                val maxLines = args["max_lines"]?.toIntOrNull()?.coerceIn(1, 2000) ?: 500
                EnvironmentSetupManager.executeShell("head -n $maxLines ${sq(path)} 2>&1")
            }

            "file_write" -> {
                val path    = args["path"]    ?: return@withContext err("file_write requires 'path'")
                val content = args["content"] ?: return@withContext err("file_write requires 'content'")
                validatePath(path)?.let { return@withContext it }
                val append = args["append"]?.lowercase() == "true"
                val redirect = if (append) ">>" else ">"
                // Use base64 to write content safely without any quoting issues
                val b64 = android.util.Base64.encodeToString(
                    content.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
                EnvironmentSetupManager.executeShell(
                    "mkdir -p \$(dirname ${sq(path)}) && echo '$b64' | base64 -d $redirect ${sq(path)} && echo 'WRITE_OK'"
                )
            }

            "file_list" -> {
                val path      = args["path"] ?: "."
                val recursive = args["recursive"]?.lowercase() == "true"
                val flags     = if (recursive) "-la -R" else "-la"
                validatePath(path)?.let { return@withContext it }
                EnvironmentSetupManager.executeShell("ls $flags ${sq(path)} 2>&1 | head -200")
            }

            "file_delete" -> {
                val path = args["path"] ?: return@withContext err("file_delete requires 'path'")
                validatePath(path)?.let { return@withContext it }
                EnvironmentSetupManager.executeShell("rm -rf ${sq(path)} && echo 'DELETED'")
            }

            "file_copy" -> {
                val src = args["src"] ?: return@withContext err("file_copy requires 'src'")
                val dst = args["dst"] ?: return@withContext err("file_copy requires 'dst'")
                validatePath(src)?.let { return@withContext it }
                validatePath(dst)?.let { return@withContext it }
                EnvironmentSetupManager.executeShell("cp -r ${sq(src)} ${sq(dst)} && echo 'COPIED'")
            }

            "file_move" -> {
                val src = args["src"] ?: return@withContext err("file_src requires 'src'")
                val dst = args["dst"] ?: return@withContext err("file_move requires 'dst'")
                validatePath(src)?.let { return@withContext it }
                validatePath(dst)?.let { return@withContext it }
                EnvironmentSetupManager.executeShell("mv ${sq(src)} ${sq(dst)} && echo 'MOVED'")
            }

            "file_stat" -> {
                val path = args["path"] ?: return@withContext err("file_stat requires 'path'")
                validatePath(path)?.let { return@withContext it }
                EnvironmentSetupManager.executeShell(
                    "stat ${sq(path)} 2>&1 && echo '' && ls -lah ${sq(path)} 2>&1"
                )
            }

            "file_find" -> {
                val path    = args["path"]    ?: "."
                val pattern = args["pattern"] ?: return@withContext err("file_find requires 'pattern'")
                val type    = args["type"]?.let { if (it in listOf("f", "d")) " -type $it" else "" } ?: ""
                validatePath(path)?.let { return@withContext it }
                val safePattern = pattern.replace(Regex("[^a-zA-Z0-9_.\\-*?]"), "")
                EnvironmentSetupManager.executeShell(
                    "find ${sq(path)} -name ${sq(safePattern)}$type 2>&1 | head -100"
                )
            }

            "file_grep" -> {
                val path      = args["path"]    ?: return@withContext err("file_grep requires 'path'")
                val pattern   = args["pattern"] ?: return@withContext err("file_grep requires 'pattern'")
                val recursive = args["recursive"]?.lowercase() == "true"
                validatePath(path)?.let { return@withContext it }
                val flag = if (recursive) "-r" else ""
                EnvironmentSetupManager.executeShell(
                    "grep -n $flag ${sq(pattern)} ${sq(path)} 2>&1 | head -100"
                )
            }

            "file_chmod" -> {
                val path = args["path"] ?: return@withContext err("file_chmod requires 'path'")
                val mode = args["mode"] ?: return@withContext err("file_chmod requires 'mode'")
                validatePath(path)?.let { return@withContext it }
                val safeMode = mode.replace(Regex("[^a-z0-9+\\-=]"), "")
                EnvironmentSetupManager.executeShell("chmod $safeMode ${sq(path)} && echo 'OK'")
            }

            "file_mkdir" -> {
                val path = args["path"] ?: return@withContext err("file_mkdir requires 'path'")
                validatePath(path)?.let { return@withContext it }
                EnvironmentSetupManager.executeShell("mkdir -p ${sq(path)} && echo 'CREATED'")
            }

            // ── Git Operations ────────────────────────────────────────────

            "git_clone" -> {
                val url    = args["url"]    ?: return@withContext err("git_clone requires 'url'")
                val dest   = args["dest"]
                val branch = args["branch"]?.let { " -b ${sanitizeName(it)}" } ?: ""
                val depth  = args["depth"]?.toIntOrNull()?.let { " --depth $it" } ?: ""
                validateUrl(url) ?: return@withContext err("Invalid URL: $url")
                val destArg = if (dest != null) {
                    validatePath(dest)?.let { return@withContext it }
                    " ${sq(dest)}"
                } else ""
                gitExec("clone$branch$depth ${sq(url)}$destArg", cwd)
            }

            "git_pull" -> {
                val remote = sanitizeName(args["remote"] ?: "origin")
                val branch = sanitizeName(args["branch"] ?: "")
                val refspec = if (branch.isNotBlank()) " $remote $branch" else " $remote"
                gitExec("pull$refspec", cwd)
            }

            "git_push" -> {
                val remote = sanitizeName(args["remote"] ?: "origin")
                val branch = sanitizeName(args["branch"] ?: "")
                val refspec = if (branch.isNotBlank()) " $remote $branch" else " $remote"
                gitExec("push$refspec", cwd)
            }

            "git_status"   -> gitExec("status --short --branch", cwd)
            "git_log"      -> {
                val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 10
                gitExec("log --oneline --graph --decorate -$limit", cwd)
            }
            "git_diff"     -> gitExec("diff --stat HEAD 2>&1 && git diff", cwd)

            "git_commit" -> {
                val message = args["message"] ?: return@withContext err("git_commit requires 'message'")
                // Stage all and commit
                gitExecMulti(listOf("add -A", "commit -m ${sq(message)}"), cwd)
            }

            "git_checkout" -> {
                val branch = sanitizeName(args["branch"] ?: return@withContext err("git_checkout requires 'branch'"))
                val create = if (args["create"]?.lowercase() == "true") "-b " else ""
                gitExec("checkout ${create}${branch}", cwd)
            }

            "git_stash" -> {
                val action = sanitizeName(args["action_sub"] ?: args["action_sub"] ?: "push")
                gitExec("stash $action", cwd)
            }

            "git_reset" -> {
                val mode = when (args["mode"]?.lowercase()) {
                    "soft"  -> "--soft"
                    "hard"  -> "--hard"
                    else    -> "--mixed"
                }
                gitExec("reset $mode HEAD", cwd)
            }

            "git_branch" -> {
                val action = args["action_sub"]?.lowercase() ?: "list"
                when (action) {
                    "list"   -> gitExec("branch -avv", cwd)
                    "delete" -> {
                        val branch = sanitizeName(args["branch"]
                            ?: return@withContext err("git_branch delete requires 'branch'"))
                        gitExec("branch -d $branch", cwd)
                    }
                    else -> gitExec("branch -avv", cwd)
                }
            }

            "git_init"   -> gitExec("init", cwd)
            "git_remote" -> gitExec("remote -v", cwd)

            "git_tag" -> {
                val name = args["name"]
                if (name != null) {
                    val safeName = sanitizeName(name)
                    gitExec("tag $safeName", cwd)
                } else {
                    gitExec("tag --list", cwd)
                }
            }

            // ── Process Management ────────────────────────────────────────

            "proc_list" -> {
                val filter = args["filter"]?.let { " | grep -i ${sq(it)}" } ?: ""
                val sortFlag = when (args["sort"]?.lowercase()) {
                    "cpu" -> " --sort=-%cpu"
                    "mem" -> " --sort=-%mem"
                    "pid" -> " --sort=pid"
                    else  -> ""
                }
                EnvironmentSetupManager.executeShell(
                    "ps -eo pid,ppid,%cpu,%mem,stat,comm$sortFlag 2>&1$filter | head -60"
                )
            }

            "proc_kill" -> {
                val target = args["target"] ?: return@withContext err("proc_kill requires 'target'")
                val signal = args["signal"]?.toIntOrNull()?.coerceIn(1, 64) ?: 15
                val safeTarget = target.replace(Regex("[^a-zA-Z0-9_.\\-]"), "")
                // Try as PID first, then as name
                val cmd = if (safeTarget.all { it.isDigit() }) {
                    "kill -$signal $safeTarget 2>&1 && echo 'KILLED PID $safeTarget'"
                } else {
                    "pkill -$signal -x $safeTarget 2>&1 && echo 'KILLED $safeTarget'"
                }
                EnvironmentSetupManager.executeShell(cmd)
            }

            "proc_top" -> {
                val count = args["count"]?.toIntOrNull()?.coerceIn(1, 30) ?: 10
                EnvironmentSetupManager.executeShell(
                    "ps -eo pid,%cpu,%mem,comm --sort=-%cpu 2>&1 | head -${count + 1}"
                )
            }

            "proc_find" -> {
                val pattern = args["pattern"] ?: return@withContext err("proc_find requires 'pattern'")
                val safeP = pattern.replace(Regex("[^a-zA-Z0-9_.\\-]"), "")
                EnvironmentSetupManager.executeShell(
                    "ps -eo pid,ppid,%cpu,%mem,comm 2>&1 | grep -i ${sq(safeP)}"
                )
            }

            // ── Script Store ──────────────────────────────────────────────

            "script_save" -> {
                val name = args["name"]     ?: return@withContext err("script_save requires 'name'")
                val code = args["code"]     ?: return@withContext err("script_save requires 'code'")
                val lang = args["language"] ?: "sh"
                if (lang !in ALLOWED_INTERPRETERS + listOf("python", "python3")) {
                    return@withContext err("Invalid language. Use: sh, bash, python, python3, node.")
                }
                ScriptStore.save(StoredScript(
                    name        = sanitizeName(name),
                    language    = lang,
                    code        = code,
                    description = args["description"] ?: ""
                ))
                ToolExecutionResult("✅ Script '${sanitizeName(name)}' saved ($lang, ${code.length} chars).")
            }

            "script_list" -> {
                val scripts = ScriptStore.list()
                if (scripts.isEmpty()) return@withContext ToolExecutionResult("No scripts saved.")
                val sb = StringBuilder("Saved scripts (${scripts.size}):\n")
                scripts.forEach { s ->
                    sb.appendLine("  [${s.name}] (${s.language}) ${s.description.take(60)}")
                }
                ToolExecutionResult(sb.toString().trimEnd())
            }

            "script_get" -> {
                val name = args["name"] ?: return@withContext err("script_get requires 'name'")
                val script = ScriptStore.get(sanitizeName(name))
                    ?: return@withContext ToolExecutionResult("❌ Script '$name' not found.", isError = true)
                ToolExecutionResult(
                    "Name: ${script.name}\nLanguage: ${script.language}\n" +
                    "Description: ${script.description}\nCode:\n${script.code}"
                )
            }

            "script_delete" -> {
                val name = args["name"] ?: return@withContext err("script_delete requires 'name'")
                if (ScriptStore.delete(sanitizeName(name)))
                    ToolExecutionResult("✅ Script '$name' deleted.")
                else
                    ToolExecutionResult("❌ Script '$name' not found.", isError = true)
            }

            // ── Environment Variables ─────────────────────────────────────

            "env_set" -> {
                val profile = args["profile"] ?: return@withContext err("env_set requires 'profile'")
                val key     = args["key"]     ?: return@withContext err("env_set requires 'key'")
                val value   = args["value"]   ?: return@withContext err("env_set requires 'value'")
                val safeKey = key.replace(Regex("[^a-zA-Z0-9_]"), "")
                if (safeKey.isEmpty()) return@withContext err("Invalid env key.")
                EnvVarRegistry.set(profile, safeKey, value)
                ToolExecutionResult("✅ Set $profile/$safeKey")
            }

            "env_get" -> {
                val profile = args["profile"] ?: return@withContext err("env_get requires 'profile'")
                val key     = args["key"]     ?: return@withContext err("env_get requires 'key'")
                val value   = EnvVarRegistry.get(profile, key)
                    ?: return@withContext ToolExecutionResult("$profile/$key is not set.", isError = true)
                ToolExecutionResult("$profile/$key = $value")
            }

            "env_list" -> {
                val profile = args["profile"] ?: return@withContext err("env_list requires 'profile'")
                val vars = EnvVarRegistry.getAll(profile)
                if (vars.isEmpty()) return@withContext ToolExecutionResult("No vars in profile '$profile'.")
                ToolExecutionResult(vars.entries.joinToString("\n") { "${it.key}=${it.value}" })
            }

            "env_delete" -> {
                val profile = args["profile"] ?: return@withContext err("env_delete requires 'profile'")
                val key     = args["key"]     ?: return@withContext err("env_delete requires 'key'")
                if (EnvVarRegistry.delete(profile, key))
                    ToolExecutionResult("✅ Deleted $profile/$key")
                else
                    ToolExecutionResult("$profile/$key not found.", isError = true)
            }

            "env_delete_profile" -> {
                val profile = args["profile"] ?: return@withContext err("env_delete_profile requires 'profile'")
                if (EnvVarRegistry.deleteProfile(profile))
                    ToolExecutionResult("✅ Profile '$profile' deleted.")
                else
                    ToolExecutionResult("Profile '$profile' not found.", isError = true)
            }

            "env_list_profiles" -> {
                val profiles = EnvVarRegistry.listProfiles()
                if (profiles.isEmpty()) ToolExecutionResult("No env profiles saved.")
                else ToolExecutionResult("Env profiles:\n${profiles.joinToString("\n") { "  • $it" }}")
            }

            // ── Downloads ─────────────────────────────────────────────────

            "download_file" -> {
                val url = args["url"] ?: return@withContext err("download_file requires 'url'")
                downloadFile(url, args["dest_path"])
            }

            "download_exec" -> {
                val url = args["url"] ?: return@withContext err("download_exec requires 'url'")
                downloadExec(url, args["args"] ?: "", cwd)
            }

            "download_verify" -> {
                val url      = args["url"]      ?: return@withContext err("download_verify requires 'url'")
                val checksum = args["checksum"] ?: return@withContext err("download_verify requires 'checksum'")
                downloadVerify(url, checksum, args["dest"])
            }

            "install_tool" -> ToolDownloaderEngine.installTool(args)

            "tools_status" -> ToolDownloaderEngine.toolsStatus()

            else -> err("Unknown action: '$action'. See tool description for available actions.")
        }
    }

    // ── Private: Action Implementations ───────────────────────────────────

    private suspend fun nodeRun(
        code:       String,
        cwd:        String?,
        envProfile: String?
    ): ToolExecutionResult {
        val nodeBin = EnvironmentSetupManager.findBinary("node")
            ?: return err("Node.js not found. Run action=install_node.")
        val envPfx = buildString {
            append(EnvironmentSetupManager.buildEnvPrefix())
            if (envProfile != null) append(EnvVarRegistry.buildPrefix(envProfile))
        }
        val script = buildString {
            if (!cwd.isNullOrBlank()) appendLine("cd ${sq(cwd)} || exit 1")
            appendLine("${envPfx}${nodeBin} -e ${sq(code)}")
        }
        return EnvironmentSetupManager.executeShell(script, useBase64 = true)
    }

    private suspend fun runStoredScript(
        name:       String,
        extraArgs:  String?,
        cwd:        String?,
        envProfile: String?
    ): ToolExecutionResult {
        val script = ScriptStore.get(sanitizeName(name))
            ?: return ToolExecutionResult("❌ Script '$name' not found. Use action=script_list.", isError = true)
        val envPfx = envProfile?.let { EnvVarRegistry.buildPrefix(it) } ?: ""
        val argStr = extraArgs?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ") { sq(it) }
            ?.let { " $it" } ?: ""

        return when (script.language.lowercase()) {
            "python", "python3" ->
                EnvironmentSetupManager.runPython("$envPfx${script.code}", extraArgs, cwd)
            "node" ->
                nodeRun("$envPfx${script.code}", cwd, envProfile)
            else ->
                EnvironmentSetupManager.executeShell("$envPfx${script.code}$argStr", cwd)
        }
    }

    private suspend fun pipeExec(pipelineJson: String, cwd: String?): ToolExecutionResult {
        val commands = try {
            // Simple JSON array parse — avoids importing a full JSON library
            pipelineJson.trim()
                .removePrefix("[").removeSuffix("]")
                .split(Regex(""",\s*(?="[^"]*")"""))
                .map { it.trim().removePrefix("\"").removeSuffix("\"") }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            return err("Invalid pipeline JSON: ${e.message}")
        }
        if (commands.isEmpty()) return err("Empty pipeline.")
        val joined = commands.joinToString(" | ")
        return EnvironmentSetupManager.executeShell(joined, cwd)
    }

    private suspend fun runBootstrap(args: Map<String, String>): ToolExecutionResult {
        val plan = EnvironmentSetupManager.buildStandardBootstrapPlan()
        val sb = StringBuilder("=== OmniDev Bootstrap ===\n\n")
        var failures = 0

        EnvironmentSetupManager.runBootstrap(plan) { stepId, result ->
            val icon = when (result) {
                is BootstrapResult.Success    -> "✅"
                is BootstrapResult.AlreadyDone -> "✔️"
                is BootstrapResult.Skipped    -> "⏭️"
                is BootstrapResult.Failed     -> { failures++; "❌" }
            }
            val msg = when (result) {
                is BootstrapResult.Success     -> result.message
                is BootstrapResult.AlreadyDone -> result.message
                is BootstrapResult.Skipped     -> result.reason
                is BootstrapResult.Failed      -> "${result.reason}${if (result.hint.isNotBlank()) "\n   Hint: ${result.hint}" else ""}"
            }
            sb.appendLine("$icon [$stepId] $msg")
        }

        sb.appendLine()
        sb.appendLine(if (failures == 0) "✅ Bootstrap complete!" else "⚠️ Bootstrap finished with $failures failure(s).")
        return ToolExecutionResult(sb.toString().trimEnd(), isError = failures > 0)
    }

    private suspend fun bootstrapStatus(): ToolExecutionResult {
        val plan  = EnvironmentSetupManager.buildStandardBootstrapPlan()
        val sb    = StringBuilder("Bootstrap Plan Status:\n\n")
        plan.forEach { step ->
            val done = try { step.checkFn() } catch (e: Exception) { false }
            sb.appendLine("${if (done) "✅" else "❌"} [${step.id}] ${step.description}")
        }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private suspend fun pkgInstallSingle(pkg: String): ToolExecutionResult {
        return EnvironmentSetupManager.pkgInstall(pkg).also {
            if (!it.isError) EnvironmentSetupManager.probe(force = true)
        }
    }

    private suspend fun gitExec(subcommand: String, cwd: String?): ToolExecutionResult {
        val gitBin = EnvironmentSetupManager.findBinary("git")
            ?: return err("Git not found. Run action=install_git.")
        val envPfx = EnvironmentSetupManager.buildEnvPrefix()
        val script = buildString {
            if (!cwd.isNullOrBlank()) appendLine("cd ${sq(cwd)} || exit 1")
            appendLine("${envPfx}${gitBin} $subcommand 2>&1")
        }
        return EnvironmentSetupManager.executeShell(script, useBase64 = false)
    }

    private suspend fun gitExecMulti(subcommands: List<String>, cwd: String?): ToolExecutionResult {
        val gitBin = EnvironmentSetupManager.findBinary("git")
            ?: return err("Git not found. Run action=install_git.")
        val envPfx = EnvironmentSetupManager.buildEnvPrefix()
        val script = buildString {
            if (!cwd.isNullOrBlank()) appendLine("cd ${sq(cwd)} || exit 1")
            subcommands.forEach { sub ->
                appendLine("${envPfx}${gitBin} $sub 2>&1 || exit 1")
            }
        }
        return EnvironmentSetupManager.executeShell(script, useBase64 = true)
    }

    private suspend fun downloadFile(url: String, destPath: String?): ToolExecutionResult {
        return ToolDownloaderEngine.downloadFile(url, destPath)
    }

    private suspend fun downloadExec(url: String, extraArgs: String, cwd: String?): ToolExecutionResult {
        return ToolDownloaderEngine.downloadExec(url, extraArgs, cwd)
    }

    private suspend fun downloadVerify(url: String, checksum: String, dest: String?): ToolExecutionResult {
        return ToolDownloaderEngine.downloadVerify(url, checksum, dest)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private val ALLOWED_INTERPRETERS = setOf("sh", "bash", "dash", "ash", "python3", "python", "node", "perl", "ruby")

    private fun err(msg: String) = ToolExecutionResult(msg, isError = true)

    private fun sq(s: String) = EnvironmentSetupManager.shellQuote(s)

    private fun sanitizeName(name: String) = name.replace(Regex("[^a-zA-Z0-9_.\\-]"), "")

    private fun validatePath(path: String): ToolExecutionResult? {
        if (path.contains("..")) return err("Path must not contain '..'")
        return null
    }

    private fun validateUrl(url: String): String? {
        return try {
            val parsed = URL(url.trim())
            if (parsed.protocol !in setOf("http", "https")) null else url.trim()
        } catch (_: MalformedURLException) { null }
    }
}
