package com.omnidev.workspace.data.tools

import android.content.Context
import android.util.Log
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import com.omnidev.workspace.data.tools.security.OmniNativeToolsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// ═══════════════════════════════════════════════════════════════════════════════
// ENVIRONMENT SETUP MANAGER — v3.0
//
// A full-lifecycle environment orchestrator for the OmniDev agent runtime.
//
// Architecture:
//   ┌──────────────────────────────────────────────────────────┐
//   │  SetupStateMachine  (UNINITIALIZED → PROBING → READY)   │
//   │       ↕ emits SetupState via StateFlow                  │
//   │  BinaryCache        (path resolution with TTL)          │
//   │  RuntimeProbe       (per-runtime health + version)      │
//   │  BootstrapOrchestrator (staged, dependency-aware)       │
//   │  EnvVarRegistry     (named env profiles per runtime)    │
//   └──────────────────────────────────────────────────────────┘
//
// Key improvements over the old TermuxEnvironmentBridge pattern:
//  • State machine instead of ad-hoc flag checks → the agent always knows
//    exactly what's available before attempting execution.
//  • Binary path cache with configurable TTL prevents repeated `which` calls.
//  • Staged bootstrap is idempotent — safe to call repeatedly.
//  • RuntimeProbe reports version strings, so the agent can select the
//    correct interpreter for a given task.
//  • EnvVarRegistry builds the correct LD_LIBRARY_PATH / PREFIX / PATH
//    prefix string for each runtime (Termux, system, venv).
// ═══════════════════════════════════════════════════════════════════════════════

// ── State Machine ────────────────────────────────────────────────────────────

/** Phase of the environment setup state machine. */
enum class SetupPhase {
    /** No probe has been run yet — initial state on cold start. */
    UNINITIALIZED,
    /** Actively checking installed runtimes and privilege backend. */
    PROBING,
    /** All required runtimes found and privilege backend is live. */
    READY,
    /**
     * Core runtime (Termux) missing or privilege backend unavailable.
     * Some tools may still work in degraded mode.
     */
    DEGRADED,
    /** Critical error — cannot execute any commands. */
    FAILED
}

/**
 * Availability of a single runtime component (Python, Node, Git, etc.).
 * [version] is the first line of `<binary> --version` output, or null if absent.
 */
data class RuntimeStatus(
    val name:      String,
    val available: Boolean,
    val path:      String?  = null,
    val version:   String?  = null,
    val source:    String?  = null   // "termux" | "system" | "venv"
)

/**
 * Immutable snapshot of the complete environment state.
 * Emitted by [EnvironmentSetupManager.stateFlow] whenever anything changes.
 */
data class SetupState(
    val phase:        SetupPhase                = SetupPhase.UNINITIALIZED,
    val runtimes:     Map<String, RuntimeStatus> = emptyMap(),
    val privilegeBackend: String                = "none",   // "shizuku"|"rish"|"root"|"none"
    val termuxPrefix: String?                   = null,
    val probeTimeMs:  Long                      = 0L,
    val error:        String?                   = null
) {
    val isReady:    Boolean get() = phase == SetupPhase.READY
    val isDegraded: Boolean get() = phase == SetupPhase.DEGRADED

    /** Convenience: returns a [RuntimeStatus] for [name], or a placeholder. */
    fun runtime(name: String) = runtimes[name]
        ?: RuntimeStatus(name, available = false)

    fun summaryLine(): String = buildString {
        append("[${phase.name}] ")
        append("backend=$privilegeBackend ")
        val available = runtimes.values.count { it.available }
        append("runtimes=$available/${runtimes.size}")
        if (error != null) append(" ⚠️ $error")
    }
}

// ── Bootstrap Plan ────────────────────────────────────────────────────────────

/** A single idempotent installation step in the bootstrap plan. */
data class BootstrapStep(
    val id:           String,
    val description:  String,
    val dependsOn:    List<String> = emptyList(),
    val checkFn:      suspend () -> Boolean,
    val installFn:    suspend () -> BootstrapResult
)

/** Result of executing one [BootstrapStep]. */
sealed class BootstrapResult {
    data class Success(val message: String) : BootstrapResult()
    data class AlreadyDone(val message: String) : BootstrapResult()
    data class Failed(val reason: String, val hint: String = "") : BootstrapResult()
    data class Skipped(val reason: String) : BootstrapResult()
}

// ── Binary Cache ──────────────────────────────────────────────────────────────

/** Thread-safe binary path cache with TTL-based invalidation. */
private class BinaryCache(private val ttlMs: Long = 120_000L) {

    data class Entry(val path: String, val timestamp: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val misses = ConcurrentHashMap<String, Long>()  // cache negative results too

    fun get(name: String): String? {
        val entry = cache[name] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
            cache.remove(name)
            return null
        }
        return entry.path
    }

    fun isCachedMiss(name: String): Boolean {
        val ts = misses[name] ?: return false
        if (System.currentTimeMillis() - ts > ttlMs) { misses.remove(name); return false }
        return true
    }

    fun put(name: String, path: String) {
        misses.remove(name)
        cache[name] = Entry(path, System.currentTimeMillis())
    }

    fun putMiss(name: String) { misses[name] = System.currentTimeMillis() }

    fun invalidate(name: String) { cache.remove(name); misses.remove(name) }
    fun invalidateAll() { cache.clear(); misses.clear() }

    fun snapshot(): Map<String, String> = cache
        .filter { (_, v) -> System.currentTimeMillis() - v.timestamp <= ttlMs }
        .mapValues { (_, v) -> v.path }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MAIN OBJECT
// ═══════════════════════════════════════════════════════════════════════════════

object EnvironmentSetupManager {

    private const val TAG = "EnvSetupMgr"

    // ── Constants ──────────────────────────────────────────────────────────

    const val TERMUX_ROOT    = "/data/data/com.termux/files"
    const val TERMUX_PREFIX  = "$TERMUX_ROOT/usr"
    const val TERMUX_HOME    = "$TERMUX_ROOT/home"
    const val TERMUX_BIN     = "$TERMUX_PREFIX/bin"
    const val TERMUX_BASH    = "$TERMUX_BIN/bash"
    const val TERMUX_SH      = "$TERMUX_BIN/sh"
    const val TERMUX_PYTHON3 = "$TERMUX_BIN/python3"
    const val TERMUX_PYTHON  = "$TERMUX_BIN/python"
    const val TERMUX_NODE    = "$TERMUX_BIN/node"
    const val TERMUX_GIT     = "$TERMUX_BIN/git"
    const val TERMUX_PKG     = "$TERMUX_BIN/pkg"
    const val TERMUX_APT     = "$TERMUX_BIN/apt"
    const val TERMUX_PIP3    = "$TERMUX_BIN/pip3"
    const val TERMUX_NPM     = "$TERMUX_BIN/npm"

    /** Fallback paths checked when Termux and `which` both fail. */
    private val SYSTEM_PYTHON_PATHS = listOf(
        "/system/bin/python3", "/system/bin/python",
        "/system/xbin/python3", "/system/xbin/python",
        "/data/usr/bin/python3", "/data/usr/bin/python"
    )
    private val SYSTEM_NODE_PATHS = listOf(
        "/system/bin/node", "/system/xbin/node",
        "/data/usr/bin/node"
    )
    private val SYSTEM_GIT_PATHS = listOf(
        "/system/bin/git", "/usr/bin/git", "/usr/local/bin/git"
    )

    // ── State ──────────────────────────────────────────────────────────────

    private val _stateFlow = MutableStateFlow(SetupState())
    val stateFlow: StateFlow<SetupState> = _stateFlow.asStateFlow()

    val currentState: SetupState get() = _stateFlow.value

    private val cache  = BinaryCache(ttlMs = 120_000L)
    private val mutex  = Mutex()
    private var lastProbeMs = AtomicLong(0L)
    @Volatile
    private var appContext: Context? = null

    /** Minimum milliseconds between full probes (avoids hammer on rapid calls). */
    private const val MIN_PROBE_INTERVAL_MS = 15_000L

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ── PATH VALIDATION HELPER ─────────────────────────────────────────────────

    /** Phrases that appear in Shizuku PartialSuccess garbage output — never valid paths */
    private val BINARY_PATH_GARBAGE_PHRASES = setOf(
        "Execution without output",
        "sh: syntax error",
        "sh: inaccessible",
        "not found",
        "not accessible",
        "no such file",
        "permission denied",
        "error",
        "(exit="
    )

    /**
     * Returns true if [path] looks like a real absolute binary path.
     *
     * A valid binary path:
     *  - Starts with '/'
     *  - Contains no whitespace
     *  - Does not contain any Shizuku error phrases
     *  - Is at most 512 chars
     */
    private fun isValidBinaryPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        if (path.length > 512) return false
        if (!path.startsWith("/")) return false
        if (path.contains(" ") || path.contains("\n") || path.contains("\t")) return false
        val lower = path.lowercase()
        return BINARY_PATH_GARBAGE_PHRASES.none { lower.contains(it) }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Run a full environment probe and update [stateFlow].
     *
     * - Detects privilege backend (Shizuku / rish / root).
     * - Resolves paths for all known runtimes.
     * - Reads version strings.
     * - Transitions to [SetupPhase.READY], [SetupPhase.DEGRADED], or [SetupPhase.FAILED].
     *
     * Throttled: won't re-probe more often than [MIN_PROBE_INTERVAL_MS].
     * Pass [force]=true to bypass the throttle.
     */
    suspend fun probe(force: Boolean = false): SetupState = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastProbeMs.get() < MIN_PROBE_INTERVAL_MS &&
            currentState.phase != SetupPhase.UNINITIALIZED) {
            return@withContext currentState
        }

        mutex.withLock {
            // Double-check inside lock
            val now2 = System.currentTimeMillis()
            if (!force && now2 - lastProbeMs.get() < MIN_PROBE_INTERVAL_MS &&
                currentState.phase != SetupPhase.UNINITIALIZED) {
                return@withLock currentState
            }

            _stateFlow.value = currentState.copy(phase = SetupPhase.PROBING)
            val start = System.currentTimeMillis()
            Log.i(TAG, "Starting environment probe...")

            try {
                // 1. Privilege backend
                val backend = detectPrivilegeBackend()
                Log.i(TAG, "Privilege backend: $backend")

                // 2. Termux prefix
                val termuxPrefix = if (File(TERMUX_BASH).exists()) TERMUX_PREFIX else null

                // 3. Runtime detection (all parallel)
                val runtimes = mutableMapOf<String, RuntimeStatus>()
                coroutineScope {
                    val jobs = listOf(
                        async { probeRuntime("python",   ::findPython) },
                        async { probeRuntime("python3",  ::findPython3) },
                        async { probeRuntime("node",     ::findNode) },
                        async { probeRuntime("npm",      ::findNpm) },
                        async { probeRuntime("git",      ::findGit) },
                        async { probeRuntime("curl",     ::findCurl) },
                        async { probeRuntime("wget",     ::findWget) },
                        async { probeRuntime("bash",     ::findBash) },
                        async { probeRuntime("busybox",  ::findBusybox) },
                        async { probeRuntime("termux",   ::findTermux) },
                        async { probeRuntime("ruby",     ::findRuby) },
                        async { probeRuntime("perl",     ::findPerl) }
                    )
                    jobs.forEach { runtimes[it.await().name] = it.await() }
                }

                // 4. Phase determination
                val phase = when {
                    backend == "none" ->
                        SetupPhase.FAILED
                    !runtimes["termux"]!!.available && !runtimes["bash"]!!.available ->
                        SetupPhase.DEGRADED
                    else ->
                        SetupPhase.READY
                }

                val elapsed = System.currentTimeMillis() - start
                val state = SetupState(
                    phase            = phase,
                    runtimes         = runtimes,
                    privilegeBackend = backend,
                    termuxPrefix     = termuxPrefix,
                    probeTimeMs      = elapsed,
                    error            = if (phase == SetupPhase.FAILED)
                        "No privilege backend — install Shizuku or enable root." else null
                )
                lastProbeMs.set(System.currentTimeMillis())
                _stateFlow.value = state
                Log.i(TAG, "Probe complete in ${elapsed}ms: ${state.summaryLine()}")
                state

            } catch (e: Exception) {
                Log.e(TAG, "Probe failed with exception", e)
                val state = SetupState(
                    phase = SetupPhase.FAILED,
                    error = "Probe exception: ${e.message}"
                )
                _stateFlow.value = state
                state
            }
        }
    }

    /**
     * Resolve the path of [binary], using the cache if available.
     * Search order: BinaryCache → Termux bin → ToolDownloaderEngine dir → known paths → `which`.
     * Returns null if not found. Validates all paths against garbage-output heuristics.
     */
    suspend fun resolveBinary(binary: String): String? = withContext(Dispatchers.IO) {
        val safeBinary = sanitizeName(binary).ifBlank { return@withContext null }

        // 1. Cache hit — but only if it's a valid path (guard against stale garbage)
        val cached = cache.get(safeBinary)
        if (cached != null) {
            return@withContext if (isValidBinaryPath(cached)) cached else {
                cache.invalidate(safeBinary)  // Evict garbage
                null
            }
        }
        if (cache.isCachedMiss(safeBinary)) return@withContext null

        // 2. Direct Termux path check (fast, no Shizuku needed)
        val termuxPath = "$TERMUX_BIN/$safeBinary"
        if (File(termuxPath).exists()) {
            cache.put(safeBinary, termuxPath)
            return@withContext termuxPath
        }

        // 3. Check ToolDownloaderEngine install dir first (our custom-installed tools)
        val omniToolPath = "${ToolDownloaderEngine.INSTALL_DIR}/$safeBinary"
        val omniVerify = PrivilegedExecutionManager.executeCommand(
            "test -f '$omniToolPath' && test -x '$omniToolPath' && echo OK"
        )
        if (omniVerify.getOrDefault("").contains("OK")) {
            cache.put(safeBinary, omniToolPath)
            return@withContext omniToolPath
        }

        // 4. System binary: check known static paths first (File.exists() works for /system)
        val knownStaticPaths = when (safeBinary) {
            "python3", "python" -> SYSTEM_PYTHON_PATHS
            "node"              -> SYSTEM_NODE_PATHS
            "git"               -> SYSTEM_GIT_PATHS
            else                -> emptyList()
        }
        val staticPath = knownStaticPaths.firstOrNull { File(it).exists() }
        if (staticPath != null) {
            cache.put(safeBinary, staticPath)
            return@withContext staticPath
        }

        // 5. `which` via Shizuku — with strict path validation
        val whichResult = PrivilegedExecutionManager
            .executeCommand("which '${safeBinary}' 2>/dev/null")
            .getOrNull()
            ?.trim()

        if (isValidBinaryPath(whichResult)) {
            // Extra check: verify the file actually exists via Shizuku
            val existsCheck = PrivilegedExecutionManager.executeCommand(
                "test -f '$whichResult' && echo EXISTS"
            )
            if (existsCheck.getOrDefault("").contains("EXISTS")) {
                cache.put(safeBinary, whichResult!!)
                return@withContext whichResult
            }
        }

        // 6. App-private Omni tools path (native + custom)
        appContext?.let { ctx ->
            val omniRoot = File(ctx.filesDir, "omnidev_tools")
            val privateCandidates = listOf(
                File(omniRoot, "custom/bin/$safeBinary"),
                File(omniRoot, "bin/$safeBinary"),
                File(omniRoot, "python/bin/$safeBinary")
            )
            privateCandidates.firstOrNull { it.exists() && it.isFile }?.let { hit ->
                cache.put(safeBinary, hit.absolutePath)
                return@withContext hit.absolutePath
            }
        }

        // 7. Not found
        cache.putMiss(safeBinary)
        null
    }

    /**
     * Build the environment variable prefix string for a command that needs to
     * run within the Termux environment (correct LD_LIBRARY_PATH, PREFIX, HOME, PATH).
     *
     * If Termux is not installed, returns an empty string.
     *
     * IMPORTANT — includes LD_PRELOAD=$TERMUX_PREFIX/lib/libtermux-exec.so.
     * Without this, ALL Termux binaries silently fail when called from Shizuku
     * because the system linker loads the wrong libc. This was the root cause
     * of "nothing shows even with Shizuku enabled".
     *
     * Returns an `export` block (newline-terminated) safe for prepending to
     * any script executed via [executeShell]. Not suitable as an inline prefix
     * for single-command strings — use [buildInlineEnvPrefix] for that.
     */
    fun buildEnvPrefix(): String {
        if (!File(TERMUX_BASH).exists()) return ""
        return TermuxExecutionFix.buildTermuxEnvBlock()
    }

    /**
     * Single-line inline env prefix for simple commands.
     * Still includes LD_PRELOAD. Use [buildEnvPrefix] (the export block)
     * for scripts with multiple lines or subshells.
     */
    fun buildInlineEnvPrefix(): String {
        if (!File(TERMUX_BASH).exists()) return ""
        return TermuxExecutionFix.buildInlineEnvPrefix()
    }

    /** Returns true if Termux is installed and functional. */
    fun isTermuxUsable(): Boolean = File(TERMUX_BASH).exists()

    /**
     * Runs a staged bootstrap plan.
     *
     * Each [BootstrapStep] is checked for completion before attempting installation.
     * Steps whose [dependsOn] are not yet complete are skipped with a diagnostic.
     *
     * @return A list of (stepId, result) pairs in execution order.
     */
    suspend fun runBootstrap(
        plan: List<BootstrapStep>,
        onProgress: ((stepId: String, result: BootstrapResult) -> Unit)? = null
    ): List<Pair<String, BootstrapResult>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Pair<String, BootstrapResult>>()
        val completed = mutableSetOf<String>()

        for (step in plan) {
            // Dependency check
            val unmet = step.dependsOn.filter { it !in completed }
            if (unmet.isNotEmpty()) {
                val r = BootstrapResult.Skipped("Depends on unfinished steps: $unmet")
                results += step.id to r
                onProgress?.invoke(step.id, r)
                continue
            }

            Log.i(TAG, "Bootstrap [${step.id}]: ${step.description}")
            val result = try {
                if (step.checkFn()) {
                    BootstrapResult.AlreadyDone("Already installed.")
                } else {
                    step.installFn()
                }
            } catch (e: Exception) {
                BootstrapResult.Failed("Exception: ${e.message}")
            }

            results += step.id to result
            onProgress?.invoke(step.id, result)
            Log.i(TAG, "Bootstrap [${step.id}] → $result")

            if (result is BootstrapResult.Success || result is BootstrapResult.AlreadyDone) {
                completed += step.id
                cache.invalidate(step.id)
            }
        }

        // Re-probe after bootstrap to update state
        probe(force = true)
        results
    }

    /**
     * Returns the standard bootstrap plan for a full Termux + Python + Node + Git setup.
     * Safe to call on any device — each step checks if already done before acting.
     */
    fun buildStandardBootstrapPlan(): List<BootstrapStep> = listOf(

        BootstrapStep(
            id          = "termux",
            description = "Verify Termux is installed",
            checkFn     = { File(TERMUX_BASH).exists() },
            installFn   = {
                BootstrapResult.Failed(
                    reason = "Termux is not installed.",
                    hint   = "Install Termux from F-Droid: https://f-droid.org/packages/com.termux/"
                )
            }
        ),

        BootstrapStep(
            id          = "termux_update",
            description = "Update Termux package index",
            dependsOn   = listOf("termux"),
            checkFn     = { false },   // Always run update once per bootstrap
            installFn   = {
                val env = buildEnvPrefix()
                val r = PrivilegedExecutionManager.executeCommand(
                    "${env}DEBIAN_FRONTEND=noninteractive $TERMUX_APT update -y 2>&1"
                )
                if (r.isSuccess) BootstrapResult.Success("Package index updated.")
                else BootstrapResult.Failed("apt update failed: ${r.exceptionOrNull()?.message}")
            }
        ),

        BootstrapStep(
            id          = "python",
            description = "Install Python 3 via Termux",
            dependsOn   = listOf("termux_update"),
            checkFn     = { File(TERMUX_PYTHON3).exists() || File(TERMUX_PYTHON).exists() },
            installFn   = {
                val env = buildEnvPrefix()
                val r = PrivilegedExecutionManager.executeCommand(
                    "${env}DEBIAN_FRONTEND=noninteractive $TERMUX_PKG install -y python 2>&1"
                )
                val installed = File(TERMUX_PYTHON3).exists() || File(TERMUX_PYTHON).exists()
                if (r.isSuccess && installed) BootstrapResult.Success("Python installed.")
                else BootstrapResult.Failed(
                    "Python install failed.",
                    hint = r.getOrDefault("").take(500)
                )
            }
        ),

        BootstrapStep(
            id          = "node",
            description = "Install Node.js via Termux",
            dependsOn   = listOf("termux_update"),
            checkFn     = { File(TERMUX_NODE).exists() },
            installFn   = {
                val env = buildEnvPrefix()
                val r = PrivilegedExecutionManager.executeCommand(
                    "${env}DEBIAN_FRONTEND=noninteractive $TERMUX_PKG install -y nodejs 2>&1"
                )
                if (r.isSuccess && File(TERMUX_NODE).exists()) BootstrapResult.Success("Node.js installed.")
                else BootstrapResult.Failed("Node install failed.", hint = r.getOrDefault("").take(500))
            }
        ),

        BootstrapStep(
            id          = "git",
            description = "Install Git via Termux",
            dependsOn   = listOf("termux_update"),
            checkFn     = { File(TERMUX_GIT).exists() },
            installFn   = {
                val env = buildEnvPrefix()
                val r = PrivilegedExecutionManager.executeCommand(
                    "${env}DEBIAN_FRONTEND=noninteractive $TERMUX_PKG install -y git 2>&1"
                )
                if (r.isSuccess && File(TERMUX_GIT).exists()) BootstrapResult.Success("Git installed.")
                else BootstrapResult.Failed("Git install failed.", hint = r.getOrDefault("").take(500))
            }
        ),

        BootstrapStep(
            id          = "curl_wget",
            description = "Ensure curl and wget are available",
            dependsOn   = listOf("termux_update"),
            checkFn     = { File("$TERMUX_BIN/curl").exists() || File("$TERMUX_BIN/wget").exists() },
            installFn   = {
                val env = buildEnvPrefix()
                val r = PrivilegedExecutionManager.executeCommand(
                    "${env}DEBIAN_FRONTEND=noninteractive $TERMUX_PKG install -y curl wget 2>&1"
                )
                if (r.isSuccess) BootstrapResult.Success("curl/wget installed.")
                else BootstrapResult.Failed("curl/wget install failed.")
            }
        ),

        BootstrapStep(
            id          = "pip_bootstrap",
            description = "Bootstrap pip for Python",
            dependsOn   = listOf("python"),
            checkFn     = { File(TERMUX_PIP3).exists() || File("$TERMUX_BIN/pip").exists() },
            installFn   = {
                val pyPath = if (File(TERMUX_PYTHON3).exists()) TERMUX_PYTHON3 else TERMUX_PYTHON
                val env = buildEnvPrefix()
                val r = PrivilegedExecutionManager.executeCommand(
                    "${env}${pyPath} -m ensurepip --upgrade 2>&1"
                )
                if (r.isSuccess) BootstrapResult.Success("pip bootstrapped.")
                else BootstrapResult.Failed("pip bootstrap failed.", r.getOrDefault("").take(300))
            }
        )
    )

    /**
     * Run a one-shot command with full Termux environment injection.
     * Equivalent to the old `TermuxEnvironmentBridge.executeSingleShot`.
     *
     * @param script   Shell script content (may be multi-line).
     * @param cwd      Optional working directory.
     * @param useBase64 When true, writes the script to a temp file via Base64 injection
     *                  to bypass all shell quoting issues. Default: auto (true for scripts
     *                  longer than 200 chars or containing special chars).
     */
    suspend fun executeShell(
        script:    String,
        cwd:       String?  = null,
        useBase64: Boolean? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {

        if (script.isBlank()) return@withContext err("Empty script.")

        val needsBase64 = useBase64 ?: (
            script.length > 200 ||
            script.contains('\n') ||
            script.any { it in "\"$`\\|<>&;{}!~" }
        )

        val fullScript = buildString {
            if (!cwd.isNullOrBlank()) appendLine("cd ${shellQuote(cwd)} || { echo \"cd failed: $cwd\"; exit 1; }")
            appendLine(script)
        }

        val envPrefix = buildEnvPrefix()

        val cmd = if (needsBase64) {
            buildBase64InjectionCommand(fullScript, envPrefix)
        } else {
            "${envPrefix}sh -c ${shellQuote(fullScript)}"
        }

        val result = PrivilegedExecutionManager.executeCommand(cmd)
        result.fold(
            onSuccess = { output ->
                ToolExecutionResult(output.trim().take(MAX_OUTPUT).ifBlank { "(no output)" })
            },
            onFailure = { e ->
                ToolExecutionResult("❌ ${e.message?.take(2000)}", isError = true)
            }
        )
    }

    /**
     * Execute Python [code] inline. Handles interpreter resolution and env injection.
     * Delegates to [executeShell] with proper wrapping.
     */
    suspend fun runPython(
        code:       String,
        extraArgs:  String? = null,
        cwd:        String? = null,
        venvPath:   String? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {

        val (pyBin, envPrefix) = resolveInterpreterAndEnv(venvPath)
            ?: return@withContext ToolExecutionResult(
                "❌ Python not found. Run action=install_python or action=bootstrap.", isError = true
            )

        val argStr = extraArgs?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ") { shellQuote(it) }
            ?.let { " $it" } ?: ""

        val script = buildString {
            if (!cwd.isNullOrBlank()) appendLine("cd ${shellQuote(cwd)} || exit 1")
            appendLine("${envPrefix}${pyBin} -c ${shellQuote(code)}$argStr")
        }
        executeShell(script, useBase64 = true)
    }

    /**
     * Install packages via Termux `pkg`.
     * Equivalent to old `TermuxEnvironmentBridge.pkgInstall`.
     */
    suspend fun pkgInstall(packages: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!File(TERMUX_PKG).exists() && !File(TERMUX_APT).exists()) {
            return@withContext ToolExecutionResult(
                "❌ Termux package manager not found. Is Termux installed?", isError = true
            )
        }
        val safePkgs = sanitizePackageList(packages)
            ?: return@withContext err("Invalid package list.")
        val env = buildEnvPrefix()
        val pkgMgr = if (File(TERMUX_PKG).exists()) TERMUX_PKG else TERMUX_APT
        val cmd = "${env}DEBIAN_FRONTEND=noninteractive ${pkgMgr} install -y $safePkgs 2>&1"
        val r = PrivilegedExecutionManager.executeCommand(cmd)
        cache.invalidateAll()  // Installed new binaries → invalidate cache
        r.fold(
            onSuccess = { ToolExecutionResult("✅ pkg install $safePkgs:\n${it.take(MAX_OUTPUT)}") },
            onFailure = { ToolExecutionResult("❌ pkg install failed: ${it.message?.take(1000)}", isError = true) }
        )
    }

    /**
     * Install Python packages via pip.
     */
    suspend fun pipInstall(
        packages: String,
        upgrade:  Boolean = false,
        venvPath: String? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val safePkgs = sanitizePackageList(packages)
            ?: return@withContext err("Invalid package list.")
        val (pyBin, envP) = resolveInterpreterAndEnv(venvPath)
            ?: return@withContext err("Python not found.")
        val upgradeFlag = if (upgrade) " --upgrade" else ""
        val pipBin = resolvePip(pyBin)
        val cmd = if (pipBin != null)
            "${envP}${pipBin} install$upgradeFlag $safePkgs 2>&1"
        else
            "${envP}${pyBin} -m pip install$upgradeFlag $safePkgs 2>&1"
        val r = PrivilegedExecutionManager.executeCommand(cmd)
        r.fold(
            onSuccess = { ToolExecutionResult("✅ pip install:\n${it.take(MAX_OUTPUT)}") },
            onFailure = { ToolExecutionResult("❌ pip failed: ${it.message?.take(1000)}", isError = true) }
        )
    }

    /**
     * Execute an npm command (e.g. `install -g typescript`).
     */
    suspend fun npmCommand(subcommand: String, cwd: String? = null): ToolExecutionResult =
        executeShell(
            script    = "${buildEnvPrefix()}${TERMUX_NPM} $subcommand 2>&1",
            cwd       = cwd,
            useBase64 = false
        )

    /**
     * Find a binary across all known locations.
     * Caching-aware alias for [resolveBinary].
     */
    suspend fun findBinary(name: String): String? = resolveBinary(name)

    /**
     * Generate a comprehensive status report string (for `env_check` agent tool).
     */
    suspend fun statusReport(): ToolExecutionResult {
        val state = probe()
        val sb = StringBuilder()

        sb.appendLine("╔══ OmniDev Environment Status ═══════════════════════════════╗")
        sb.appendLine("║ Phase       : ${state.phase}")
        sb.appendLine("║ Backend     : ${state.privilegeBackend}")
        sb.appendLine("║ Termux      : ${if (state.termuxPrefix != null) "✅ ${state.termuxPrefix}" else "❌ not installed"}")
        sb.appendLine("║ Dalvikvm    : ${ToolDownloaderEngine.findDalvikvm() ?: "❌ not found"}")
        sb.appendLine("║ Probe time  : ${state.probeTimeMs}ms")
        sb.appendLine("║")

        sb.appendLine("║ VERIFIED RUNTIMES (actual paths, validated)")
        state.runtimes.entries.sortedBy { it.key }.forEach { (_, rs) ->
            val icon = if (rs.available) "✅" else "❌"
            val pathStr = when {
                rs.path == null -> "not found"
                !isValidBinaryPath(rs.path) -> "❌ invalid path: ${rs.path?.take(40)}"
                else -> rs.path
            }
            val ver = rs.version?.take(40)?.let { " ($it)" } ?: ""
            val src = rs.source?.let { " [$it]" } ?: ""
            sb.appendLine("║   $icon ${rs.name.padEnd(10)}$pathStr$ver$src")
        }

        // Show OmniDev-installed tools
        val omniTools = ToolDownloaderEngine.listInstalled()
        if (omniTools.isNotEmpty()) {
            sb.appendLine("║")
            sb.appendLine("║ OMNIDEV-INSTALLED TOOLS (${ToolDownloaderEngine.INSTALL_DIR})")
            omniTools.forEach { sb.appendLine("║   ✅ $it") }
        }

        if (state.error != null) sb.appendLine("║ ⚠️  ${state.error}")
        sb.appendLine("╚═══════════════════════════════════════════════════════════════╝")
        sb.appendLine()
        sb.appendLine("TOOL INSTALLATION (no Termux, no curl/wget needed):")
        sb.appendLine("  Install apktool : action=ensure_tool tool=apktool")
        sb.appendLine("  Install jadx    : action=ensure_tool tool=jadx")
        sb.appendLine("  Direct install  : use tool 'install_tool' action=install tool_name=apktool")
        sb.appendLine()
        sb.appendLine("QUICK ACTIONS:")
        sb.appendLine("  Full bootstrap  : action=bootstrap")
        sb.appendLine("  Install Python  : action=install_python")
        sb.appendLine("  Install Node.js : action=install_node")
        sb.appendLine("  Install Git     : action=install_git")
        sb.appendLine("NOTES:")
        sb.appendLine("  • Downloads use OkHttp (built-in) — no external tools required")
        sb.appendLine("  • JAR tools require ART: ${ToolDownloaderEngine.findDalvikvm() ?: "NOT FOUND"}")

        return ToolExecutionResult(sb.toString().trimEnd())
    }

    // ── Private: Runtime Probers ───────────────────────────────────────────

    private suspend fun probeRuntime(name: String, finder: suspend () -> RuntimeStatus): RuntimeStatus {
        return try { finder() } catch (e: Exception) {
            RuntimeStatus(name, available = false)
        }
    }

    private suspend fun findTermux(): RuntimeStatus {
        val exists = File(TERMUX_BASH).exists()
        if (!exists) return RuntimeStatus("termux", false, source = "termux")
        val version = exec("${buildEnvPrefix()}${TERMUX_PKG} --version 2>/dev/null")
            ?.firstLine() ?: "unknown"
        return RuntimeStatus("termux", true, TERMUX_PREFIX, version, "termux")
    }

    private suspend fun findPython(): RuntimeStatus  = findInterpreter("python",
        listOf(TERMUX_PYTHON, TERMUX_PYTHON3) + SYSTEM_PYTHON_PATHS)

    private suspend fun findPython3(): RuntimeStatus = findInterpreter("python3",
        listOf(TERMUX_PYTHON3, TERMUX_PYTHON) + SYSTEM_PYTHON_PATHS)

    private suspend fun findNode(): RuntimeStatus    = findInterpreter("node",
        listOf(TERMUX_NODE) + SYSTEM_NODE_PATHS)

    private suspend fun findNpm(): RuntimeStatus {
        val path = resolveBinary("npm") ?: return RuntimeStatus("npm", false)
        val envP = if (path.startsWith(TERMUX_BIN)) buildEnvPrefix() else ""
        val ver = exec("${envP}${path} --version 2>/dev/null")?.firstLine()
        return RuntimeStatus("npm", true, path, ver, if (path.startsWith(TERMUX_BIN)) "termux" else "system")
    }

    private suspend fun findGit(): RuntimeStatus     = findInterpreter("git",
        listOf(TERMUX_GIT) + SYSTEM_GIT_PATHS)

    private suspend fun findCurl(): RuntimeStatus    = findBinSimple("curl")
    private suspend fun findWget(): RuntimeStatus    = findBinSimple("wget")
    private suspend fun findBash(): RuntimeStatus    = findBinSimple("bash")
    private suspend fun findBusybox(): RuntimeStatus = findBinSimple("busybox")
    private suspend fun findRuby(): RuntimeStatus    = findBinSimple("ruby")
    private suspend fun findPerl(): RuntimeStatus    = findBinSimple("perl")

    private suspend fun findInterpreter(name: String, candidates: List<String>): RuntimeStatus {
        for (candidate in candidates) {
            if (!File(candidate).exists()) continue
            val envP = if (candidate.startsWith(TERMUX_BIN)) buildEnvPrefix() else ""
            val ver = exec("${envP}'${candidate}' --version 2>&1 | head -1")?.firstLine()?.take(80)
            val src = if (candidate.startsWith(TERMUX_BIN)) "termux" else "system"
            cache.put(name, candidate)
            return RuntimeStatus(name, true, candidate, ver, src)
        }

        // Try Shizuku `which` with path validation
        val whichPath = exec("which '$name' 2>/dev/null")?.trim()
        if (isValidBinaryPath(whichPath)) {
            // Verify file exists
            val exists = PrivilegedExecutionManager.executeCommand(
                "test -f '$whichPath' && echo E"
            ).getOrDefault("").contains("E")
            if (exists) {
                val envP = if (whichPath!!.startsWith(TERMUX_BIN)) buildEnvPrefix() else ""
                val ver = exec("${envP}'${whichPath}' --version 2>&1 | head -1")?.firstLine()?.take(80)
                cache.put(name, whichPath)
                return RuntimeStatus(name, true, whichPath, ver, "system")
            }
        }

        // Check ToolDownloaderEngine install dir
        val omniPath = "${ToolDownloaderEngine.INSTALL_DIR}/$name"
        val omniOk = PrivilegedExecutionManager.executeCommand(
            "test -x '$omniPath' && echo OK"
        ).getOrDefault("").contains("OK")
        if (omniOk) {
            val ver = exec("'$omniPath' --version 2>&1 | head -1")?.firstLine()?.take(80)
            cache.put(name, omniPath)
            return RuntimeStatus(name, true, omniPath, ver, "omnidev")
        }

        cache.putMiss(name)
        return RuntimeStatus(name, available = false)
    }

    private suspend fun findBinSimple(name: String): RuntimeStatus {
        val path = resolveBinary(name) ?: return RuntimeStatus(name, available = false)
        val envP = if (path.startsWith(TERMUX_BIN)) buildEnvPrefix() else ""
        // Run `--version` but cap output and tolerate non-zero exit
        val verRaw = exec("${envP}'${path}' --version 2>&1 | head -1")
        val ver = verRaw?.firstLine()?.take(80)
        val src = when {
            path.startsWith(TERMUX_BIN)                        -> "termux"
            path.startsWith(ToolDownloaderEngine.INSTALL_DIR)  -> "omnidev"
            else                                               -> "system"
        }
        return RuntimeStatus(name, available = true, path = path, version = ver, source = src)
    }

    // ── Private: Privilege Backend Detection ──────────────────────────────

    private suspend fun detectPrivilegeBackend(): String {
        return try {
            when {
                PrivilegedExecutionManager.isShizukuReady()  -> "shizuku"
                PrivilegedExecutionManager.isRishReady()     -> "rish"
                PrivilegedExecutionManager.isRootAvailable() -> "root"
                else                                          -> "none"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Backend detection exception: ${e.message}")
            "none"
        }
    }

    // ── Private: Interpreter Resolution ──────────────────────────────────

    /**
     * Resolve the best Python interpreter and env prefix.
     * If [venvPath] is provided and valid, uses that venv's python.
     */
    internal suspend fun resolveInterpreterAndEnv(
        venvPath: String? = null
    ): Pair<String, String>? {
        if (!venvPath.isNullOrBlank()) {
            val venvPy = listOf("$venvPath/bin/python3", "$venvPath/bin/python")
                .firstOrNull { File(it).exists() }
            if (venvPy != null) {
                val envP = if (isTermuxUsable()) buildEnvPrefix() else ""
                return venvPy to envP
            }
        }
        val pyPath = listOf(TERMUX_PYTHON3, TERMUX_PYTHON).firstOrNull { File(it).exists() }
            ?: resolveBinary("python3")
            ?: resolveBinary("python")
            ?: SYSTEM_PYTHON_PATHS.firstOrNull { File(it).exists() }
            ?: return null
        val envP = if (pyPath.startsWith(TERMUX_BIN)) buildEnvPrefix() else ""
        return pyPath to envP
    }

    private suspend fun resolvePip(pythonBin: String): String? {
        val dir = File(pythonBin).parent ?: return null
        listOf("$dir/pip3", "$dir/pip").forEach { if (File(it).exists()) return it }
        val envP = if (pythonBin.startsWith(TERMUX_BIN)) buildEnvPrefix() else ""
        return exec("${envP}which pip3 2>/dev/null")?.trim()?.takeIf { it.isNotBlank() }
            ?: exec("${envP}which pip 2>/dev/null")?.trim()?.takeIf { it.isNotBlank() }
    }

    // ── Private: Base64 Injection ─────────────────────────────────────────

    /**
     * Builds a command that:
     *  1. Writes [script] to a temp file via base64 decode (bypasses ALL quoting).
     *  2. Executes the temp file.
     *  3. Deletes the temp file.
     *
     * This is the most reliable way to execute complex scripts with special chars
     * through the Shizuku/rish shell bridge.
     */
    private fun buildBase64InjectionCommand(script: String, envPrefix: String): String {
        // envPrefix is now embedded inside the script block via buildTermuxEnvBlock(),
        // so only the base64 wrapper is needed here.
        // Delegate to TermuxExecutionFix which uses the proven pattern.
        return TermuxExecutionFix.buildBase64Command(script)
    }

    @Suppress("unused")
    private fun buildBase64InjectionCommandLegacy(script: String, envPrefix: String): String {
        val b64 = android.util.Base64.encodeToString(
            script.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val tmp = "/data/local/tmp/omni_env_${System.currentTimeMillis()}.sh"
        // Write → chmod → execute → delete — all in one atomic shell command
        return "echo '$b64' | base64 -d > $tmp && chmod +x $tmp && " +
               "${envPrefix}sh $tmp 2>&1; EXIT_CODE=\$?; rm -f $tmp; exit \$EXIT_CODE"
    }

    // ── Private: Utilities ────────────────────────────────────────────────

    private suspend fun exec(cmd: String): String? =
        PrivilegedExecutionManager.executeCommand(cmd)
            .getOrNull()?.trim()
            ?.takeIf { it.isNotBlank() && it != "(no output)" }

    private fun String.firstLine() = lineSequence().firstOrNull { it.isNotBlank() }?.trim()

    private fun sanitizePackageList(packages: String): String? {
        val safe = packages.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-Z0-9_.\\-\\[\\]=~<>!]"), "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return safe.ifBlank { null }
    }

    private fun sanitizeName(name: String) = name.replace(Regex("[^a-zA-Z0-9_.\\-]"), "")

    fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"

    private fun err(msg: String) = ToolExecutionResult(msg, isError = true)

    private const val MAX_OUTPUT = 12_000

    // ── ToolManager API ────────────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "advanced_terminal",
            description = """
                Full-lifecycle shell & runtime engine backed by the Termux environment.
                Actions:
                - 'status': Probe all runtimes and report availability.
                - 'exec'  : Run a one-shot shell script/command and return output.
                - 'python_run': Execute inline Python code.
                - 'npm'   : Run an npm sub-command (e.g., 'install').
                - 'ensure_tool': Pre-flight check + auto-provision a missing tool (Termux first, standalone fallback).
                - 'pip_install': Install Python packages via pip.
                - 'pkg_install': Install Termux packages via pkg/apt.
                - 'pkg_update' : Update all installed Termux packages.
                - 'bootstrap'  : Run the full 8-step idempotent bootstrap plan.
                - 'find_binary': Resolve the path of an executable.
            """.trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string", "Action to perform (status, exec, python_run, npm, ensure_tool, pip_install, pkg_install, pkg_update, bootstrap, find_binary)", required = true),
                ToolParameter("command", "string", "Shell command or npm sub-command to run.", required = false),
                ToolParameter("code", "string", "Inline Python code (for python_run).", required = false),
                ToolParameter("packages", "string", "Space-separated package list (for pip_install / pkg_install).", required = false),
                ToolParameter("binary", "string", "Binary name to locate (for find_binary).", required = false),
                ToolParameter("tool", "string", "Tool to verify/provision (e.g., apktool, jadx, python, node).", required = false),
                ToolParameter("language", "string", "Optional runtime hint: python, node, java.", required = false),
                ToolParameter("termux_package", "string", "Optional explicit Termux package to install.", required = false),
                ToolParameter("pip_package", "string", "Optional Python package to install after Python is available.", required = false),
                ToolParameter("npm_package", "string", "Optional npm package to install globally after Node is available.", required = false),
                ToolParameter("fallback_url", "string", "Optional direct HTTPS download URL for standalone fallback.", required = false),
                ToolParameter("java_class", "string", "Optional Java main class used when fallback_url points to a jar.", required = false),
                ToolParameter("verify_command", "string", "Optional command used to verify tool health after install.", required = false),
                ToolParameter("cwd", "string", "Working directory.", required = false)
            )
        ),
        ToolDefinition(
            name = "setup_build_environment",
            description = "Bootstrap or inspect the Termux-based build environment. Alias for advanced_terminal.",
            parameters = listOf(
                ToolParameter("action", "string", "Action (status, bootstrap, ensure_tool, pkg_install, pip_install, exec, python_run)", required = true),
                ToolParameter("command", "string", "Shell command to run.", required = false),
                ToolParameter("packages", "string", "Packages to install.", required = false),
                ToolParameter("tool", "string", "Tool to verify/provision.", required = false),
                ToolParameter("fallback_url", "string", "Direct HTTPS URL for standalone fallback binary/jar.", required = false),
                ToolParameter("code", "string", "Inline Python code.", required = false),
                ToolParameter("cwd", "string", "Working directory.", required = false)
            )
        )
    )

    suspend fun executeTool(
        name: String,
        arguments: Map<String, String>,
        scopePath: String? = null
    ): ToolExecutionResult {
        val action = arguments["action"]?.lowercase()?.trim()
            ?: return ToolExecutionResult("Missing required argument 'action'.", isError = true)
        val cwd = arguments["cwd"]?.takeIf { it.isNotBlank() } ?: scopePath

        return when (action) {
            "status", "env_check" -> statusReport()

            "exec", "script", "shell" -> {
                val cmd = arguments["command"] ?: arguments["script"]
                    ?: return ToolExecutionResult("Missing 'command' argument for action '$action'.", isError = true)
                executeShell(cmd, cwd)
            }

            "python_run", "python" -> {
                val code = arguments["code"]
                    ?: return ToolExecutionResult("Missing 'code' argument for python_run.", isError = true)
                runPython(code, arguments["args"], cwd)
            }

            "npm" -> {
                val cmd = arguments["command"]
                    ?: return ToolExecutionResult("Missing 'command' argument for npm.", isError = true)
                npmCommand(cmd, cwd)
            }

            "ensure_tool", "provision_tool", "auto_provision" ->
                ensureTool(arguments)

            "pip_install", "pip" -> {
                val packages = arguments["packages"]
                    ?: return ToolExecutionResult("Missing 'packages' argument for pip_install.", isError = true)
                pipInstall(packages)
            }

            "pkg_install", "pkg" -> {
                val packages = arguments["packages"]
                    ?: return ToolExecutionResult("Missing 'packages' argument for pkg_install.", isError = true)
                pkgInstall(packages)
            }

            "pkg_update", "update" -> {
                val updateCmd = "${buildEnvPrefix()}DEBIAN_FRONTEND=noninteractive pkg upgrade -y 2>&1"
                val r = PrivilegedExecutionManager.executeCommand(updateCmd)
                cache.invalidateAll()
                r.fold(
                    onSuccess = { ToolExecutionResult("✅ pkg upgrade:\n${it.take(MAX_OUTPUT)}") },
                    onFailure = { ToolExecutionResult("❌ pkg upgrade failed: ${it.message?.take(1000)}", isError = true) }
                )
            }

            "bootstrap", "setup" -> {
                val plan = buildStandardBootstrapPlan()
                val results = runBootstrap(plan)
                val summary = results.joinToString("\n") { (id, r) ->
                    val icon = when (r) {
                        is BootstrapResult.Success    -> "✅"
                        is BootstrapResult.AlreadyDone -> "✔️ "
                        is BootstrapResult.Skipped    -> "⏭️ "
                        is BootstrapResult.Failed     -> "❌"
                    }
                    "$icon $id: ${when (r) {
                        is BootstrapResult.Success     -> r.message
                        is BootstrapResult.AlreadyDone -> r.message
                        is BootstrapResult.Skipped     -> r.reason
                        is BootstrapResult.Failed      -> r.reason
                    }}"
                }
                ToolExecutionResult("Bootstrap complete:\n$summary")
            }

            "find_binary" -> {
                val binary = arguments["binary"]
                    ?: return ToolExecutionResult("Missing 'binary' argument for find_binary.", isError = true)
                val path = findBinary(binary)
                if (path != null) ToolExecutionResult("✅ $binary → $path")
                else ToolExecutionResult("❌ '$binary' not found.", isError = true)
            }

            else -> ToolExecutionResult(
                "Unknown action '$action'. Valid actions: status, exec, python_run, npm, ensure_tool, pip_install, pkg_install, pkg_update, bootstrap, find_binary.",
                isError = true
            )
        }
    }

    private suspend fun ensureTool(arguments: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.IO) {

            val toolRaw = arguments["tool"] ?: arguments["binary"] ?: arguments["command"]
            val toolName = toolRaw?.trim()?.let { sanitizeName(it) }.orEmpty()
            if (toolName.isBlank()) {
                return@withContext ToolExecutionResult("Missing 'tool' argument.", isError = true)
            }

            val verifyCommand = arguments["verify_command"]?.trim().orEmpty()
            val log = StringBuilder()
            fun progress(msg: String) { log.appendLine(msg) }

            // ── Step 1: Already installed? ────────────────────────────────────────
            val existingPath = resolveBinary(toolName)
            if (existingPath != null) {
                val check = if (verifyCommand.isNotBlank()) {
                    executeShell(verifyCommand)
                } else {
                    executeShell("'$existingPath' --version 2>&1 | head -3 || true")
                }
                return@withContext ToolExecutionResult(
                    buildString {
                        appendLine("✅ '$toolName' is already available at: $existingPath")
                        if (!check.isError && check.output.isNotBlank()) {
                            appendLine("Verification:")
                            appendLine(check.output.take(300))
                        }
                    }.trimEnd()
                )
            }

            // ── Step 2: ToolDownloaderEngine (OkHttp + dalvikvm, no Termux needed) ──
            val context = appContext

            if (context != null && toolName.lowercase() in ToolDownloaderEngine.KNOWN_TOOLS) {
                progress("🔧 Using ToolDownloaderEngine for '$toolName'…")
                val result = ToolDownloaderEngine.installTool(context, toolName) { progress(it) }
                if (result.isSuccess) {
                    cache.put(toolName, result.getOrNull()!!)
                    val verify = if (verifyCommand.isNotBlank()) executeShell(verifyCommand)
                    else executeShell("'${result.getOrNull()}' --version 2>&1 | head -3 || true")
                    return@withContext ToolExecutionResult(
                        buildString {
                            appendLine(log.toString().trimEnd())
                            appendLine()
                            appendLine("✅ '$toolName' installed → ${result.getOrNull()}")
                            if (!verify.isError && verify.output.isNotBlank()) {
                                appendLine("Verification:")
                                appendLine(verify.output.take(300))
                            }
                        }.trimEnd()
                    )
                } else {
                    progress("⚠️ ToolDownloaderEngine failed: ${result.exceptionOrNull()?.message}")
                    progress("   Will try fallback methods…")
                }
            }

            // ── Step 2b: Omni native/security toolchain ────────────────────────────
            val omniCtx = appContext
            val omniTool = when (toolName) {
                "python", "python3" -> OmniNativeToolsManager.Tool.PYTHON
                "aapt2", "aapt" -> OmniNativeToolsManager.Tool.AAPT2
                "jadx" -> OmniNativeToolsManager.Tool.JADX
                "apktool" -> OmniNativeToolsManager.Tool.APKTOOL
                "busybox", "strings" -> OmniNativeToolsManager.Tool.BUSYBOX
                else -> null
            }
            if (omniCtx != null && omniTool != null) {
                runCatching { OmniNativeToolsManager.init(omniCtx) }
                val omniRes = OmniNativeToolsManager.ensure(omniCtx, omniTool)
                if (omniRes.isSuccess) {
                    val omniFile = omniRes.getOrNull()
                    if (omniFile != null) {
                        cache.put(toolName, omniFile.absolutePath)
                        return@withContext ToolExecutionResult(
                            "✅ Provisioned '$toolName' via Omni toolchain: ${omniFile.absolutePath}"
                        )
                    }
                } else {
                    progress("Omni toolchain install failed: ${omniRes.exceptionOrNull()?.message}")
                }
            }

            // ── Step 3: Termux (pkg / pip / npm) ─────────────────────────────────
            if (isTermuxUsable()) {
                progress("Termux available → trying package managers…")

                val pipPackage = arguments["pip_package"]?.trim().orEmpty()
                val npmPackage = arguments["npm_package"]?.trim().orEmpty()
                val termuxPackage = arguments["termux_package"]?.trim()?.let { sanitizePackageList(it) }.orEmpty()
                val language = arguments["language"]?.trim()?.lowercase()

                val defaultPkg = when {
                    termuxPackage.isNotBlank() -> termuxPackage
                    toolName == "python" || toolName == "python3" -> "python"
                    toolName == "node" || toolName == "npm"       -> "nodejs"
                    toolName == "git"                              -> "git"
                    toolName == "curl"                             -> "curl"
                    toolName == "wget"                             -> "wget"
                    toolName == "apktool"                          -> "apktool"
                    toolName == "jadx"                             -> "jadx"
                    else -> defaultTermuxPackageFor(toolName, language).orEmpty()
                }

                if (defaultPkg.isNotBlank()) {
                    progress("pkg install $defaultPkg …")
                    val pkgResult = pkgInstall(defaultPkg)
                    progress(pkgResult.output.take(300))
                }
                if (pipPackage.isNotBlank()) {
                    progress("pip install $pipPackage …")
                    val pipResult = pipInstall(pipPackage)
                    progress(pipResult.output.take(300))
                }
                if (npmPackage.isNotBlank()) {
                    val safeNpm = sanitizePackageList(npmPackage)
                    if (safeNpm != null) {
                        progress("npm install -g $safeNpm …")
                        progress(npmCommand("install -g $safeNpm").output.take(300))
                    }
                }

                val resolved = resolveBinary(toolName)
                if (resolved != null) {
                    val verify = if (verifyCommand.isNotBlank()) executeShell(verifyCommand)
                    else executeShell("'$resolved' --version 2>&1 | head -3 || true")
                    return@withContext ToolExecutionResult(
                        buildString {
                            appendLine(log.toString().trimEnd())
                            appendLine()
                            appendLine("✅ Provisioned '$toolName' via Termux: $resolved")
                            if (!verify.isError && verify.output.isNotBlank()) {
                                appendLine("Verification:")
                                appendLine(verify.output.take(300))
                            }
                        }.trimEnd()
                    )
                }
                progress("Termux provisioning did not expose '$toolName' in PATH.")
            }

            // ── Step 4: Custom fallback_url ───────────────────────────────────────
            val fallbackUrl = arguments["fallback_url"]?.trim().orEmpty()
            val javaClass = arguments["java_class"]?.trim().orEmpty()

            if (fallbackUrl.isNotBlank() && fallbackUrl.startsWith("https://")) {
                if (context != null) {
                    progress("⬇ Downloading from: $fallbackUrl")
                    val mainClass = javaClass.ifBlank {
                        if (fallbackUrl.endsWith(".jar")) {
                            return@withContext ToolExecutionResult(
                                "${log.toString().trimEnd()}\n\n" +
                                "❌ JAR detected but 'java_class' not provided. " +
                                "Cannot create launcher without main class.",
                                isError = true
                            )
                        }
                        ""
                    }
                    val installResult = ToolDownloaderEngine.installCustomTool(
                        context     = context,
                        name        = toolName,
                        downloadUrl = fallbackUrl,
                        mainClass   = mainClass,
                        jvmArgs     = arguments["jvm_args"] ?: "-Xmx512m",
                        onProgress  = { progress(it) }
                    )
                    if (installResult.isSuccess) {
                        cache.put(toolName, installResult.getOrNull()!!)
                        return@withContext ToolExecutionResult(
                            buildString {
                                appendLine(log.toString().trimEnd())
                                appendLine()
                                appendLine("✅ Custom tool '$toolName' installed → ${installResult.getOrNull()}")
                            }.trimEnd()
                        )
                    } else {
                        progress("❌ Custom install failed: ${installResult.exceptionOrNull()?.message}")
                    }
                }
            }

            // ── Step 5: Failure ───────────────────────────────────────────────────
            ToolExecutionResult(
                buildString {
                    appendLine(log.toString().trimEnd())
                    appendLine()
                    appendLine("❌ Could not provision '$toolName'.")
                    appendLine()
                    appendLine("Options:")
                    appendLine("  • Known tools (auto-download): action=ensure_tool tool=apktool")
                    appendLine("    Known: ${ToolDownloaderEngine.KNOWN_TOOLS.keys.joinToString()}")
                    appendLine("  • Custom JAR : action=ensure_tool tool=$toolName fallback_url=https://... java_class=com.example.Main")
                    appendLine("  • Custom bin : action=ensure_tool tool=$toolName fallback_url=https://.../binary")
                    appendLine("  • Direct install: use install_tool tool_name=$toolName")
                }.trimEnd(),
                isError = true
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
}
