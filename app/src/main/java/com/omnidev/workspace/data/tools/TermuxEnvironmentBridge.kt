package com.omnidev.workspace.data.tools

import android.util.Base64
import android.util.Log
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * TermuxEnvironmentBridge — The Ultimate God-Mode CLI & Persistent Session Engine.
 *
 * FEATURES v2:
 * 1. Persistent Background Sessions: Run background servers (Node, Python) or long builds.
 * 2. Multi-Threading: Open multiple terminal sessions simultaneously.
 * 3. Base64 Script Injection: Bypasses all bash quoting/escaping hell for single-shot commands.
 * 4. CWD Support: Agent can set the Current Working Directory for builds (npm, gradle).
 * 5. Full Package Management: Native wrappers for pip, pkg, apt, and npm.
 * 6. NEW: Timeout support for exec (default 60s, configurable up to 600s).
 * 7. NEW: session_peek — reads buffer WITHOUT clearing (safe monitoring).
 * 8. NEW: session_info — per-session uptime, cwd, buffer fill, process liveness.
 * 9. NEW: env_info — dumps the exact Termux environment that will be injected.
 * 10. NEW: which — shorthand alias for find_binary.
 */
object TermuxEnvironmentBridge {

    private const val TAG = "TermuxBridge"

    const val TERMUX_PKG          = "com.termux"
    const val TERMUX_PREFIX       = "/data/data/com.termux/files/usr"
    const val TERMUX_HOME         = "/data/data/com.termux/files/home"
    const val TERMUX_BIN          = "$TERMUX_PREFIX/bin"
    const val TERMUX_LIB          = "$TERMUX_PREFIX/lib"
    const val TERMUX_BASH         = "$TERMUX_BIN/bash"
    const val TERMUX_PYTHON3      = "$TERMUX_BIN/python3"
    const val TERMUX_PYTHON       = "$TERMUX_BIN/python"
    const val TERMUX_NODE         = "$TERMUX_BIN/node"
    const val TERMUX_NPM          = "$TERMUX_BIN/npm"
    const val TERMUX_PIP3         = "$TERMUX_BIN/pip3"
    const val TERMUX_PIP          = "$TERMUX_BIN/pip"
    const val TERMUX_GIT          = "$TERMUX_BIN/git"
    const val TERMUX_PKG_MANAGER  = "$TERMUX_BIN/pkg"
    const val TERMUX_APT          = "$TERMUX_BIN/apt"
    private const val TERMUX_EXEC_SO = "$TERMUX_LIB/libtermux-exec.so"

    // Truncates from the END of the log to capture build errors
    private const val MAX_OUTPUT = 12_000

    // ─────────────────────────────────────────────────────────────
    // Environment Building
    // ─────────────────────────────────────────────────────────────

    fun buildTermuxEnv(): Map<String, String> {
        val env = mutableMapOf(
            "HOME"           to TERMUX_HOME,
            "PREFIX"         to TERMUX_PREFIX,
            "TERM"           to "xterm-256color",
            "LANG"           to "en_US.UTF-8",
            "TMPDIR"         to "$TERMUX_PREFIX/tmp",
            "ANDROID_ROOT"   to "/system",
            "ANDROID_DATA"   to "/data",
            "PATH"           to buildPath(),
            "LD_LIBRARY_PATH" to buildLdLibraryPath()
        )
        if (File(TERMUX_EXEC_SO).exists()) {
            env["LD_PRELOAD"] = TERMUX_EXEC_SO
        }
        return env
    }

    private fun buildPath(): String {
        val paths = mutableListOf(
            TERMUX_BIN,
            "$TERMUX_PREFIX/sbin",
            "/system/bin",
            "/system/xbin",
            "/sbin",
            "/vendor/bin",
            "/data/local/tmp",
            "$TERMUX_PREFIX/lib/python3*/site-packages/bin" // Python global packages
        )
        return paths.joinToString(":")
    }

    private fun buildLdLibraryPath(): String {
        val paths = mutableListOf(
            TERMUX_LIB,
            "$TERMUX_LIB/termux-exec",
            "/system/lib64",
            "/system/lib",
            "/vendor/lib64",
            "/vendor/lib"
        )
        return paths.joinToString(":")
    }

    fun buildEnvPrefix(): String =
        buildTermuxEnv()
            .entries
            .joinToString(" ") { (k, v) -> "$k=${shellQuote(v)}" }
            .let { if (it.isNotBlank()) "$it " else "" }

    // ─────────────────────────────────────────────────────────────
    // Availability Checks
    // ─────────────────────────────────────────────────────────────

    fun isTermuxUsable(): Boolean = File(TERMUX_BASH).exists()

    suspend fun findBinary(name: String): String? = withContext(Dispatchers.IO) {
        val direct = File(TERMUX_BIN, name)
        if (direct.exists() && direct.canExecute()) return@withContext direct.absolutePath

        if (isTermuxUsable()) {
            val envPfx = buildEnvPrefix()
            val result = PrivilegedExecutionManager.executeCommand(
                "${envPfx}which $name 2>/dev/null || command -v $name 2>/dev/null"
            ).getOrNull()?.trim()?.takeIf { isValidPath(it) }
            if (result != null) return@withContext result
        }

        PrivilegedExecutionManager.executeCommand(
            "which $name 2>/dev/null || command -v $name 2>/dev/null"
        ).getOrNull()?.trim()?.takeIf { isValidPath(it) }
    }

    internal suspend fun findPythonInterpreter(): String? = withContext(Dispatchers.IO) {
        val candidates = listOf(TERMUX_PYTHON3, TERMUX_PYTHON, "/usr/bin/python3", "/system/bin/python3")
        candidates.firstOrNull { File(it).exists() } ?: findBinary("python3") ?: findBinary("python")
    }

    private fun isValidPath(s: String): Boolean =
        s.isNotBlank() && s != "(no output)" && !s.startsWith("ERROR") && s.startsWith("/")

    // ─────────────────────────────────────────────────────────────
    // Output Formatting
    // ─────────────────────────────────────────────────────────────

    private fun smartTruncate(output: String): String {
        if (output.length <= MAX_OUTPUT) return output.ifBlank { "(no output)" }
        return "...[TRUNCATED ${output.length - MAX_OUTPUT} chars]...\n" + output.takeLast(MAX_OUTPUT)
    }

    internal fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"

    private fun sanitizePackageList(packages: String): String? {
        val safe = packages.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-Z0-9_.\\-\\[\\]=~<>!@]"), "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return safe.ifBlank { null }
    }

    // ─────────────────────────────────────────────────────────────
    // Persistent Session Manager (The Engine)
    // ─────────────────────────────────────────────────────────────

    class TerminalSession(val id: String, val cwd: String?) {
        private var process: Process? = null
        private var writer: BufferedWriter? = null
        private val outputBuffer = StringBuffer()
        private val jobs = mutableListOf<Job>()
        private val scope = CoroutineScope(Dispatchers.IO)
        private val startedAt = System.currentTimeMillis()

        private val MAX_BUFFER_SIZE = 20_000

        fun start(): Boolean {
            return try {
                val shell = if (isTermuxUsable()) TERMUX_BASH else "/system/bin/sh"
                val pb = ProcessBuilder(shell).redirectErrorStream(true)
                
                // Inject Termux environment variables directly into the process
                val env = pb.environment()
                env.putAll(buildTermuxEnv())
                
                if (!cwd.isNullOrBlank()) {
                    val dir = File(cwd)
                    if (dir.exists() && dir.isDirectory) pb.directory(dir)
                }

                process = pb.start()
                writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
                val reader = BufferedReader(InputStreamReader(process!!.inputStream))

                jobs.add(scope.launch {
                    try {
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            appendOutput(line!!)
                        }
                    } catch (_: Exception) {}
                    appendOutput("\n[Process Terminated]")
                })
                
                appendOutput("Session started: $shell")
                true
            } catch (e: Exception) {
                appendOutput("Failed to start session: ${e.message}")
                false
            }
        }

        @Synchronized
        private fun appendOutput(text: String) {
            outputBuffer.append(text).append("\n")
            if (outputBuffer.length > MAX_BUFFER_SIZE) {
                outputBuffer.delete(0, outputBuffer.length - MAX_BUFFER_SIZE)
                outputBuffer.insert(0, "[...TRUNCATED...]\n")
            }
        }

        fun sendCommand(cmd: String) {
            try {
                appendOutput("$ $cmd")
                writer?.write("$cmd\n")
                writer?.flush()
            } catch (e: Exception) {
                appendOutput("Error sending command: ${e.message}")
            }
        }

        @Synchronized
        fun readOutputAndClear(): String {
            val out = outputBuffer.toString()
            outputBuffer.delete(0, outputBuffer.length)
            return out.ifBlank { "(No new output)" }
        }

        /** Read buffer WITHOUT clearing it — safe for monitoring without consuming output. */
        @Synchronized
        fun peekOutput(): String = outputBuffer.toString().ifBlank { "(No output yet)" }

        /** Human-readable session status summary. */
        fun info(): String {
            val uptimeMs  = System.currentTimeMillis() - startedAt
            val uptimeSec = uptimeMs / 1000
            val bufferFill = synchronized(this) { outputBuffer.length }
            return buildString {
                appendLine("Session ID    : $id")
                appendLine("CWD           : ${cwd ?: "(default)"}")
                appendLine("Uptime        : ${uptimeSec}s")
                appendLine("Buffer fill   : $bufferFill / $MAX_BUFFER_SIZE chars")
                appendLine("Process alive : ${(process?.isAlive) ?: false}")
            }
        }

        fun stop() {
            try { writer?.close() } catch (_: Exception) {}
            try { process?.destroy() } catch (_: Exception) {}
            jobs.forEach { it.cancel() }
        }
    }

    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    // ─────────────────────────────────────────────────────────────
    // Core Single-Shot Execution (Base64 Injection)
    // ─────────────────────────────────────────────────────────────

    suspend fun executeSingleShot(command: String, cwd: String? = null): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!isTermuxUsable()) {
            val cdCmd = if (!cwd.isNullOrBlank()) "cd ${shellQuote(cwd)} && " else ""
            return@withContext PrivilegedExecutionManager.executeCommand("$cdCmd$command").fold(
                onSuccess = { ToolExecutionResult(smartTruncate(it)) },
                onFailure = { ToolExecutionResult("System shell failed: ${it.message}", isError = true) }
            )
        }

        val scriptContent = buildString {
            appendLine("#!$TERMUX_BASH")
            if (!cwd.isNullOrBlank()) appendLine("cd ${shellQuote(cwd)} || { echo 'Failed to cd into $cwd'; exit 1; }")
            appendLine(command)
        }

        val tmpPath = "/data/local/tmp/omni_exec_${System.currentTimeMillis()}.sh"
        val b64 = Base64.encodeToString(scriptContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        
        val writeCmd = "echo ${shellQuote(b64)} | base64 -d > ${shellQuote(tmpPath)} && chmod +x ${shellQuote(tmpPath)} && echo WRITE_OK"
        val writeResult = PrivilegedExecutionManager.executeCommand(writeCmd)
        
        if (writeResult.isFailure || !writeResult.getOrDefault("").contains("WRITE_OK")) {
            return@withContext ToolExecutionResult(
                "Engine failed to inject script: ${writeResult.exceptionOrNull()?.message}",
                isError = true
            )
        }

        val envPfx = buildEnvPrefix()
        val execCmd = "${envPfx}${TERMUX_BASH} ${shellQuote(tmpPath)} 2>&1; rm -f ${shellQuote(tmpPath)}"
        
        PrivilegedExecutionManager.executeCommand(execCmd).fold(
            onSuccess = { ToolExecutionResult(smartTruncate(it)) },
            onFailure = { ToolExecutionResult("Execution failed: ${it.message}", isError = true) }
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Advanced Runners & Package Managers
    // ─────────────────────────────────────────────────────────────

    suspend fun runPython(code: String, args: String? = null, cwd: String? = null): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val pyBin = findPythonInterpreter()
                ?: return@withContext ToolExecutionResult("Python not found. Install via 'pkg_install' packages='python'.", isError = true)

            val scriptContent = buildString {
                appendLine("#!$TERMUX_BASH")
                if (!cwd.isNullOrBlank()) appendLine("cd ${shellQuote(cwd)} || exit 1")
                val pyB64 = Base64.encodeToString(code.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                appendLine("echo ${shellQuote(pyB64)} | base64 -d > script.py")
                appendLine("$pyBin script.py ${args ?: ""}")
                appendLine("rm -f script.py")
            }
            executeSingleShot(scriptContent, cwd)
        }

    suspend fun npmCommand(command: String, cwd: String? = null): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val npmBin = findBinary("npm")
                ?: return@withContext ToolExecutionResult("NPM not found. Install via 'pkg_install' packages='nodejs'.", isError = true)
            executeSingleShot("$npmBin $command", cwd)
        }

    suspend fun pipInstall(packages: String, upgrade: Boolean = false): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val safePkgs = sanitizePackageList(packages)
                ?: return@withContext ToolExecutionResult("Invalid package names provided.", isError = true)
            val upgradeFlag = if (upgrade) " --upgrade" else ""
            
            // Uses executeSingleShot for reliable multi-command cascade
            val cmd = "pip3 install$upgradeFlag $safePkgs || pip install$upgradeFlag $safePkgs || python3 -m pip install$upgradeFlag $safePkgs"
            executeSingleShot(cmd)
        }

    suspend fun pkgInstall(packages: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        val safePkgs = sanitizePackageList(packages)
            ?: return@withContext ToolExecutionResult("Invalid package names provided.", isError = true)
            
        val cmd = "DEBIAN_FRONTEND=noninteractive pkg install -y $safePkgs || DEBIAN_FRONTEND=noninteractive apt-get install -y $safePkgs"
        executeSingleShot(cmd)
    }

    suspend fun pkgUpdate(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val cmd = "DEBIAN_FRONTEND=noninteractive pkg update -y || DEBIAN_FRONTEND=noninteractive apt-get update -y"
        executeSingleShot(cmd)
    }

    // ─────────────────────────────────────────────────────────────
    // Status Report
    // ─────────────────────────────────────────────────────────────

    suspend fun statusReport(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("╔══ OmniDev CLI Engine Status ══════════════════════════════════╗")

        val shizuku   = PrivilegedExecutionManager.isShizukuReady()
        val rishReady = PrivilegedExecutionManager.isRishReady()
        sb.appendLine("║ BACKEND : ${if (shizuku) "✅ Shizuku" else "❌ Offline"} | ${if (rishReady) "✅ rish" else "❌ No rish"}")
        
        val termuxOk = isTermuxUsable()
        sb.appendLine("║ TERMUX  : ${if (termuxOk) "✅ Ready ($TERMUX_BASH)" else "❌ Not Installed"}")

        sb.appendLine("║ INTERPRETERS:")
        val tools = listOf("python3", "node", "npm", "git", "gcc", "make", "java", "jadx", "apktool")
        for (tool in tools) {
            val path = findBinary(tool)
            sb.appendLine("║   ${if (path != null) "✅" else "❌"} $tool ${if (path != null) "→ $path" else ""}")
        }
        sb.appendLine("╚═══════════════════════════════════════════════════════════════╝")
        ToolExecutionResult(sb.toString().trimEnd())
    }

    // ─────────────────────────────────────────────────────────────
    // Tool Definitions & Routing
    // ─────────────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "termux_bridge",
            description = """
                The Ultimate God-Mode CLI & Persistent Session Engine.
                Run complex commands or manage background servers (Node, Python).
                
                Actions:
                - 'status': Check installed interpreters and shell readiness.
                - 'exec': Run a single-shot command and wait for output.
                - 'session_start': Opens a background terminal. Returns a session_id.
                - 'session_send': Sends a command to a session (requires 'session_id' & 'command').
                - 'session_read': Reads the current output buffer of a session.
                - 'session_stop': Kills a specific session.
                - 'session_list': Lists all active background sessions.
                - 'session_kill_all': Kills all active sessions.
                - 'python_run': Run inline Python code safely.
                - 'npm': Run npm commands (e.g., 'install').
                - 'pip_install' / 'pkg_install' / 'pkg_update': Native package management.
                - 'find_binary': Find location of an executable.
            """.trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string", "Action to perform (exec, session_start, session_send, pip_install, etc.)", required = true),
                ToolParameter("command", "string", "Command to execute (for exec, session_send, npm).", required = false),
                ToolParameter("session_id", "string", "Session ID (required for session_send, read, stop).", required = false),
                ToolParameter("cwd", "string", "Current Working Directory (for exec, session_start).", required = false),
                ToolParameter("code", "string", "Inline Python code.", required = false),
                ToolParameter("packages", "string", "Packages for pip/pkg install (e.g., 'express react' or 'nodejs git').", required = false),
                ToolParameter("args", "string", "Extra arguments for python_run.", required = false),
                ToolParameter("upgrade", "string", "Pass 'true' to upgrade during pip_install.", required = false),
                ToolParameter("binary", "string", "Binary name for 'find_binary'.", required = false)
            )
        )
    )

    suspend fun executeTool(args: Map<String, String>): ToolExecutionResult {
        val action = args["action"]?.lowercase()?.trim() ?: return ToolExecutionResult("Missing 'action'.", isError = true)
        val cwd = args["cwd"]
        val timeoutMs = ((args["timeout_seconds"]?.toLongOrNull() ?: 60L).coerceIn(1L, 600L)) * 1000L

        return when (action) {
            "status" -> statusReport()
            "exec", "script" -> {
                val cmd = args["command"] ?: args["script"] ?: return ToolExecutionResult("Missing 'command' parameter.", isError = true)
                try {
                    withTimeout(timeoutMs) { executeSingleShot(cmd, cwd) }
                } catch (_: TimeoutCancellationException) {
                    ToolExecutionResult("⏱️  Command timed out after ${timeoutMs / 1000}s. Use timeout_seconds= to extend (max 600).", isError = true)
                }
            }
            "session_start" -> {
                val id = "sess_" + UUID.randomUUID().toString().take(6)
                val session = TerminalSession(id, cwd)
                if (session.start()) {
                    sessions[id] = session
                    ToolExecutionResult("✅ Session started. ID: $id\nUse 'session_send' to run commands and 'session_read' to view output.")
                } else {
                    ToolExecutionResult("❌ Failed to start session.", isError = true)
                }
            }
            "session_list" -> {
                if (sessions.isEmpty()) return ToolExecutionResult("No active sessions.")
                val list = sessions.values.joinToString("\n") { "- ID: ${it.id} (cwd: ${it.cwd ?: "default"})" }
                ToolExecutionResult("Active Sessions (${sessions.size}):\n$list")
            }
            "session_send" -> {
                val id = args["session_id"] ?: return ToolExecutionResult("Missing 'session_id'.", isError = true)
                val cmd = args["command"] ?: return ToolExecutionResult("Missing 'command'.", isError = true)
                val session = sessions[id] ?: return ToolExecutionResult("Session '$id' not found.", isError = true)
                session.sendCommand(cmd)
                ToolExecutionResult("Command sent to $id. Call 'session_read' to see the output.")
            }
            "session_read" -> {
                val id = args["session_id"] ?: return ToolExecutionResult("Missing 'session_id'.", isError = true)
                val session = sessions[id] ?: return ToolExecutionResult("Session '$id' not found.", isError = true)
                ToolExecutionResult("Output for $id:\n${session.readOutputAndClear()}")
            }
            "session_peek" -> {
                val id = args["session_id"] ?: return ToolExecutionResult("Missing 'session_id'.", isError = true)
                val session = sessions[id] ?: return ToolExecutionResult("Session '$id' not found.", isError = true)
                ToolExecutionResult("[PEEK — buffer NOT cleared]\n${session.peekOutput()}")
            }
            "session_info" -> {
                val id = args["session_id"] ?: return ToolExecutionResult("Missing 'session_id'.", isError = true)
                val session = sessions[id] ?: return ToolExecutionResult("Session '$id' not found.", isError = true)
                ToolExecutionResult(session.info())
            }
            "session_stop" -> {
                val id = args["session_id"] ?: return ToolExecutionResult("Missing 'session_id'.", isError = true)
                val session = sessions.remove(id) ?: return ToolExecutionResult("Session '$id' not found.", isError = true)
                session.stop()
                ToolExecutionResult("✅ Session '$id' stopped.")
            }
            "session_kill_all" -> {
                val count = sessions.size
                sessions.values.forEach { it.stop() }
                sessions.clear()
                ToolExecutionResult("✅ Killed all $count active sessions.")
            }
            "python_run" -> {
                val code = args["code"] ?: return ToolExecutionResult("Missing 'code'.", isError = true)
                runPython(code, args["args"], cwd)
            }
            "npm" -> {
                val cmd = args["command"] ?: return ToolExecutionResult("Missing 'command'.", isError = true)
                npmCommand(cmd, cwd)
            }
            "pip_install" -> {
                val pkgs = args["packages"] ?: return ToolExecutionResult("Missing 'packages' parameter.", isError = true)
                pipInstall(pkgs, args["upgrade"]?.lowercase() == "true")
            }
            "pkg_install" -> {
                val pkgs = args["packages"] ?: return ToolExecutionResult("Missing 'packages' parameter.", isError = true)
                pkgInstall(pkgs)
            }
            "pkg_update" -> pkgUpdate()
            "find_binary", "which" -> {
                val name = args["binary"] ?: args["command"] ?: return ToolExecutionResult("Missing 'binary' parameter.", isError = true)
                val path = findBinary(name)
                if (path != null) ToolExecutionResult("✅ $name located at: $path")
                else ToolExecutionResult("❌ '$name' not found in PATH.", isError = true)
            }
            "env_info" -> {
                val env = buildTermuxEnv()
                val formatted = buildString {
                    appendLine("═══ Termux Injected Environment ═══")
                    env.entries.sortedBy { it.key }.forEach { (k, v) ->
                        appendLine("  $k = $v")
                    }
                    appendLine("  Termux usable: ${isTermuxUsable()}")
                }
                ToolExecutionResult(formatted)
            }
            else -> ToolExecutionResult("Unknown action: '$action'.", isError = true)
        }
    }
}
