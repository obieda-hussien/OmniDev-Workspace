package com.omnidev.workspace.data.tools

import android.util.Base64
import android.util.Log
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// ═══════════════════════════════════════════════════════════════════════════════
// TERMUX ENVIRONMENT BRIDGE — v4.0  "IRON BRIDGE"
//
//  The single authoritative execution layer between OmniDev and Termux/Android.
//
//  ┌───────────────────────────────────────────────────────────────────────────┐
//  │  Architecture                                                             │
//  │                                                                           │
//  │  TermuxEnvironmentBridge (this file)                                      │
//  │  ├─ EnvBuilder          → Correct LD_PRELOAD + export env block           │
//  │  ├─ BinaryResolver      → Cached binary lookup with TTL                  │
//  │  ├─ SessionEngine       → Multi-window persistent shell sessions         │
//  │  ├─ ExecutionEngine     → Base64-safe single-shot + streaming exec       │
//  │  ├─ PackageManager      → pkg / pip / npm with idempotent install        │
//  │  ├─ BootstrapOrchestrator → Staged, dependency-aware full setup          │
//  │  ├─ DiagnosticsEngine   → Deep LD_PRELOAD / backend smoke tests          │
//  │  └─ ToolRouter          → Agent-facing action dispatcher                 │
//  │                                                                           │
//  │  ROOT CAUSE FIXES (from TermuxExecutionFix analysis):                    │
//  │                                                                           │
//  │  1. LD_PRELOAD fix — Old code set PATH+LD_LIBRARY_PATH but MISSED        │
//  │     LD_PRELOAD=/data/data/com.termux/files/usr/lib/libtermux-exec.so.    │
//  │     Without it, every Termux binary silently exits or crashes from        │
//  │     Shizuku. This is now ALWAYS injected when the .so exists.            │
//  │                                                                           │
//  │  2. export block vs inline prefix — Using KEY=VAL command only applies   │
//  │     env to that one process. export KEY=VAL && command ensures child     │
//  │     processes (subshells, interpreters) also inherit the env.            │
//  │                                                                           │
//  │  3. Base64 script injection — The ONLY reliable way to pass multi-line   │
//  │     scripts with special chars through the Shizuku/rish binder. Every    │
//  │     complex script goes through this path.                               │
//  │                                                                           │
//  │  4. Standalone fallback — When Termux is absent, falls back to            │
//  │     system sh + toybox + busybox for basic file/network ops.             │
//  │                                                                           │
//  │  5. Binary cache with TTL — prevents re-running `which` on every call.  │
//  └───────────────────────────────────────────────────────────────────────────┘
// ═══════════════════════════════════════════════════════════════════════════════

// ─────────────────────────────────────────────────────────────────────────────
// § 1. CONSTANTS
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "TermuxBridgeV4"
private const val MAX_OUTPUT      = 14_000
private const val MAX_BUFFER_SIZE = 24_000

// ─────────────────────────────────────────────────────────────────────────────
// § 3. DIAGNOSTICS DATA
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full snapshot of the current Termux execution readiness.
 * Returned by [TermuxEnvironmentBridge.diagnose].
 */
data class TermuxBridgeStatus(
    val termuxInstalled:   Boolean,
    val preloadSoFound:    Boolean,      // libtermux-exec.so present?
    val backendAvailable:  Boolean,      // Shizuku / rish / root
    val backendName:       String,       // "shizuku" | "rish" | "root" | "none"
    val bashWorks:         Boolean,
    val pythonWorks:       Boolean,
    val nodeWorks:         Boolean,
    val pkgWorks:          Boolean,
    val toyboxPath:        String?,
    val busyboxPath:       String?,
    val issues:            List<String>,
    val probeElapsedMs:    Long = 0L
) {
    val isFullyOperational: Boolean
        get() = termuxInstalled && preloadSoFound && backendAvailable && bashWorks

    val canInstallPackages: Boolean
        get() = isFullyOperational && pkgWorks

    fun summaryLine(): String = buildString {
        append("[${if (isFullyOperational) "READY" else "DEGRADED"}] ")
        append("backend=$backendName ")
        append("termux=${if (termuxInstalled) "✅" else "❌"} ")
        append("ld_preload=${if (preloadSoFound) "✅" else "❌"} ")
        append("python=${if (pythonWorks) "✅" else "❌"}")
        if (issues.isNotEmpty()) append(" issues=${issues.size}")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// § 4. BINARY CACHE
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Thread-safe binary path cache with TTL-based invalidation.
 * Avoids repeated `which` calls during the same session.
 */
private class TermuxBinaryCache(private val ttlMs: Long = 120_000L) {

    private data class Entry(val path: String, val ts: Long)

    private val hits   = ConcurrentHashMap<String, Entry>()
    private val misses = ConcurrentHashMap<String, Long>()

    fun get(name: String): String? {
        val e = hits[name] ?: return null
        if (System.currentTimeMillis() - e.ts > ttlMs) { hits.remove(name); return null }
        return e.path
    }

    fun isMiss(name: String): Boolean {
        val ts = misses[name] ?: return false
        if (System.currentTimeMillis() - ts > ttlMs) { misses.remove(name); return false }
        return true
    }

    fun put(name: String, path: String) {
        misses.remove(name)
        hits[name] = Entry(path, System.currentTimeMillis())
    }

    fun putMiss(name: String) { misses[name] = System.currentTimeMillis() }

    fun invalidate(name: String)  { hits.remove(name); misses.remove(name) }
    fun invalidateAll()           { hits.clear(); misses.clear() }
}

// ─────────────────────────────────────────────────────────────────────────────
// § 5. MAIN OBJECT
// ─────────────────────────────────────────────────────────────────────────────

object TermuxEnvironmentBridge {

    // ── 5.1  Known Paths ──────────────────────────────────────────────────────

    const val TERMUX_PKG          = "com.termux"
    const val TERMUX_PREFIX       = "/data/data/com.termux/files/usr"
    const val TERMUX_HOME         = "/data/data/com.termux/files/home"
    const val TERMUX_BIN          = "$TERMUX_PREFIX/bin"
    const val TERMUX_LIB          = "$TERMUX_PREFIX/lib"
    const val TERMUX_TMP          = "$TERMUX_PREFIX/tmp"
    const val TERMUX_BASH         = "$TERMUX_BIN/bash"
    const val TERMUX_SH           = "$TERMUX_BIN/sh"
    const val TERMUX_PYTHON3      = "$TERMUX_BIN/python3"
    const val TERMUX_PYTHON       = "$TERMUX_BIN/python"
    const val TERMUX_NODE         = "$TERMUX_BIN/node"
    const val TERMUX_NPM          = "$TERMUX_BIN/npm"
    const val TERMUX_PIP3         = "$TERMUX_BIN/pip3"
    const val TERMUX_PIP          = "$TERMUX_BIN/pip"
    const val TERMUX_GIT          = "$TERMUX_BIN/git"
    const val TERMUX_PKG_MANAGER  = "$TERMUX_BIN/pkg"
    const val TERMUX_APT          = "$TERMUX_BIN/apt"

    /**
     * THE critical LD_PRELOAD target.
     * Without this, ALL Termux binaries silently fail when called from Shizuku
     * because the system dynamic linker loads the wrong libc.
     * This was the root cause of "nothing shows even with Shizuku enabled".
     */
    private const val TERMUX_EXEC_SO = "$TERMUX_LIB/libtermux-exec.so"

    /** Fallback static binary store when Termux is absent. */
    private const val STANDALONE_BIN = "/data/local/tmp/omni_bins"

    private val TOYBOX_PATHS = listOf(
        "/system/bin/toybox",
        "/system/xbin/toybox"
    )
    private val BUSYBOX_PATHS = listOf(
        "/data/adb/magisk/busybox",
        "/data/adb/modules/busybox-ndk/bin/busybox",
        "/system/xbin/busybox",
        "/data/local/busybox",
        "/sbin/busybox"
    )
    private val SYSTEM_PYTHON_PATHS = listOf(
        "/system/bin/python3", "/system/bin/python",
        "/system/xbin/python3", "/system/xbin/python",
        "/data/usr/bin/python3", "/data/usr/bin/python"
    )
    private val SYSTEM_NODE_PATHS = listOf(
        "/system/bin/node", "/system/xbin/node", "/data/usr/bin/node"
    )
    private val SYSTEM_GIT_PATHS = listOf(
        "/system/bin/git", "/usr/bin/git", "/usr/local/bin/git"
    )

    // ── 5.2  Internal State ───────────────────────────────────────────────────

    private val binaryCache = TermuxBinaryCache(ttlMs = 120_000L)
    private val probeMutex  = Mutex()
    private val lastProbeMs = AtomicLong(0L)

    @Volatile private var _lastStatus: TermuxBridgeStatus? = null
    val lastStatus: TermuxBridgeStatus? get() = _lastStatus

    private val MIN_PROBE_INTERVAL_MS = 15_000L

    // ── 5.3  Environment Building ─────────────────────────────────────────────

    /**
     * Returns a multi-line `export` block with all Termux env vars, including
     * the critical LD_PRELOAD that makes Termux binaries work from Shizuku.
     *
     * This block must be PREPENDED to every script executed via Shizuku/rish.
     *
     * OLD broken approach:    "KEY=VAL command"       → subshells don't inherit
     * NEW fixed approach:     "export KEY=VAL\ncommand" → everything inherits
     */
    fun buildEnvBlock(): String {
        if (!isTermuxInstalled()) return ""

        val preload = if (File(TERMUX_EXEC_SO).exists()) {
            "export LD_PRELOAD=\"$TERMUX_EXEC_SO\"\n"
        } else {
            Log.w(TAG, "libtermux-exec.so NOT found at $TERMUX_EXEC_SO — " +
                       "Termux binaries may silently fail from Shizuku! " +
                       "Fix: open Termux → pkg install termux-exec")
            ""
        }

        return buildString {
            appendLine("export PREFIX=\"$TERMUX_PREFIX\"")
            appendLine("export HOME=\"$TERMUX_HOME\"")
            appendLine("export TMPDIR=\"$TERMUX_TMP\"")
            appendLine("export PATH=\"$TERMUX_BIN:$TERMUX_PREFIX/sbin:/system/bin:/system/xbin:/sbin:/vendor/bin\"")
            appendLine("export LD_LIBRARY_PATH=\"$TERMUX_LIB:$TERMUX_LIB/termux-exec:/system/lib64:/system/lib\"")
            append(preload)
            appendLine("export TERM=\"xterm-256color\"")
            appendLine("export LANG=\"en_US.UTF-8\"")
            appendLine("export DEBIAN_FRONTEND=\"noninteractive\"")
            appendLine("export ANDROID_ROOT=\"/system\"")
            appendLine("export ANDROID_DATA=\"/data\"")
        }
    }

    /**
     * Single-line inline prefix — still includes LD_PRELOAD.
     * Prefer [buildEnvBlock] for multi-line scripts.
     */
    fun buildInlineEnvPrefix(): String {
        if (!isTermuxInstalled()) return ""
        val preload = if (File(TERMUX_EXEC_SO).exists()) "LD_PRELOAD=\"$TERMUX_EXEC_SO\" " else ""
        return "PREFIX=\"$TERMUX_PREFIX\" HOME=\"$TERMUX_HOME\" TMPDIR=\"$TERMUX_TMP\" " +
               "PATH=\"$TERMUX_BIN:/system/bin\" LD_LIBRARY_PATH=\"$TERMUX_LIB\" " +
               "${preload}LANG=\"en_US.UTF-8\" "
    }

    /** Builds environment as a Map for direct ProcessBuilder injection. */
    fun buildEnvMap(): Map<String, String> {
        val env = mutableMapOf(
            "PREFIX"          to TERMUX_PREFIX,
            "HOME"            to TERMUX_HOME,
            "TMPDIR"          to TERMUX_TMP,
            "TERM"            to "xterm-256color",
            "LANG"            to "en_US.UTF-8",
            "ANDROID_ROOT"    to "/system",
            "ANDROID_DATA"    to "/data",
            "DEBIAN_FRONTEND" to "noninteractive",
            "PATH"            to "$TERMUX_BIN:$TERMUX_PREFIX/sbin:/system/bin:/system/xbin:/sbin",
            "LD_LIBRARY_PATH" to "$TERMUX_LIB:/system/lib64:/system/lib"
        )
        if (File(TERMUX_EXEC_SO).exists()) env["LD_PRELOAD"] = TERMUX_EXEC_SO
        return env
    }

    // ── 5.4  Binary Resolution ────────────────────────────────────────────────

    /** Returns true if Termux bash exists (Termux is installed). */
    fun isTermuxInstalled(): Boolean = File(TERMUX_BASH).exists()

    /**
     * Resolves a binary path with full caching.
     * Search order: cache → Termux bin → `which` via privileged shell → known static paths.
     */
    suspend fun findBinary(name: String): String? = withContext(Dispatchers.IO) {
        binaryCache.get(name)?.let { return@withContext it }
        if (binaryCache.isMiss(name)) return@withContext null

        // 1. Direct Termux path
        val termuxDirect = File(TERMUX_BIN, name)
        if (termuxDirect.exists() && termuxDirect.canExecute()) {
            binaryCache.put(name, termuxDirect.absolutePath)
            return@withContext termuxDirect.absolutePath
        }

        // 2. Privileged shell `which`
        if (isTermuxInstalled()) {
            val envPfx = buildInlineEnvPrefix()
            val result = PrivilegedExecutionManager.executeCommand(
                "${envPfx}which ${sanitizeName(name)} 2>/dev/null || command -v ${sanitizeName(name)} 2>/dev/null"
            ).getOrNull()?.trim()?.takeIf { isValidPath(it) }
            if (result != null) {
                binaryCache.put(name, result)
                return@withContext result
            }
        }

        // 3. System `which` fallback
        val sysResult = PrivilegedExecutionManager.executeCommand(
            "which ${sanitizeName(name)} 2>/dev/null"
        ).getOrNull()?.trim()?.takeIf { isValidPath(it) }
        if (sysResult != null) {
            binaryCache.put(name, sysResult)
            return@withContext sysResult
        }

        // 4. Known static locations
        val static = when (name) {
            "python3", "python" -> SYSTEM_PYTHON_PATHS.firstOrNull { File(it).exists() }
            "node"              -> SYSTEM_NODE_PATHS.firstOrNull   { File(it).exists() }
            "git"               -> SYSTEM_GIT_PATHS.firstOrNull    { File(it).exists() }
            else                -> null
        }
        if (static != null) binaryCache.put(name, static) else binaryCache.putMiss(name)
        static
    }

    internal suspend fun findPythonInterpreter(venvPath: String? = null): String? {
        // Prefer venv
        if (!venvPath.isNullOrBlank()) {
            listOf("$venvPath/bin/python3", "$venvPath/bin/python")
                .firstOrNull { File(it).exists() }?.let { return it }
        }
        // Termux
        listOf(TERMUX_PYTHON3, TERMUX_PYTHON).firstOrNull { File(it).exists() }?.let { return it }
        // Fallback
        return findBinary("python3") ?: findBinary("python")
            ?: SYSTEM_PYTHON_PATHS.firstOrNull { File(it).exists() }
    }

    // ── 5.5  Base64 Injection Engine ─────────────────────────────────────────

    /**
     * The most reliable way to execute multi-line scripts through the Shizuku/rish
     * binder interface, bypassing ALL shell quoting/escaping issues.
     *
     * Process:
     *  1. Base64-encode the full script (no characters need escaping).
     *  2. Pipe the b64 string through `base64 -d` into a temp file.
     *  3. chmod +x the temp file.
     *  4. Execute it with `sh`.
     *  5. Capture exit code.
     *  6. Delete temp file.
     */
    fun buildBase64Command(script: String): String {
        val b64 = Base64.encodeToString(
            script.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        val tmp = "/data/local/tmp/omnidev_${System.currentTimeMillis()}.sh"
        // Use single-quoted b64 — no special chars can appear inside a base64 string
        return "echo '$b64' | base64 -d > $tmp && chmod 755 $tmp && " +
               "sh $tmp 2>&1; __EC=\$?; rm -f $tmp; exit \$__EC"
    }

    /**
     * Determines whether Base64 injection should be used for a given script.
     * True for: multi-line, long, or scripts containing shell metacharacters.
     */
    private fun needsBase64(script: String): Boolean =
        script.length > 200 ||
        script.contains('\n') ||
        script.any { it in "\"$`\\|<>&;{}!~()" }

    // ── 5.6  Core Execution Engine ────────────────────────────────────────────

    /**
     * Execute a single-shot command or multi-line script.
     *
     * Automatically:
     *  - Prepends the Termux env block (with LD_PRELOAD) to fix all Shizuku issues.
     *  - Uses Base64 injection for scripts that contain special characters.
     *  - Falls back to standalone execution (toybox/busybox) if Termux is absent.
     *  - Applies [cwd] change before script execution.
     *
     * @param command   Shell code to execute (single line or multi-line script).
     * @param cwd       Optional working directory (cd executed before script).
     * @param forceBase64 Force Base64 injection even for simple commands.
     * @param fallback  Fall back to standalone execution if Termux env fails.
     * @param timeoutMs Execution timeout in milliseconds (0 = no timeout).
     */
    suspend fun executeSingleShot(
        command:      String,
        cwd:          String? = null,
        forceBase64:  Boolean = false,
        fallback:     Boolean = true,
        timeoutMs:    Long    = 0L
    ): ToolExecutionResult = withContext(Dispatchers.IO) {

        if (command.isBlank()) return@withContext err("Empty command.")

        if (!isTermuxInstalled()) {
            return@withContext if (fallback) {
                executeStandalone(command, cwd)
            } else {
                err("❌ Termux is not installed.\n" +
                    "Install from F-Droid: https://f-droid.org/packages/com.termux/")
            }
        }

        val envBlock = buildEnvBlock()

        val fullScript = buildString {
            append(envBlock)
            if (!cwd.isNullOrBlank()) {
                appendLine("cd ${sq(cwd)} || { echo \"[ERROR] cd failed: $cwd\"; exit 1; }")
            }
            appendLine(command)
        }

        val useB64 = forceBase64 || needsBase64(fullScript)

        val execCmd = if (useB64) {
            buildBase64Command(fullScript)
        } else {
            "${buildInlineEnvPrefix()}sh -c ${sq(fullScript)}"
        }

        val exec = suspend {
            PrivilegedExecutionManager.executeCommand(execCmd).fold(
                onSuccess = { out ->
                    ToolExecutionResult(smartTruncate(out))
                },
                onFailure = { e ->
                    Log.w(TAG, "Termux exec failed: ${e.message}")
                    if (fallback) executeStandalone(command, cwd)
                    else err("❌ Execution failed: ${e.message?.take(1000)}")
                }
            )
        }

        if (timeoutMs > 0L) {
            try {
                withTimeout(timeoutMs) { exec() }
            } catch (_: TimeoutCancellationException) {
                err("⏱️ Command timed out after ${timeoutMs / 1000}s.")
            }
        } else {
            exec()
        }
    }

    /**
     * Streaming execution — emits output lines as they arrive via a [Flow<String>].
     * Uses a [TerminalSession] internally for the live output feed.
     *
     * Useful for long-running commands where you want incremental progress
     * (e.g. build systems, long installations).
     */
    fun executeStreaming(
        command: String,
        cwd:     String? = null,
        scope:   CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    ): Flow<String> = callbackFlow {
        val session = TerminalSession("stream_${UUID.randomUUID().toString().take(6)}", cwd)
        if (!session.start()) {
            trySend("[ERROR] Failed to start streaming session.")
            close()
            return@callbackFlow
        }

        val envScript = buildString {
            append(buildEnvBlock())
            if (!cwd.isNullOrBlank()) appendLine("cd ${sq(cwd)} 2>/dev/null || true")
            appendLine(command)
        }

        session.sendCommand(envScript)

        val pollJob = scope.launch {
            while (isActive) {
                val output = session.peekNewOutput()
                if (output.isNotBlank()) trySend(output)
                delay(100)
            }
        }

        awaitClose {
            pollJob.cancel()
            session.stop()
        }
    }

    /**
     * Execute using only Android built-in tools (no Termux).
     * Uses /system/bin/sh + toybox + busybox.
     */
    private suspend fun executeStandalone(
        command: String,
        cwd:     String?
    ): ToolExecutionResult {
        val busybox = BUSYBOX_PATHS.firstOrNull { File(it).exists() }
        val toybox  = TOYBOX_PATHS.firstOrNull  { File(it).exists() }

        val standaloneEnv = buildString {
            appendLine("export PATH=\"$STANDALONE_BIN:/system/bin:/system/xbin:/sbin\"")
            if (busybox != null) {
                appendLine("for _cmd in wget curl python python3 bash awk sed; do")
                appendLine("  command -v \$_cmd >/dev/null 2>&1 || alias \$_cmd=\"$busybox \$_cmd\" 2>/dev/null || true")
                appendLine("done")
            }
            if (toybox != null) appendLine("# toybox available: $toybox")
        }

        val script = buildString {
            append(standaloneEnv)
            if (!cwd.isNullOrBlank()) appendLine("cd ${sq(cwd)} 2>/dev/null || true")
            appendLine(command)
        }

        return PrivilegedExecutionManager.executeCommand(buildBase64Command(script)).fold(
            onSuccess = { ToolExecutionResult(smartTruncate(it)) },
            onFailure = { e ->
                err("❌ Standalone exec failed: ${e.message?.take(500)}\n" +
                    "Install Termux from F-Droid for full package support.")
            }
        )
    }

    // ── 5.7  Specialised Runners ──────────────────────────────────────────────

    /** Run inline Python code with proper Termux env + venv support. */
    suspend fun runPython(
        code:     String,
        args:     String? = null,
        cwd:      String? = null,
        venvPath: String? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val pyBin = findPythonInterpreter(venvPath)
            ?: return@withContext err("❌ Python not found.\nFix: action=pkg_install packages='python'")

        val argStr = args?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ") { sq(it) }
            ?.let { " $it" } ?: ""

        val script = buildString {
            if (venvPath != null) {
                appendLine(". ${sq("$venvPath/bin/activate")} 2>/dev/null || true")
            }
            if (!cwd.isNullOrBlank()) appendLine("cd ${sq(cwd)} || exit 1")
            appendLine("$pyBin -c ${sq(code)}$argStr 2>&1")
        }
        executeSingleShot(script, forceBase64 = true)
    }

    /** Run an npm command. */
    suspend fun npmCommand(command: String, cwd: String? = null): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val npmBin = findBinary("npm")
                ?: return@withContext err("❌ npm not found. Fix: action=pkg_install packages='nodejs'")
            executeSingleShot("$npmBin $command", cwd)
        }

    /** Install Python packages via pip, with fallback cascade. */
    suspend fun pipInstall(
        packages: String,
        upgrade:  Boolean = false,
        venvPath: String? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val safePkgs = sanitizePackageList(packages)
            ?: return@withContext err("❌ Invalid package names.")

        val pyBin = findPythonInterpreter(venvPath)
            ?: return@withContext err("❌ Python not found. Fix: action=pkg_install packages='python'")

        val upgradeFlag = if (upgrade) " --upgrade" else ""
        val venvActivate = if (venvPath != null)
            ". ${sq("$venvPath/bin/activate")} 2>/dev/null && " else ""

        val script = "${venvActivate}pip3 install$upgradeFlag $safePkgs 2>&1" +
                     " || pip install$upgradeFlag $safePkgs 2>&1" +
                     " || $pyBin -m pip install$upgradeFlag $safePkgs 2>&1"
        executeSingleShot(script, forceBase64 = false)
    }

    /** Install Termux packages via pkg/apt with non-interactive mode. */
    suspend fun pkgInstall(packages: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!isTermuxInstalled()) {
            return@withContext err("❌ Termux not installed.\nhttps://f-droid.org/packages/com.termux/")
        }
        val safePkgs = sanitizePackageList(packages)
            ?: return@withContext err("❌ Invalid package names.")

        val pkgMgr = when {
            File(TERMUX_PKG_MANAGER).exists() -> TERMUX_PKG_MANAGER
            File(TERMUX_APT).exists()         -> TERMUX_APT
            else -> return@withContext err("❌ Termux package manager not found.")
        }

        val script = buildString {
            appendLine("unset TERMUX_APP_PACKAGE_MANAGER 2>/dev/null || true")
            appendLine("$pkgMgr install -y $safePkgs 2>&1")
        }

        Log.i(TAG, "pkg install: $safePkgs")
        executeSingleShot(script, forceBase64 = true).also {
            binaryCache.invalidateAll()  // new binaries may now be available
        }
    }

    /** Update Termux package index. */
    suspend fun pkgUpdate(): ToolExecutionResult = withContext(Dispatchers.IO) {
        executeSingleShot(
            "DEBIAN_FRONTEND=noninteractive pkg update -y 2>&1 || apt-get update -y 2>&1",
            forceBase64 = false
        )
    }

    /** Upgrade all installed Termux packages. */
    suspend fun pkgUpgrade(): ToolExecutionResult = withContext(Dispatchers.IO) {
        executeSingleShot(
            "DEBIAN_FRONTEND=noninteractive pkg upgrade -y 2>&1 || apt-get upgrade -y 2>&1",
            forceBase64 = false
        )
    }

    // ── 5.8  Persistent Session Engine ───────────────────────────────────────

    /**
     * A persistent interactive shell session backed by a live [Process].
     *
     * Supports:
     *  - Sending commands at any time ([sendCommand]).
     *  - Reading accumulated output ([readOutputAndClear]).
     *  - Peeking without consuming ([peekOutput]).
     *  - Incremental output polling ([peekNewOutput]).
     *  - Session info/status ([info]).
     *  - Graceful termination ([stop]).
     */
    class TerminalSession(val id: String, val cwd: String?) {

        private var process:   Process?        = null
        private var writer:    BufferedWriter?  = null
        private val buffer     = StringBuffer()
        private val lastReadPos = AtomicLong(0L)
        private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val startedAt  = System.currentTimeMillis()
        private val isAlive    = AtomicBoolean(false)

        fun start(): Boolean = try {
            val shell = if (isTermuxInstalled()) TERMUX_BASH else "/system/bin/sh"
            val pb = ProcessBuilder(shell).redirectErrorStream(true)
            pb.environment().putAll(buildEnvMap())

            if (!cwd.isNullOrBlank()) {
                val dir = File(cwd)
                if (dir.exists() && dir.isDirectory) pb.directory(dir)
            }

            process = pb.start()
            writer  = BufferedWriter(OutputStreamWriter(process!!.outputStream))
            val reader = BufferedReader(InputStreamReader(process!!.inputStream))
            isAlive.set(true)

            scope.launch {
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        appendToBuffer(line!! + "\n")
                    }
                } catch (_: Exception) {}
                appendToBuffer("\n[Process Terminated at ${Date()}]\n")
                isAlive.set(false)
            }

            appendToBuffer("Session [$id] started: $shell\n")
            Log.i(TAG, "Session [$id] started (cwd=$cwd)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Session [$id] start failed: ${e.message}")
            appendToBuffer("[ERROR] Failed to start: ${e.message}\n")
            false
        }

        @Synchronized
        private fun appendToBuffer(text: String) {
            buffer.append(text)
            if (buffer.length > MAX_BUFFER_SIZE) {
                buffer.delete(0, buffer.length - MAX_BUFFER_SIZE)
                buffer.insert(0, "[... BUFFER TRUNCATED ...]\n")
            }
        }

        fun sendCommand(cmd: String) {
            try {
                appendToBuffer("$ $cmd\n")
                writer?.write("$cmd\n")
                writer?.flush()
            } catch (e: Exception) {
                appendToBuffer("[ERROR] sendCommand failed: ${e.message}\n")
            }
        }

        @Synchronized
        fun readOutputAndClear(): String {
            val out = buffer.toString()
            buffer.delete(0, buffer.length)
            lastReadPos.set(0L)
            return out.ifBlank { "(No new output)" }
        }

        @Synchronized
        fun peekOutput(): String = buffer.toString().ifBlank { "(No output yet)" }

        /** Returns only new output since last call to this function. Non-destructive. */
        @Synchronized
        fun peekNewOutput(): String {
            val pos = lastReadPos.get().toInt().coerceAtLeast(0)
            val current = buffer.toString()
            if (pos >= current.length) return ""
            val newContent = current.substring(pos)
            lastReadPos.set(current.length.toLong())
            return newContent
        }

        fun isProcessAlive(): Boolean = isAlive.get() && try {
            process?.exitValue(); false
        } catch (_: IllegalThreadStateException) { true }

        fun info(): String {
            val uptimeSec  = (System.currentTimeMillis() - startedAt) / 1000
            val bufferFill = synchronized(this) { buffer.length }
            return buildString {
                appendLine("Session ID    : $id")
                appendLine("CWD           : ${cwd ?: "(default)"}")
                appendLine("Uptime        : ${uptimeSec}s")
                appendLine("Buffer fill   : $bufferFill / $MAX_BUFFER_SIZE chars")
                appendLine("Process alive : ${if (isProcessAlive()) "✅ yes" else "❌ no"}")
            }
        }

        fun stop() {
            isAlive.set(false)
            try { writer?.write("exit 0\n"); writer?.flush() } catch (_: Exception) {}
            try { writer?.close() } catch (_: Exception) {}
            try { process?.destroy() } catch (_: Exception) {}
            scope.cancel()
            Log.i(TAG, "Session [$id] stopped.")
        }
    }

    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    // ── 5.9  Diagnostics Engine ───────────────────────────────────────────────

    /**
     * Deep diagnostic smoke test.
     *
     * Checks:
     *  1. Termux installation
     *  2. libtermux-exec.so (the LD_PRELOAD fix)
     *  3. Privilege backend (Shizuku/rish/root)
     *  4. bash execution
     *  5. Python execution
     *  6. Node.js execution
     *  7. pkg/apt package manager
     *  8. toybox/busybox standalone availability
     */
    suspend fun diagnose(force: Boolean = false): TermuxBridgeStatus = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastProbeMs.get() < MIN_PROBE_INTERVAL_MS && _lastStatus != null) {
            return@withContext _lastStatus!!
        }

        probeMutex.withLock {
            val now2 = System.currentTimeMillis()
            if (!force && now2 - lastProbeMs.get() < MIN_PROBE_INTERVAL_MS && _lastStatus != null) {
                return@withLock _lastStatus!!
            }

            val start     = System.currentTimeMillis()
            val installed = isTermuxInstalled()
            val preload   = File(TERMUX_EXEC_SO).exists()

            val backendName = when {
                PrivilegedExecutionManager.isShizukuReady()  -> "shizuku"
                PrivilegedExecutionManager.isRishReady()     -> "rish"
                PrivilegedExecutionManager.isRootAvailable() -> "root"
                else                                          -> "none"
            }
            val backendOk = backendName != "none"

            val bashPath   = if (installed) TERMUX_BASH else null
            val pythonPath = if (installed)
                listOf(TERMUX_PYTHON3, TERMUX_PYTHON).firstOrNull { File(it).exists() }
                else null
            val nodePath = if (installed && File(TERMUX_NODE).exists()) TERMUX_NODE else null

            // Run smoke tests only if we have a working backend
            val bashOk = bashPath != null && backendOk && smokeTest(
                buildString {
                    append(buildEnvBlock())
                    appendLine("$bashPath -c 'echo BASH_OK' 2>&1")
                },
                "BASH_OK"
            )

            val pythonOk = pythonPath != null && backendOk && smokeTest(
                buildString {
                    append(buildEnvBlock())
                    appendLine("$pythonPath -c 'print(\"PYTHON_OK\")' 2>&1")
                },
                "PYTHON_OK"
            )

            val nodeOk = nodePath != null && backendOk && smokeTest(
                buildString {
                    append(buildEnvBlock())
                    appendLine("$nodePath -e 'console.log(\"NODE_OK\")' 2>&1")
                },
                "NODE_OK"
            )

            val pkgOk = installed && backendOk && bashOk && run {
                val pkgBin = when {
                    File(TERMUX_PKG_MANAGER).exists() -> TERMUX_PKG_MANAGER
                    File(TERMUX_APT).exists()         -> TERMUX_APT
                    else -> null
                }
                pkgBin != null && smokeTest(
                    buildString {
                        append(buildEnvBlock())
                        appendLine("$pkgBin list 2>&1 | head -3")
                    },
                    null  // just check for no crash
                )
            }

            val toybox  = TOYBOX_PATHS.firstOrNull  { File(it).exists() }
            val busybox = BUSYBOX_PATHS.firstOrNull { File(it).exists() }

            val issues = buildList {
                if (!backendOk)  add("No privilege backend — Shizuku/rish/root required")
                if (!installed)  add("Termux not installed")
                if (installed && !preload)   add("libtermux-exec.so missing → Termux binaries crash from Shizuku. Fix: pkg install termux-exec")
                if (installed && !bashOk)    add("bash not working despite env fix — try updating Termux")
                if (installed && !pythonOk)  add("Python not working. Fix: pkg install python")
                if (installed && bashOk && !pythonPath.isNullOrEmpty() && !pythonOk)
                    add("Python binary found but not working — may need reinstall")
            }

            val status = TermuxBridgeStatus(
                termuxInstalled   = installed,
                preloadSoFound    = preload,
                backendAvailable  = backendOk,
                backendName       = backendName,
                bashWorks         = bashOk,
                pythonWorks       = pythonOk,
                nodeWorks         = nodeOk,
                pkgWorks          = pkgOk,
                toyboxPath        = toybox,
                busyboxPath       = busybox,
                issues            = issues,
                probeElapsedMs    = System.currentTimeMillis() - start
            )

            _lastStatus = status
            lastProbeMs.set(System.currentTimeMillis())
            Log.i(TAG, "Diagnose complete: ${status.summaryLine()}")
            status
        }
    }

    private suspend fun smokeTest(script: String, expectedToken: String?): Boolean = try {
        val r = PrivilegedExecutionManager.executeCommand(buildBase64Command(script))
        val out = r.getOrNull() ?: return false
        expectedToken == null || out.contains(expectedToken)
    } catch (_: Exception) { false }

    /** Full diagnostic report as a formatted string. */
    suspend fun fullDiagnosticReport(): ToolExecutionResult {
        val s = diagnose(force = true)
        return ToolExecutionResult(buildString {
            appendLine("╔══ OmniDev TermuxBridge v4.0 Diagnostics ═══════════════════════╗")
            appendLine("║")
            appendLine("║  Termux installed    : ${yn(s.termuxInstalled)}")
            appendLine("║  libtermux-exec.so   : ${yn(s.preloadSoFound)}  ← THE critical LD_PRELOAD fix")
            appendLine("║  Privilege backend   : ${yn(s.backendAvailable)} (${s.backendName})")
            appendLine("║  bash works          : ${yn(s.bashWorks)}")
            appendLine("║  Python works        : ${yn(s.pythonWorks)}")
            appendLine("║  Node.js works       : ${yn(s.nodeWorks)}")
            appendLine("║  pkg/apt works       : ${yn(s.pkgWorks)}")
            appendLine("║  toybox              : ${s.toyboxPath ?: "❌ not found"}")
            appendLine("║  busybox             : ${s.busyboxPath ?: "❌ not found"}")
            appendLine("║  Probe time          : ${s.probeElapsedMs}ms")
            appendLine("║")
            if (s.issues.isNotEmpty()) {
                appendLine("║  ISSUES DETECTED:")
                s.issues.forEach { appendLine("║  ⚠️  $it") }
                appendLine("║")
                appendLine("║  RECOMMENDED FIXES:")
                buildFixSuggestions(s).forEach { appendLine("║  → $it") }
            } else {
                appendLine("║  ✅ All systems operational! Full Termux execution available.")
            }
            appendLine("╚════════════════════════════════════════════════════════════════╝")
        }.trimEnd())
    }

    // ── 5.10  Bootstrap Orchestrator ─────────────────────────────────────────

    /**
     * A single idempotent installation step in a bootstrap plan.
     */
    data class BootstrapStep(
        val id:          String,
        val description: String,
        val dependsOn:   List<String> = emptyList(),
        val checkFn:     suspend () -> Boolean,
        val installFn:   suspend () -> BootstrapStepResult
    )

    sealed class BootstrapStepResult {
        data class Success(val message: String)    : BootstrapStepResult()
        data class AlreadyDone(val message: String) : BootstrapStepResult()
        data class Failed(val reason: String, val hint: String = "") : BootstrapStepResult()
        data class Skipped(val reason: String)     : BootstrapStepResult()
    }

    /**
     * Builds the standard bootstrap plan: Termux → pkg update → Python → Node → Git → curl/wget → pip.
     * Each step checks its own precondition before acting (idempotent).
     */
    fun buildStandardBootstrapPlan(): List<BootstrapStep> = listOf(

        BootstrapStep(
            id          = "termux_check",
            description = "Verify Termux is installed",
            checkFn     = { isTermuxInstalled() },
            installFn   = {
                BootstrapStepResult.Failed(
                    reason = "Termux is not installed.",
                    hint   = "Install from F-Droid: https://f-droid.org/packages/com.termux/"
                )
            }
        ),

        BootstrapStep(
            id          = "termux_exec",
            description = "Ensure libtermux-exec.so is present (LD_PRELOAD fix)",
            dependsOn   = listOf("termux_check"),
            checkFn     = { File(TERMUX_EXEC_SO).exists() },
            installFn   = {
                val r = executeSingleShot("pkg install -y termux-exec 2>&1")
                if (File(TERMUX_EXEC_SO).exists())
                    BootstrapStepResult.Success("termux-exec installed — LD_PRELOAD fixed.")
                else
                    BootstrapStepResult.Failed("termux-exec install failed.", r.output.take(300))
            }
        ),

        BootstrapStep(
            id          = "pkg_update",
            description = "Update Termux package index",
            dependsOn   = listOf("termux_check"),
            checkFn     = { false },  // always run once per bootstrap
            installFn   = {
                val r = pkgUpdate()
                if (!r.isError) BootstrapStepResult.Success("Package index updated.")
                else BootstrapStepResult.Failed("pkg update failed.", r.output.take(300))
            }
        ),

        BootstrapStep(
            id          = "python",
            description = "Install Python 3 via Termux",
            dependsOn   = listOf("pkg_update"),
            checkFn     = { File(TERMUX_PYTHON3).exists() || File(TERMUX_PYTHON).exists() },
            installFn   = {
                val r = pkgInstall("python")
                val installed = File(TERMUX_PYTHON3).exists() || File(TERMUX_PYTHON).exists()
                if (installed) BootstrapStepResult.Success("Python installed.")
                else BootstrapStepResult.Failed("Python install failed.", r.output.take(300))
            }
        ),

        BootstrapStep(
            id          = "node",
            description = "Install Node.js via Termux",
            dependsOn   = listOf("pkg_update"),
            checkFn     = { File(TERMUX_NODE).exists() },
            installFn   = {
                val r = pkgInstall("nodejs")
                if (File(TERMUX_NODE).exists()) BootstrapStepResult.Success("Node.js installed.")
                else BootstrapStepResult.Failed("Node install failed.", r.output.take(300))
            }
        ),

        BootstrapStep(
            id          = "git",
            description = "Install Git via Termux",
            dependsOn   = listOf("pkg_update"),
            checkFn     = { File(TERMUX_GIT).exists() },
            installFn   = {
                val r = pkgInstall("git")
                if (File(TERMUX_GIT).exists()) BootstrapStepResult.Success("Git installed.")
                else BootstrapStepResult.Failed("Git install failed.", r.output.take(300))
            }
        ),

        BootstrapStep(
            id          = "curl_wget",
            description = "Ensure curl and wget are available",
            dependsOn   = listOf("pkg_update"),
            checkFn     = { File("$TERMUX_BIN/curl").exists() || File("$TERMUX_BIN/wget").exists() },
            installFn   = {
                val r = pkgInstall("curl wget")
                if (!r.isError) BootstrapStepResult.Success("curl/wget installed.")
                else BootstrapStepResult.Failed("curl/wget failed.", r.output.take(300))
            }
        ),

        BootstrapStep(
            id          = "pip",
            description = "Bootstrap pip for Python",
            dependsOn   = listOf("python"),
            checkFn     = { File(TERMUX_PIP3).exists() || File("$TERMUX_BIN/pip").exists() },
            installFn   = {
                val pyBin = if (File(TERMUX_PYTHON3).exists()) TERMUX_PYTHON3 else TERMUX_PYTHON
                val r = executeSingleShot("$pyBin -m ensurepip --upgrade 2>&1")
                if (!r.isError) BootstrapStepResult.Success("pip bootstrapped.")
                else BootstrapStepResult.Failed("pip bootstrap failed.", r.output.take(300))
            }
        )
    )

    /**
     * Execute a bootstrap plan with optional step-level progress callbacks.
     * Each step is idempotent — safe to call multiple times.
     */
    suspend fun runBootstrap(
        plan:       List<BootstrapStep>,
        onProgress: ((stepId: String, result: BootstrapStepResult) -> Unit)? = null
    ): List<Pair<String, BootstrapStepResult>> = withContext(Dispatchers.IO) {
        val results   = mutableListOf<Pair<String, BootstrapStepResult>>()
        val completed = mutableSetOf<String>()

        for (step in plan) {
            val unmet = step.dependsOn.filter { it !in completed }
            if (unmet.isNotEmpty()) {
                val r = BootstrapStepResult.Skipped("Depends on unfinished: $unmet")
                results += step.id to r
                onProgress?.invoke(step.id, r)
                continue
            }

            Log.i(TAG, "Bootstrap [${step.id}]: ${step.description}")
            val result = try {
                if (step.checkFn()) BootstrapStepResult.AlreadyDone("Already installed.")
                else step.installFn()
            } catch (e: Exception) {
                BootstrapStepResult.Failed("Exception: ${e.message}")
            }

            results += step.id to result
            onProgress?.invoke(step.id, result)

            if (result is BootstrapStepResult.Success || result is BootstrapStepResult.AlreadyDone) {
                completed += step.id
                binaryCache.invalidate(step.id)
            }
        }
        binaryCache.invalidateAll()
        results
    }

    // ── 5.11  Status Report ────────────────────────────────────────────────────

    suspend fun statusReport(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val s = diagnose()
        val sb = StringBuilder()
        sb.appendLine("╔══ OmniDev CLI Engine Status ══════════════════════════════════╗")
        sb.appendLine("║ BACKEND : ${if (s.backendAvailable) "✅ ${s.backendName}" else "❌ None"}")
        sb.appendLine("║ TERMUX  : ${if (s.termuxInstalled) "✅ Ready" else "❌ Not Installed"}")
        sb.appendLine("║ LD_PRELOAD fix: ${if (s.preloadSoFound) "✅ libtermux-exec.so found" else "❌ MISSING — binaries may fail!"}")
        sb.appendLine("║")
        sb.appendLine("║ RUNTIMES:")
        val runtimes = listOf("python3", "node", "npm", "git", "gcc", "make", "java", "curl", "wget")
        for (tool in runtimes) {
            val path = findBinary(tool)
            sb.appendLine("║   ${if (path != null) "✅" else "❌"} $tool ${if (path != null) "→ $path" else ""}")
        }
        sb.appendLine("╚═══════════════════════════════════════════════════════════════╝")
        ToolExecutionResult(sb.toString().trimEnd())
    }

    // ── 5.12  Tool Definitions ────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name        = "termux_bridge",
            description = """
OmniDev TermuxBridge v4.0 — God-Mode CLI & Persistent Session Engine.
Backed by Shizuku/rish/root + Termux. Includes LD_PRELOAD fix for all binary issues.

━━ ENVIRONMENT ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
• status          — Full engine status (interpreters, backend, LD_PRELOAD).
• diagnose        — Deep diagnostic with smoke tests and fix suggestions.
• env_info        — Dump the injected environment variables.
• bootstrap       — Full staged bootstrap (Termux → Python → Node → Git → pip).
• bootstrap_status — Show completion state of each bootstrap step.
• ensure_tool     — Pre-flight dependency check + dynamic provisioning (Termux first, standalone fallback).

━━ CODE EXECUTION ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
• exec            — Run a single-shot command (param: command, optional: cwd, timeout_seconds).
• python_run      — Run inline Python (param: code, optional: args, cwd, venv_path).
• npm             — Run npm command (param: command, optional: cwd).

━━ PACKAGE MANAGEMENT ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
• pip_install     — pip install packages (param: packages, optional: upgrade, venv_path).
• pkg_install     — Termux pkg install (param: packages).
• pkg_update      — Update Termux package index.
• pkg_upgrade     — Upgrade all installed Termux packages.

━━ PERSISTENT SESSIONS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
• session_start   — Open a background terminal. Returns session_id.
• session_list    — List all active sessions.
• session_send    — Send a command to a session (param: session_id, command).
• session_read    — Read + clear output buffer (param: session_id).
• session_peek    — Read buffer WITHOUT clearing (param: session_id).
• session_info    — Show session uptime, cwd, buffer fill, liveness (param: session_id).
• session_stop    — Kill a specific session (param: session_id).
• session_kill_all — Kill all active sessions.

━━ BINARY LOOKUP ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
• find_binary / which — Locate a binary (param: binary or command).
• cache_invalidate     — Force re-scan of binary cache.
            """.trimIndent(),
            parameters  = listOf(
                ToolParameter("action",          "string", "Action to perform.",                    required = true),
                ToolParameter("command",         "string", "Shell command or npm sub-command.",     required = false),
                ToolParameter("session_id",      "string", "Session ID for session_* actions.",     required = false),
                ToolParameter("cwd",             "string", "Working directory.",                    required = false),
                ToolParameter("code",            "string", "Inline Python code.",                   required = false),
                ToolParameter("packages",        "string", "Package list for pip/pkg install.",     required = false),
                ToolParameter("tool",            "string", "Tool to verify/provision (e.g., apktool, jadx, python).", required = false),
                ToolParameter("language",        "string", "Optional runtime hint: python, node, java.", required = false),
                ToolParameter("termux_package",  "string", "Optional explicit Termux package for ensure_tool.", required = false),
                ToolParameter("pip_package",     "string", "Optional pip package for ensure_tool.", required = false),
                ToolParameter("npm_package",     "string", "Optional npm package for ensure_tool.", required = false),
                ToolParameter("fallback_url",    "string", "Optional direct HTTPS URL for standalone fallback.", required = false),
                ToolParameter("java_class",      "string", "Optional Java main class for jar fallback.", required = false),
                ToolParameter("verify_command",  "string", "Optional command to verify tool health.", required = false),
                ToolParameter("args",            "string", "Extra args for python_run.",            required = false),
                ToolParameter("upgrade",         "string", "'true' to pip install --upgrade.",      required = false),
                ToolParameter("venv_path",       "string", "Absolute path to a Python venv.",       required = false),
                ToolParameter("binary",          "string", "Binary name for find_binary/which.",    required = false),
                ToolParameter("timeout_seconds", "string", "Exec timeout in seconds (max 600).",    required = false)
            )
        )
    )

    // ── 5.13  Tool Router ──────────────────────────────────────────────────────

    suspend fun executeTool(args: Map<String, String>): ToolExecutionResult {
        val action    = args["action"]?.lowercase()?.trim()
            ?: return err("Missing 'action' parameter.")
        val cwd       = args["cwd"]
        val timeoutMs = (args["timeout_seconds"]?.toLongOrNull() ?: 60L).coerceIn(1L, 600L) * 1_000L

        return when (action) {

            // ── Environment ────────────────────────────────────────────────────
            "status"           -> statusReport()
            "diagnose"         -> fullDiagnosticReport()
            "env_info"         -> envInfo()
            "bootstrap"        -> runBootstrapAction()
            "bootstrap_status" -> bootstrapStatusAction()
            "ensure_tool", "provision_tool", "auto_provision" -> ensureToolAction(args)
            "cache_invalidate" -> {
                binaryCache.invalidateAll()
                ToolExecutionResult("✅ Binary cache invalidated. Next lookup will re-scan.")
            }

            // ── Execution ──────────────────────────────────────────────────────
            "exec", "script" -> {
                val cmd = args["command"] ?: args["script"]
                    ?: return err("Missing 'command' parameter.")
                executeSingleShot(cmd, cwd, timeoutMs = timeoutMs)
            }

            // ── Python ─────────────────────────────────────────────────────────
            "python_run" -> {
                val code = args["code"] ?: return err("Missing 'code' parameter.")
                runPython(code, args["args"], cwd, args["venv_path"])
            }

            // ── npm ────────────────────────────────────────────────────────────
            "npm" -> {
                val cmd = args["command"] ?: return err("Missing 'command' parameter.")
                npmCommand(cmd, cwd)
            }

            // ── Packages ───────────────────────────────────────────────────────
            "pip_install" -> {
                val pkgs = args["packages"] ?: return err("Missing 'packages'.")
                pipInstall(pkgs, args["upgrade"]?.lowercase() == "true", args["venv_path"])
            }
            "pkg_install" -> {
                val pkgs = args["packages"] ?: return err("Missing 'packages'.")
                pkgInstall(pkgs)
            }
            "pkg_update"  -> pkgUpdate()
            "pkg_upgrade" -> pkgUpgrade()

            // ── Sessions ───────────────────────────────────────────────────────
            "session_start" -> {
                val id      = "sess_${UUID.randomUUID().toString().take(6)}"
                val session = TerminalSession(id, cwd)
                if (session.start()) {
                    sessions[id] = session
                    ToolExecutionResult("✅ Session started. ID: $id\n" +
                        "Use 'session_send' to run commands, 'session_read' to view output.")
                } else {
                    err("❌ Failed to start session.")
                }
            }

            "session_list" -> {
                if (sessions.isEmpty()) return ToolExecutionResult("No active sessions.")
                val list = sessions.values.joinToString("\n") {
                    "  • ${it.id} | cwd=${it.cwd ?: "default"} | alive=${it.isProcessAlive()}"
                }
                ToolExecutionResult("Active sessions (${sessions.size}):\n$list")
            }

            "session_send" -> {
                val id  = args["session_id"] ?: return err("Missing 'session_id'.")
                val cmd = args["command"]    ?: return err("Missing 'command'.")
                val s   = sessions[id]       ?: return err("Session '$id' not found.")
                s.sendCommand(cmd)
                ToolExecutionResult("✅ Command sent to $id. Call 'session_read' to see output.")
            }

            "session_read" -> {
                val id = args["session_id"] ?: return err("Missing 'session_id'.")
                val s  = sessions[id]       ?: return err("Session '$id' not found.")
                ToolExecutionResult("Output for [$id]:\n${s.readOutputAndClear()}")
            }

            "session_peek" -> {
                val id = args["session_id"] ?: return err("Missing 'session_id'.")
                val s  = sessions[id]       ?: return err("Session '$id' not found.")
                ToolExecutionResult("[PEEK — buffer NOT cleared]\n${s.peekOutput()}")
            }

            "session_info" -> {
                val id = args["session_id"] ?: return err("Missing 'session_id'.")
                val s  = sessions[id]       ?: return err("Session '$id' not found.")
                ToolExecutionResult(s.info())
            }

            "session_stop" -> {
                val id = args["session_id"] ?: return err("Missing 'session_id'.")
                val s  = sessions.remove(id) ?: return err("Session '$id' not found.")
                s.stop()
                ToolExecutionResult("✅ Session '$id' stopped.")
            }

            "session_kill_all" -> {
                val count = sessions.size
                sessions.values.forEach { it.stop() }
                sessions.clear()
                ToolExecutionResult("✅ Killed all $count active session(s).")
            }

            // ── Binary Lookup ──────────────────────────────────────────────────
            "find_binary", "which" -> {
                val name = args["binary"] ?: args["command"]
                    ?: return err("Missing 'binary' parameter.")
                val path = findBinary(sanitizeName(name))
                if (path != null) ToolExecutionResult("✅ $name → $path")
                else err("❌ '$name' not found in PATH.")
            }

            else -> err("Unknown action: '$action'. See tool description for all available actions.")
        }
    }

    // ── 5.14  Private Helpers ─────────────────────────────────────────────────

    private data class StandaloneJavaSpec(
        val url: String,
        val fileName: String,
        val mainClass: String
    )

    private suspend fun ensureToolAction(args: Map<String, String>): ToolExecutionResult {
        val toolRaw = args["tool"] ?: args["binary"] ?: args["command"]
        val tool = toolRaw?.lowercase()?.trim()?.let(::sanitizeName).orEmpty()
        if (tool.isBlank()) return err("Missing 'tool' parameter for ensure_tool.")

        val language = args["language"]?.trim()?.lowercase()
        val verifyCommand = args["verify_command"]?.trim().orEmpty()

        findBinary(tool)?.let { existing ->
            val verify = if (verifyCommand.isNotBlank()) executeSingleShot(verifyCommand) else executeSingleShot("$existing --version 2>/dev/null || true")
            return ToolExecutionResult(
                buildString {
                    appendLine("✅ '$tool' already available at: $existing")
                    if (!verify.isError && verify.output.isNotBlank()) {
                        appendLine("Verification:")
                        appendLine(verify.output.take(300))
                    }
                }.trimEnd()
            )
        }

        val pipPackage = args["pip_package"]?.trim().orEmpty()
        val npmPackage = args["npm_package"]?.trim().orEmpty()
        val explicitTermuxPackage = args["termux_package"]?.trim()?.let { sanitizePackageList(it) }.orEmpty()
        val installLog = StringBuilder()

        if (isTermuxInstalled()) {
            installLog.appendLine("Termux detected → provisioning with package managers.")
            val defaultPkg = defaultTermuxPackageFor(tool, language)
            val pkg = explicitTermuxPackage.ifBlank { defaultPkg.orEmpty() }
            if (pkg.isNotBlank()) installLog.appendLine(pkgInstall(pkg).output.take(500))

            if ((language == "python" || pipPackage.isNotBlank()) && findBinary("python").isNullOrBlank()) {
                installLog.appendLine(pkgInstall("python").output.take(300))
            }
            if (pipPackage.isNotBlank()) installLog.appendLine(pipInstall(pipPackage).output.take(500))

            if ((language == "node" || npmPackage.isNotBlank()) && findBinary("node").isNullOrBlank()) {
                installLog.appendLine(pkgInstall("nodejs").output.take(300))
            }
            if (npmPackage.isNotBlank()) {
                val safeNpm = sanitizePackageList(npmPackage) ?: return err("Invalid npm_package value.")
                installLog.appendLine(npmCommand("install -g $safeNpm").output.take(500))
            }

            val resolved = findBinary(tool)
            if (resolved != null) {
                val verify = if (verifyCommand.isNotBlank()) executeSingleShot(verifyCommand) else executeSingleShot("$resolved --version 2>/dev/null || true")
                return ToolExecutionResult(
                    buildString {
                        appendLine("✅ Provisioned '$tool' via Termux: $resolved")
                        if (installLog.isNotBlank()) {
                            appendLine()
                            appendLine("Install summary:")
                            appendLine(installLog.toString().trim())
                        }
                        if (!verify.isError && verify.output.isNotBlank()) {
                            appendLine()
                            appendLine("Verification:")
                            appendLine(verify.output.take(300))
                        }
                    }.trimEnd()
                )
            }
            installLog.appendLine("Termux provisioning did not expose '$tool' in PATH.")
        } else {
            installLog.appendLine("Termux missing → using standalone Android fallback in /data/local/tmp.")
        }

        val spec = defaultStandaloneJavaSpecFor(tool)
        val fallbackUrl = args["fallback_url"]?.trim().orEmpty()
        val jarClassArg = args["java_class"]?.trim().orEmpty()
        val standaloneUrl = when {
            fallbackUrl.isNotBlank() -> fallbackUrl
            spec != null -> spec.url
            else -> ""
        }
        if (standaloneUrl.isBlank() || !standaloneUrl.startsWith("https://")) {
            return ToolExecutionResult(
                buildString {
                    appendLine("❌ '$tool' missing and standalone provisioning needs a secure HTTPS URL.")
                    appendLine("Use ensure_tool with fallback_url=https://... (and java_class=... for jars).")
                    if (installLog.isNotBlank()) {
                        appendLine()
                        appendLine("Attempt summary:")
                        appendLine(installLog.toString().trim())
                    }
                }.trimEnd(),
                isError = true
            )
        }

        val targetDir = "/data/local/tmp/omni_bins"
        val targetFile = when {
            spec != null && fallbackUrl.isBlank() -> "$targetDir/${spec.fileName}"
            standaloneUrl.endsWith(".jar") -> "$targetDir/$tool.jar"
            else -> "$targetDir/$tool"
        }

        val dl = executeSingleShot(
            "mkdir -p ${sq(targetDir)} && " +
                "if command -v curl >/dev/null 2>&1; then curl -fsSL ${sq(standaloneUrl)} -o ${sq(targetFile)}; " +
                "elif command -v wget >/dev/null 2>&1; then wget -qO ${sq(targetFile)} ${sq(standaloneUrl)}; " +
                "else echo 'Missing both curl and wget'; exit 1; fi",
            timeoutMs = 180_000L
        )
        if (dl.isError) return err("❌ Standalone download failed for '$tool'.\n${dl.output.take(1200)}")

        if (!targetFile.endsWith(".jar")) {
            executeSingleShot("chmod +x ${sq(targetFile)}")
            binaryCache.put(tool, targetFile)
            val verify = if (verifyCommand.isNotBlank()) executeSingleShot(verifyCommand) else executeSingleShot("${sq(targetFile)} --version 2>/dev/null || true")
            return ToolExecutionResult(
                buildString {
                    appendLine("✅ Standalone tool provisioned: $targetFile")
                    appendLine("Use absolute path or export PATH=\"$targetDir:\$PATH\".")
                    if (!verify.isError && verify.output.isNotBlank()) {
                        appendLine()
                        appendLine("Verification:")
                        appendLine(verify.output.take(300))
                    }
                }.trimEnd()
            )
        }

        val dalvik = findBinary("dalvikvm")
        if (dalvik.isNullOrBlank()) {
            return err("❌ Downloaded jar for '$tool' to $targetFile but dalvikvm is unavailable.")
        }

        val mainClass = jarClassArg.ifBlank { spec?.mainClass.orEmpty() }
        if (mainClass.isBlank()) {
            binaryCache.put(tool, targetFile)
            return ToolExecutionResult("✅ Jar downloaded to $targetFile. Provide java_class to execute via dalvikvm.")
        }

        val launcherPath = "$targetDir/$tool"
        val launcher = executeSingleShot(
            "cat > ${sq(launcherPath)} <<'EOF'\n" +
                "#!/system/bin/sh\n" +
                "exec ${sq(dalvik)} -cp ${sq(targetFile)} $mainClass \"\$@\"\n" +
                "EOF\n" +
                "chmod +x ${sq(launcherPath)}",
            timeoutMs = 60_000L
        )
        if (launcher.isError) return err("❌ Downloaded jar but failed to create launcher at $launcherPath.\n${launcher.output.take(800)}")

        binaryCache.put(tool, launcherPath)
        val verify = if (verifyCommand.isNotBlank()) executeSingleShot(verifyCommand) else executeSingleShot("${sq(launcherPath)} --version 2>/dev/null || true")
        return ToolExecutionResult(
            buildString {
                appendLine("✅ Provisioned '$tool' as standalone launcher: $launcherPath")
                appendLine("Jar: $targetFile")
                appendLine("Execution engine: dalvikvm ($dalvik)")
                appendLine("Use absolute path or export PATH=\"$targetDir:\$PATH\".")
                if (!verify.isError && verify.output.isNotBlank()) {
                    appendLine()
                    appendLine("Verification:")
                    appendLine(verify.output.take(300))
                }
            }.trimEnd()
        )
    }

    private fun defaultTermuxPackageFor(tool: String, language: String?): String? = when {
        tool == "python" || language == "python" -> "python"
        tool == "node" || tool == "npm" || language == "node" -> "nodejs"
        tool == "git" -> "git"
        tool == "curl" -> "curl"
        tool == "wget" -> "wget"
        tool == "apktool" -> "apktool"
        tool == "jadx" -> "jadx"
        tool == "aapt" || tool == "aapt2" -> "aapt"
        else -> null
    }

    private fun defaultStandaloneJavaSpecFor(tool: String): StandaloneJavaSpec? = when (tool) {
        "apktool" -> StandaloneJavaSpec(
            url = "https://bitbucket.org/iBotPeaches/apktool/downloads/apktool_2.9.3.jar",
            fileName = "apktool.jar",
            mainClass = "brut.apktool.Main"
        )
        "jadx" -> StandaloneJavaSpec(
            url = "https://github.com/skylot/jadx/releases/download/v1.5.0/jadx-1.5.0-all.jar",
            fileName = "jadx.jar",
            mainClass = "jadx.cli.JadxCLI"
        )
        else -> null
    }

    private suspend fun envInfo(): ToolExecutionResult {
        val env = buildEnvMap()
        return ToolExecutionResult(buildString {
            appendLine("══ Termux Injected Environment ══")
            env.entries.sortedBy { it.key }.forEach { (k, v) ->
                appendLine("  $k = $v")
            }
            appendLine("  Termux usable   : ${isTermuxInstalled()}")
            appendLine("  LD_PRELOAD .so  : ${if (File(TERMUX_EXEC_SO).exists()) "✅ found" else "❌ MISSING"}")
        }.trimEnd())
    }

    private suspend fun runBootstrapAction(): ToolExecutionResult {
        val plan = buildStandardBootstrapPlan()
        val sb   = StringBuilder("=== OmniDev Bootstrap ===\n\n")
        var failures = 0

        runBootstrap(plan) { stepId, result ->
            val icon = when (result) {
                is BootstrapStepResult.Success    -> "✅"
                is BootstrapStepResult.AlreadyDone -> "✔️"
                is BootstrapStepResult.Skipped    -> "⏭️"
                is BootstrapStepResult.Failed     -> { failures++; "❌" }
            }
            val msg = when (result) {
                is BootstrapStepResult.Success     -> result.message
                is BootstrapStepResult.AlreadyDone -> result.message
                is BootstrapStepResult.Skipped     -> result.reason
                is BootstrapStepResult.Failed      ->
                    "${result.reason}${if (result.hint.isNotBlank()) "\n   Hint: ${result.hint}" else ""}"
            }
            sb.appendLine("$icon [$stepId] $msg")
        }

        sb.appendLine()
        sb.appendLine(if (failures == 0) "✅ Bootstrap complete!" else "⚠️ Bootstrap finished with $failures failure(s).")
        return ToolExecutionResult(sb.toString().trimEnd(), isError = failures > 0)
    }

    private suspend fun bootstrapStatusAction(): ToolExecutionResult {
        val plan = buildStandardBootstrapPlan()
        val sb   = StringBuilder("Bootstrap Plan Status:\n\n")
        plan.forEach { step ->
            val done = try { step.checkFn() } catch (_: Exception) { false }
            sb.appendLine("${if (done) "✅" else "❌"} [${step.id}] ${step.description}")
        }
        return ToolExecutionResult(sb.toString().trimEnd())
    }

    private fun buildFixSuggestions(s: TermuxBridgeStatus): List<String> = buildList {
        if (!s.backendAvailable) {
            add("Install Shizuku from Play Store and start it")
            add("OR enable ADB wireless debugging and use rish")
        }
        if (!s.termuxInstalled) {
            add("Install Termux from F-Droid (NOT Play Store)")
            add("https://f-droid.org/packages/com.termux/")
        }
        if (s.termuxInstalled && !s.preloadSoFound) {
            add("Open Termux app and run: pkg install termux-exec")
            add("This installs libtermux-exec.so — REQUIRED for Shizuku execution")
            add("OR use action=bootstrap to do this automatically")
        }
        if (s.termuxInstalled && s.preloadSoFound && !s.pythonWorks) {
            add("action=pkg_install packages='python'")
        }
        if (s.termuxInstalled && s.preloadSoFound && !s.nodeWorks) {
            add("action=pkg_install packages='nodejs'")
        }
    }

    private fun smartTruncate(output: String): String {
        val cleaned = output.trim()
        if (cleaned.length <= MAX_OUTPUT) return cleaned.ifBlank { "(no output)" }
        return "...[TRUNCATED ${cleaned.length - MAX_OUTPUT} chars]...\n" + cleaned.takeLast(MAX_OUTPUT)
    }

    private fun sanitizePackageList(packages: String): String? {
        val safe = packages.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-Z0-9_.\\-\\[\\]=~<>!+@]"), "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return safe.ifBlank { null }
    }

    private fun sanitizeName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_.\\-]"), "")

    private fun isValidPath(s: String): Boolean =
        s.isNotBlank() && s != "(no output)" && !s.startsWith("ERROR") && s.startsWith("/")

    private fun yn(b: Boolean) = if (b) "✅" else "❌"
    private fun sq(s: String)  = "'${s.replace("'", "'\\''")}'"
    private fun err(msg: String) = ToolExecutionResult(msg, isError = true)

    // ── 5.15  Backward Compatibility Aliases ──────────────────────────────────

    /**
     * Backward-compatible alias for [buildEnvBlock].
     * Old callers that used `buildEnvPrefix()` continue to compile.
     * For new code, prefer [buildEnvBlock] (returns full export block)
     * or [buildInlineEnvPrefix] (returns single-line inline prefix).
     */
    fun buildEnvPrefix(): String = buildEnvBlock()

    /**
     * Backward-compatible alias for [isTermuxInstalled].
     * Old callers that used `isTermuxUsable()` continue to compile.
     */
    fun isTermuxUsable(): Boolean = isTermuxInstalled()
}
