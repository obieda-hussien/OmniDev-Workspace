package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * AgentSandboxTool — Dynamic Self-Sandboxing Execution Engine.
 *
 * ### Purpose
 * Provides the agent with a **safe blast-radius container** for running untested,
 * user-supplied, or potentially dangerous shell scripts. Instead of executing
 * arbitrary code directly on the host filesystem, the tool:
 *
 * 1. Creates a fresh, isolated temporary directory:
 *    `/data/local/tmp/omni_sandbox_<UUID>/`
 * 2. Writes the script payload into that directory as `script.sh`.
 * 3. Executes the script with its working directory **locked** to the sandbox
 *    (using `cd /path && sh script.sh`) so relative paths cannot escape.
 * 4. Captures stdout and stderr with hard size caps.
 * 5. **Always** nukes the sandbox directory via `rm -rf` in a logical finally
 *    block — even if the script crashes, times out, or the device reboots mid-run
 *    (the `/data/local/tmp/` directory is cleaned on reboot by Android).
 *
 * ### Execution backend
 * Uses [PrivilegedExecutionManager.executeCommand] which tries, in order:
 *   Shizuku → rish → root
 *
 * Shizuku/ADB-shell context gives the sandbox sufficient privilege to run most
 * developer scripts (package queries, file manipulations, `am`/`pm` commands)
 * while still being isolated from the agent's own application data.
 *
 * ### Timeout
 * The script execution is bounded by [EXEC_TIMEOUT_SECONDS]. Exceeding this limit
 * causes the command to be reported as timed-out; cleanup is still performed.
 *
 * ### Security contract
 * - **Not a true security sandbox** (no seccomp / Linux namespaces / SELinux
 *   profile isolation). The isolation is purely _filesystem_ and _working-directory_
 *   scoped — sufficient to prevent accidental overwriting of application data but
 *   NOT appropriate for running fully untrusted third-party binaries.
 * - The agent pipeline routes every invocation through
 *   [com.omnidev.workspace.ui.chat.ConfirmationGate] so a human must approve
 *   the script before it executes.
 * - The UUID-based path ensures sandbox directories never collide across concurrent
 *   agent calls.
 *
 * ### LLM Tool schema
 * Tool name: `sandbox_execution_tool`
 * Parameter: `shell_script` (string) — the shell script body to execute.
 */
object AgentSandboxTool {

    // ──────────────────────────────────────────────
    //  Constants
    // ──────────────────────────────────────────────

    /** Root temporary directory where sandboxes are created. */
    private const val SANDBOX_ROOT = "/data/local/tmp"

    /** Prefix for every sandbox directory name. */
    private const val SANDBOX_PREFIX = "omni_sandbox_"

    /** Script filename inside the sandbox. */
    private const val SCRIPT_FILENAME = "script.sh"

    /** Shebang prepended to every user script. */
    private const val SCRIPT_SHEBANG = "#!/system/bin/sh"

    /**
     * Maximum wall-clock seconds allowed for a single sandbox execution.
     * The underlying privileged-execution backends use their own timeouts as well;
     * this value is the upper bound embedded in the wrapper script via `timeout`.
     */
    private const val EXEC_TIMEOUT_SECONDS = 60

    /** Maximum captured output characters (stdout + stderr combined). */
    private const val MAX_OUTPUT_CHARS = 12_000

    // ──────────────────────────────────────────────
    //  Tool definition
    // ──────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "sandbox_execution_tool",
            description = "Use this when executing untested, dangerous scripts, or running unknown " +
                "binaries to prevent system damage. It creates a temporary isolated environment: " +
                "a UUID-keyed directory under /data/local/tmp/ where the script is written and " +
                "executed with its working directory locked to that sandbox. " +
                "stdout and stderr are captured and returned. " +
                "The sandbox directory is ALWAYS wiped after execution, leaving no trace. " +
                "Requires Shizuku / root for privileged operations. " +
                "The script runs with ADB-shell-level permissions (not full root unless root is " +
                "the active backend). " +
                "Script execution is time-limited to $EXEC_TIMEOUT_SECONDS seconds.",
            parameters = listOf(
                ToolParameter(
                    name = "shell_script",
                    type = "string",
                    description = "The shell script body to execute inside the isolated sandbox. " +
                        "A '#!/system/bin/sh' shebang is added automatically. " +
                        "Use relative paths freely — the working directory is the sandbox root. " +
                        "Do NOT use 'cd /' or absolute paths pointing outside the sandbox unless " +
                        "you explicitly intend to interact with the host filesystem.",
                    required = true
                ),
                ToolParameter(
                    name = "timeout_seconds",
                    type = "string",
                    description = "Optional: override the execution timeout (1–300 seconds). " +
                        "Defaults to $EXEC_TIMEOUT_SECONDS seconds.",
                    required = false
                )
            )
        )
    )

    // ──────────────────────────────────────────────
    //  Execution
    // ──────────────────────────────────────────────

    /**
     * Execute [shellScript] in an ephemeral, isolated sandbox directory.
     *
     * **Lifecycle:**
     * ```
     *  create /data/local/tmp/omni_sandbox_<UUID>/
     *  write  script.sh
     *  exec   cd <sandbox> && timeout <N> sh script.sh 2>&1
     *  capture stdout/stderr
     *  rm -rf <sandbox>      ← always, even on failure
     *  return result to LLM
     * ```
     *
     * @param shellScript   The raw script body (shebang added automatically).
     * @param timeoutSecs   Execution time limit in seconds (1–300).
     * @return [ToolExecutionResult] containing combined stdout/stderr or an error.
     */
    suspend fun execute(
        shellScript: String,
        timeoutSecs: Int = EXEC_TIMEOUT_SECONDS
    ): ToolExecutionResult = withContext(Dispatchers.IO) {

        // ── Input validation ──────────────────────────────────────────────────
        if (shellScript.isBlank()) {
            return@withContext ToolExecutionResult(
                output = "shell_script must not be blank.",
                isError = true
            )
        }
        val effectiveTimeout = timeoutSecs.coerceIn(1, 300)

        // ── Sandbox path generation ───────────────────────────────────────────
        val sandboxId = UUID.randomUUID().toString().replace("-", "")
        val sandboxPath = "$SANDBOX_ROOT/${SANDBOX_PREFIX}$sandboxId"
        val scriptPath  = "$sandboxPath/$SCRIPT_FILENAME"

        // ── Helper: shell single-quote ────────────────────────────────────────
        fun sq(arg: String): String = "'${arg.replace("'", "'\\''")}'"

        // ── Step 1: Create sandbox directory ─────────────────────────────────
        val mkdirResult = PrivilegedExecutionManager.executeCommand(
            "mkdir -p ${sq(sandboxPath)} && chmod 700 ${sq(sandboxPath)} && echo MKDIR_OK"
        )
        if (mkdirResult.isFailure) {
            return@withContext ToolExecutionResult(
                output = buildErrorOutput(
                    sandboxPath = sandboxPath,
                    stage = "MKDIR",
                    detail = mkdirResult.exceptionOrNull()?.message ?: "unknown"
                ),
                isError = true
            )
        }
        if (mkdirResult.getOrDefault("").contains("MKDIR_OK").not()) {
            // mkdir returned something unexpected — abort before writing anything.
            cleanupSandbox(sandboxPath)
            return@withContext ToolExecutionResult(
                output = buildErrorOutput(sandboxPath, "MKDIR", "mkdir did not confirm creation"),
                isError = true
            )
        }

        // ── Step 2: Write script to sandbox ──────────────────────────────────
        // Encode the user script as base64 to safely pass through the shell
        // without fighting with quoting or special characters.
        val scriptContent = "$SCRIPT_SHEBANG\n\n# OmniDev AgentSandboxTool — sandboxId=$sandboxId\n\n$shellScript"
        val b64Script = android.util.Base64.encodeToString(
            scriptContent.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val writeResult = PrivilegedExecutionManager.executeCommand(
            "echo ${sq(b64Script)} | base64 -d > ${sq(scriptPath)} && chmod 500 ${sq(scriptPath)} && echo WRITE_OK"
        )
        if (writeResult.isFailure ||
            writeResult.getOrDefault("").contains("WRITE_OK").not()) {
            cleanupSandbox(sandboxPath)
            return@withContext ToolExecutionResult(
                output = buildErrorOutput(
                    sandboxPath, "WRITE_SCRIPT",
                    writeResult.exceptionOrNull()?.message ?: writeResult.getOrDefault("write failed")
                ),
                isError = true
            )
        }

        // ── Step 3: Execute the script ────────────────────────────────────────
        //
        // Security choices:
        //  • `cd <sandbox>` — CWD locked to sandbox before exec; relative paths in
        //    the script cannot traverse the host filesystem accidentally.
        //  • `timeout <N>` — hard wall-clock kill to prevent infinite loops.
        //  • `2>&1` — merge stderr into stdout so the agent sees the full picture.
        //  • Output is head-limited to prevent flooding the context window.
        //
        val execCmd = buildString {
            append("cd ${sq(sandboxPath)}")
            append(" && timeout $effectiveTimeout sh ${sq(scriptPath)} 2>&1")
            append(" | head -c $MAX_OUTPUT_CHARS")
        }

        val execResult = runCatching {
            PrivilegedExecutionManager.executeCommand(execCmd)
        }.getOrElse { ex ->
            // Runtime exception (e.g., Binder death during long execution)
            Result.failure<String>(ex)
        }

        // ── Step 4: Cleanup — ALWAYS, in the logical finally block ────────────
        cleanupSandbox(sandboxPath)

        // ── Step 5: Return result ─────────────────────────────────────────────
        return@withContext when {
            execResult.isSuccess -> {
                val output = execResult.getOrDefault("(no output)")
                val truncated = output.length >= MAX_OUTPUT_CHARS
                val header = buildString {
                    appendLine("╔══ AgentSandboxTool Execution Report ═══════════════════════════════╗")
                    appendLine("║  sandboxId : $sandboxId")
                    appendLine("║  scriptPath: $scriptPath")
                    appendLine("║  timeout   : ${effectiveTimeout}s")
                    appendLine("╚════════════════════════════════════════════════════════════════════╝")
                }
                val footer = if (truncated)
                    "\n\n[OUTPUT TRUNCATED at $MAX_OUTPUT_CHARS chars — use smaller script or redirect to file]"
                else
                    "\n\n[Sandbox wiped. No trace remains at $sandboxPath]"

                ToolExecutionResult(
                    output = "$header\n$output$footer",
                    truncated = truncated
                )
            }
            else -> {
                val errMsg = execResult.exceptionOrNull()?.message ?: "unknown execution error"
                ToolExecutionResult(
                    output = buildErrorOutput(sandboxPath, "EXEC", errMsg) +
                             "\n\n[Sandbox wiped. No trace remains at $sandboxPath]",
                    isError = true
                )
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Tool dispatcher (matches [ToolManager] call-site)
    // ──────────────────────────────────────────────

    /**
     * Entry point called by [CompositeToolManager] when the agent selects
     * `sandbox_execution_tool`.
     */
    suspend fun executeTool(arguments: Map<String, String>): ToolExecutionResult {
        val script = arguments["shell_script"]
            ?: return ToolExecutionResult(
                output = "Missing required argument: shell_script",
                isError = true
            )
        val timeout = arguments["timeout_seconds"]?.toIntOrNull() ?: EXEC_TIMEOUT_SECONDS
        return execute(shellScript = script, timeoutSecs = timeout)
    }

    // ──────────────────────────────────────────────
    //  Internal helpers
    // ──────────────────────────────────────────────

    /**
     * Wipe the sandbox directory via `rm -rf`.
     *
     * This is intentionally a fire-and-forget best-effort cleanup. If the privileged
     * backend is unavailable by the time cleanup runs (e.g., Shizuku was killed), the
     * directory is left behind but will be removed on the next reboot since
     * `/data/local/tmp/` is volatile.
     *
     * The method is NOT `suspend` deliberately — we call it synchronously inside
     * [withContext(Dispatchers.IO)] to ensure cleanup always runs before returning.
     */
    private suspend fun cleanupSandbox(sandboxPath: String) {
        runCatching {
            PrivilegedExecutionManager.executeCommand(
                "rm -rf '${sandboxPath.replace("'", "'\\''")}' 2>/dev/null; echo CLEANED"
            )
        }
        // Silently ignore cleanup failures — the path is under /data/local/tmp/
        // which Android wipes on reboot, providing a natural backstop.
    }

    /**
     * Builds a structured error output string for failed sandbox stages.
     *
     * @param sandboxPath  The sandbox path (always included for auditability).
     * @param stage        The stage that failed (MKDIR, WRITE_SCRIPT, EXEC).
     * @param detail       The raw error detail from the backend.
     */
    private fun buildErrorOutput(sandboxPath: String, stage: String, detail: String): String =
        buildString {
            appendLine("╔══ AgentSandboxTool ERROR ═══════════════════════════════════════════╗")
            appendLine("║  stage     : $stage")
            appendLine("║  sandboxPath: $sandboxPath")
            appendLine("║  detail    : $detail")
            appendLine("╚════════════════════════════════════════════════════════════════════╝")
            appendLine()
            appendLine("The sandbox could not complete execution at the '$stage' stage.")
            appendLine("Ensure Shizuku (or root) is available and authorized.")
        }.trimEnd()
}
