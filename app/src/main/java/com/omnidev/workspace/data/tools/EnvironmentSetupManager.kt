package com.omnidev.workspace.data.tools

import android.content.Context
import android.util.Log
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Phase of the unified runtime state machine. */
enum class SetupPhase { UNINITIALIZED, PROBING, READY, DEGRADED, FAILED }

data class RuntimeStatus(
    val name: String,
    val available: Boolean,
    val path: String? = null,
    val version: String? = null,
    val source: String? = null
)

data class SetupState(
    val phase: SetupPhase = SetupPhase.UNINITIALIZED,
    val runtimes: Map<String, RuntimeStatus> = emptyMap(),
    val privilegeBackend: String = "none",
    val termuxPrefix: String? = null,
    val probeTimeMs: Long = 0L,
    val error: String? = null
) {
    val isReady: Boolean get() = phase == SetupPhase.READY
    val isDegraded: Boolean get() = phase == SetupPhase.DEGRADED

    fun runtime(name: String): RuntimeStatus =
        runtimes[name] ?: RuntimeStatus(name, available = false)

    fun summaryLine(): String = buildString {
        append("[${phase.name}] backend=$privilegeBackend ")
        append("runtimes=${runtimes.values.count { it.available }}/${runtimes.size}")
        if (error != null) append(" ⚠️ $error")
    }
}

data class BootstrapStep(
    val id: String,
    val description: String,
    val dependsOn: List<String> = emptyList(),
    val checkFn: suspend () -> Boolean,
    val installFn: suspend () -> BootstrapResult
)

sealed class BootstrapResult {
    data class Success(val message: String) : BootstrapResult()
    data class AlreadyDone(val message: String) : BootstrapResult()
    data class Failed(val reason: String, val hint: String = "") : BootstrapResult()
    data class Skipped(val reason: String) : BootstrapResult()
}

private class BinaryCache(private val ttlMs: Long = 120_000L) {
    private data class Entry(val path: String, val time: Long)
    private val hits = ConcurrentHashMap<String, Entry>()
    private val misses = ConcurrentHashMap<String, Long>()

    fun get(name: String): String? {
        val entry = hits[name] ?: return null
        if (System.currentTimeMillis() - entry.time > ttlMs) {
            hits.remove(name)
            return null
        }
        return entry.path
    }

    fun isCachedMiss(name: String): Boolean {
        val time = misses[name] ?: return false
        if (System.currentTimeMillis() - time > ttlMs) {
            misses.remove(name)
            return false
        }
        return true
    }

    fun put(name: String, path: String) {
        misses.remove(name)
        hits[name] = Entry(path, System.currentTimeMillis())
    }

    fun putMiss(name: String) {
        hits.remove(name)
        misses[name] = System.currentTimeMillis()
    }

    fun invalidate(name: String) {
        hits.remove(name)
        misses.remove(name)
    }

    fun invalidateAll() {
        hits.clear()
        misses.clear()
    }
}

/**
 * Canonical developer-runtime coordinator.
 *
 * There are deliberately three separate execution domains:
 *  1. Termux RunCommandService -> developer shell, pkg/apt, Python, Node, Git.
 *  2. Shizuku UserService     -> privileged Android/system commands.
 *  3. rish                    -> ADB-equivalent interactive/shell semantics.
 *
 * Never execute Termux private binaries through Shizuku. Linux app sandboxing
 * makes that unreliable even when PATH/LD_LIBRARY_PATH are injected correctly.
 */
object EnvironmentSetupManager {

    private const val TAG = "EnvSetupMgr"
    private const val MIN_PROBE_INTERVAL_MS = 15_000L
    private const val MAX_OUTPUT = 24_000
    private const val TERMUX_TIMEOUT_MS = 120_000L

    // Compatibility constants. They describe Termux's canonical paths but must
    // not be used as proof that this app can directly access another app sandbox.
    const val TERMUX_ROOT = "/data/data/com.termux/files"
    const val TERMUX_PREFIX = "$TERMUX_ROOT/usr"
    const val TERMUX_HOME = "$TERMUX_ROOT/home"
    const val TERMUX_BIN = "$TERMUX_PREFIX/bin"
    const val TERMUX_BASH = "$TERMUX_BIN/bash"
    const val TERMUX_SH = "$TERMUX_BIN/sh"
    const val TERMUX_PYTHON3 = "$TERMUX_BIN/python3"
    const val TERMUX_PYTHON = "$TERMUX_BIN/python"
    const val TERMUX_NODE = "$TERMUX_BIN/node"
    const val TERMUX_GIT = "$TERMUX_BIN/git"
    const val TERMUX_PKG = "$TERMUX_BIN/pkg"
    const val TERMUX_APT = "$TERMUX_BIN/apt"
    const val TERMUX_PIP3 = "$TERMUX_BIN/pip3"
    const val TERMUX_NPM = "$TERMUX_BIN/npm"

    private val _stateFlow = MutableStateFlow(SetupState())
    val stateFlow: StateFlow<SetupState> = _stateFlow.asStateFlow()
    val currentState: SetupState get() = _stateFlow.value

    private val cache = BinaryCache()
    private val probeMutex = Mutex()
    private val packageMutex = Mutex()
    private val lastProbeMs = AtomicLong(0L)

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        TermuxRunCommandBridge.init(app)
    }

    fun isTermuxUsable(): Boolean {
        val context = appContext ?: return false
        return TermuxRunCommandBridge.isInitialized() &&
            TermuxRunCommandBridge.isTermuxInstalled(context) &&
            TermuxRunCommandBridge.hasRunCommandPermission(context)
    }

    /**
     * Legacy compatibility: Termux execution now occurs *inside* Termux, where
     * its environment is already correct. Injecting PREFIX/LD_PRELOAD is harmful.
     */
    fun buildEnvPrefix(): String = ""
    fun buildInlineEnvPrefix(): String = ""

    suspend fun probe(force: Boolean = false): SetupState = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastProbeMs.get() < MIN_PROBE_INTERVAL_MS &&
            currentState.phase != SetupPhase.UNINITIALIZED
        ) return@withContext currentState

        probeMutex.withLock {
            val nowLocked = System.currentTimeMillis()
            if (!force && nowLocked - lastProbeMs.get() < MIN_PROBE_INTERVAL_MS &&
                currentState.phase != SetupPhase.UNINITIALIZED
            ) return@withLock currentState

            _stateFlow.value = currentState.copy(phase = SetupPhase.PROBING)
            val started = System.currentTimeMillis()
            val privileged = detectPrivilegeBackend()

            try {
                val termuxProbe = probeTermuxRuntimes()
                val transportReady = termuxProbe.first
                val runtimes = termuxProbe.second
                val phase = when {
                    transportReady -> SetupPhase.READY
                    privileged != "none" -> SetupPhase.DEGRADED
                    else -> SetupPhase.FAILED
                }
                val error = if (transportReady) null else termuxSetupHint()
                val state = SetupState(
                    phase = phase,
                    runtimes = runtimes,
                    privilegeBackend = privileged,
                    termuxPrefix = if (transportReady) TERMUX_PREFIX else null,
                    probeTimeMs = System.currentTimeMillis() - started,
                    error = error
                )
                lastProbeMs.set(System.currentTimeMillis())
                _stateFlow.value = state
                Log.i(TAG, "Probe: ${state.summaryLine()}")
                state
            } catch (t: Throwable) {
                val state = SetupState(
                    phase = if (privileged != "none") SetupPhase.DEGRADED else SetupPhase.FAILED,
                    privilegeBackend = privileged,
                    probeTimeMs = System.currentTimeMillis() - started,
                    error = "Runtime probe failed: ${t.message ?: t.javaClass.simpleName}"
                )
                _stateFlow.value = state
                state
            }
        }
    }

    private suspend fun probeTermuxRuntimes(): Pair<Boolean, Map<String, RuntimeStatus>> {
        val names = listOf(
            "bash", "python", "python3", "node", "npm", "git",
            "curl", "wget", "busybox", "ruby", "perl", "pkg"
        )
        val context = appContext
        if (context == null || !TermuxRunCommandBridge.isInitialized()) {
            return false to names.associateWith { RuntimeStatus(it, false, source = "termux") }
        }

        val marker = "__OMNI_RT__"
        val script = buildString {
            appendLine("printf '${marker}transport\\tOK\\n'")
            appendLine("for b in ${names.joinToString(" ") { shellQuote(it) }}; do")
            appendLine("  p=\$(command -v \"\$b\" 2>/dev/null || true)")
            appendLine("  if [ -n \"\$p\" ]; then")
            appendLine("    v=\$(\"\$p\" --version 2>&1 | head -n 1 | tr '\\t\\r\\n' '   ')")
            appendLine("    printf '${marker}%s\\t%s\\t%s\\n' \"\$b\" \"\$p\" \"\$v\"")
            appendLine("  else printf '${marker}%s\\t-\\t-\\n' \"\$b\"; fi")
            appendLine("done")
        }
        val result = TermuxRunCommandBridge.executeShell(script, timeoutMs = 30_000L, label = "OmniDev runtime probe")
        if (!result.isSuccess) {
            return false to names.associateWith { RuntimeStatus(it, false, source = "termux") }
        }

        val found = mutableMapOf<String, RuntimeStatus>()
        result.stdout.lineSequence()
            .filter { it.startsWith(marker) }
            .forEach { line ->
                val fields = line.removePrefix(marker).split('\t', limit = 3)
                if (fields.firstOrNull() == "transport") return@forEach
                val name = fields.getOrNull(0).orEmpty()
                if (name !in names) return@forEach
                val path = fields.getOrNull(1)?.takeUnless { it == "-" || it.isBlank() }
                val version = fields.getOrNull(2)?.takeUnless { it == "-" || it.isBlank() }
                found[name] = RuntimeStatus(name, path != null, path, version, "termux")
                if (path != null) cache.put(name, path) else cache.putMiss(name)
            }

        names.forEach { name ->
            if (name !in found) found[name] = RuntimeStatus(name, false, source = "termux")
        }
        found["termux"] = RuntimeStatus(
            name = "termux",
            available = true,
            path = TERMUX_PREFIX,
            version = found["pkg"]?.version,
            source = "termux"
        )
        return true to found
    }

    suspend fun resolveBinary(binary: String): String? = withContext(Dispatchers.IO) {
        val name = sanitizeName(binary).ifBlank { return@withContext null }
        cache.get(name)?.let { return@withContext it }
        if (cache.isCachedMiss(name)) return@withContext null

        // Developer runtimes are resolved in Termux's own namespace.
        if (isTermuxUsable()) {
            val result = TermuxRunCommandBridge.executeShell(
                "command -v ${shellQuote(name)} 2>/dev/null || true",
                timeoutMs = 15_000L,
                label = "OmniDev which $name"
            )
            val path = result.stdout.lineSequence().firstOrNull { it.startsWith("/") }?.trim()
            if (result.transportSucceeded && isValidBinaryPath(path)) {
                cache.put(name, path!!)
                return@withContext path
            }
        }

        // OmniDev-downloaded tools often live in /data/local/tmp and are visible
        // to the privileged Android shell rather than the Termux sandbox.
        val omniPath = "${ToolDownloaderEngine.INSTALL_DIR}/$name"
        val omni = PrivilegedExecutionManager.executeCommand(
            "test -x ${shellQuote(omniPath)} && printf '%s' ${shellQuote(omniPath)}"
        ).getOrNull()?.trim()
        if (isValidBinaryPath(omni)) {
            cache.put(name, omni!!)
            return@withContext omni
        }

        val systemCandidates = listOf(
            "/system/bin/$name", "/system/xbin/$name", "/vendor/bin/$name", "/product/bin/$name"
        )
        systemCandidates.firstOrNull { File(it).isFile }?.let {
            cache.put(name, it)
            return@withContext it
        }

        val systemWhich = PrivilegedExecutionManager.executeCommand(
            "command -v ${shellQuote(name)} 2>/dev/null || true"
        ).getOrNull()?.lineSequence()?.firstOrNull { it.startsWith("/") }?.trim()
        if (isValidBinaryPath(systemWhich)) {
            cache.put(name, systemWhich!!)
            return@withContext systemWhich
        }

        cache.putMiss(name)
        null
    }

    suspend fun executeShell(
        script: String,
        cwd: String? = null,
        @Suppress("UNUSED_PARAMETER") useBase64: Boolean? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (script.isBlank()) return@withContext err("Empty script.")
        if (appContext == null) return@withContext err("Runtime is not initialized.")

        val result = TermuxRunCommandBridge.executeShell(
            script = script,
            cwd = cwd,
            timeoutMs = TERMUX_TIMEOUT_MS,
            label = "OmniDev agent terminal"
        )
        toToolResult(result, operation = "terminal")
    }

    suspend fun runPython(
        code: String,
        extraArgs: String? = null,
        cwd: String? = null,
        venvPath: String? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (code.isBlank()) return@withContext err("Python code is empty.")
        val pythonSelector = if (!venvPath.isNullOrBlank()) {
            "${shellQuote(venvPath.trimEnd('/'))}/bin/python"
        } else {
            "\$(command -v python3 2>/dev/null || command -v python 2>/dev/null)"
        }
        val args = extraArgs?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        val script = """
            py=$pythonSelector
            if [ -z "\$py" ] || [ ! -x "\$py" ]; then
              echo 'Python is not installed in Termux. Use pkg_install packages=python.' >&2
              exit 127
            fi
            "\$py" -c ${shellQuote(code)}$args
        """.trimIndent()
        executeShell(script, cwd)
    }

    suspend fun pkgInstall(packages: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        val args = packageArgs(packages) ?: return@withContext err("Invalid or empty package list.")
        packageMutex.withLock {
            val install = "DEBIAN_FRONTEND=noninteractive pkg install -y $args"
            var result = TermuxRunCommandBridge.executeShell(
                install,
                timeoutMs = 10 * 60_000L,
                label = "OmniDev pkg install"
            )

            if (!result.isSuccess && looksRecoverablePackageFailure(result.mergedOutput())) {
                val recovery = """
                    export DEBIAN_FRONTEND=noninteractive
                    dpkg --configure -a || true
                    apt-get -f install -y || true
                    pkg update -y || apt-get update || true
                """.trimIndent()
                TermuxRunCommandBridge.executeShell(
                    recovery,
                    timeoutMs = 5 * 60_000L,
                    label = "OmniDev package recovery"
                )
                result = TermuxRunCommandBridge.executeShell(
                    install,
                    timeoutMs = 10 * 60_000L,
                    label = "OmniDev pkg retry"
                )
            }

            if (result.isSuccess) cache.invalidateAll()
            toToolResult(result, "pkg install")
        }
    }

    suspend fun pipInstall(
        packages: String,
        upgrade: Boolean = false,
        venvPath: String? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val args = packageArgs(packages) ?: return@withContext err("Invalid or empty pip package list.")
        val py = if (!venvPath.isNullOrBlank()) {
            "${shellQuote(venvPath.trimEnd('/'))}/bin/python"
        } else {
            "\$(command -v python3 2>/dev/null || command -v python 2>/dev/null)"
        }
        val upgradeFlag = if (upgrade) " --upgrade" else ""
        val script = """
            py=$py
            if [ -z "\$py" ] || [ ! -x "\$py" ]; then
              echo 'Python is not installed.' >&2
              exit 127
            fi
            "\$py" -m pip install$upgradeFlag $args
        """.trimIndent()
        executeShell(script)
    }

    suspend fun npmCommand(subcommand: String, cwd: String? = null): ToolExecutionResult {
        if (subcommand.isBlank()) return err("npm command is empty.")
        return executeShell(
            "npm_bin=\$(command -v npm 2>/dev/null || true); " +
                "[ -n \"\$npm_bin\" ] || { echo 'npm is not installed.' >&2; exit 127; }; " +
                "\"\$npm_bin\" $subcommand",
            cwd
        )
    }

    suspend fun findBinary(name: String): String? = resolveBinary(name)

    internal suspend fun resolveInterpreterAndEnv(venvPath: String? = null): Pair<String, String>? {
        if (!venvPath.isNullOrBlank()) {
            return "${venvPath.trimEnd('/')}/bin/python" to ""
        }
        val path = resolveBinary("python3") ?: resolveBinary("python") ?: return null
        return path to ""
    }

    suspend fun runBootstrap(
        plan: List<BootstrapStep>,
        onProgress: ((stepId: String, result: BootstrapResult) -> Unit)? = null
    ): List<Pair<String, BootstrapResult>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Pair<String, BootstrapResult>>()
        val completed = mutableSetOf<String>()
        for (step in plan) {
            val unmet = step.dependsOn.filterNot { it in completed }
            val result = if (unmet.isNotEmpty()) {
                BootstrapResult.Skipped("Depends on unfinished steps: $unmet")
            } else {
                try {
                    if (step.checkFn()) BootstrapResult.AlreadyDone("Already ready.") else step.installFn()
                } catch (t: Throwable) {
                    BootstrapResult.Failed("${t.javaClass.simpleName}: ${t.message}")
                }
            }
            results += step.id to result
            onProgress?.invoke(step.id, result)
            if (result is BootstrapResult.Success || result is BootstrapResult.AlreadyDone) {
                completed += step.id
            }
        }
        probe(force = true)
        results
    }

    fun buildStandardBootstrapPlan(): List<BootstrapStep> = listOf(
        BootstrapStep(
            id = "termux_transport",
            description = "Verify official Termux RunCommand transport",
            checkFn = { termuxHealth().isSuccess },
            installFn = {
                val health = termuxHealth()
                if (health.isSuccess) BootstrapResult.Success("Termux RunCommand transport ready.")
                else BootstrapResult.Failed(
                    "Termux transport is not ready.",
                    termuxSetupHint() + "\n" + health.output.take(500)
                )
            }
        ),
        BootstrapStep(
            id = "package_index",
            description = "Refresh Termux package index",
            dependsOn = listOf("termux_transport"),
            checkFn = { false },
            installFn = {
                val result = packageUpdate(upgrade = false)
                if (result.isError) BootstrapResult.Failed("Package index update failed.", result.output.take(800))
                else BootstrapResult.Success("Package index updated.")
            }
        ),
        BootstrapStep(
            id = "python",
            description = "Install Python",
            dependsOn = listOf("package_index"),
            checkFn = { resolveBinary("python3") != null || resolveBinary("python") != null },
            installFn = toolInstallStep("python", "Python installed.")
        ),
        BootstrapStep(
            id = "node",
            description = "Install Node.js",
            dependsOn = listOf("package_index"),
            checkFn = { resolveBinary("node") != null },
            installFn = toolInstallStep("nodejs", "Node.js installed.")
        ),
        BootstrapStep(
            id = "git",
            description = "Install Git",
            dependsOn = listOf("package_index"),
            checkFn = { resolveBinary("git") != null },
            installFn = toolInstallStep("git", "Git installed.")
        ),
        BootstrapStep(
            id = "base_tools",
            description = "Install curl, wget and core utilities",
            dependsOn = listOf("package_index"),
            checkFn = { resolveBinary("curl") != null && resolveBinary("wget") != null },
            installFn = toolInstallStep("curl wget coreutils", "Base CLI tools installed.")
        )
    )

    private fun toolInstallStep(packages: String, success: String): suspend () -> BootstrapResult = {
        val result = pkgInstall(packages)
        if (result.isError) BootstrapResult.Failed("Install failed for $packages", result.output.take(800))
        else BootstrapResult.Success(success)
    }

    private suspend fun packageUpdate(upgrade: Boolean): ToolExecutionResult = packageMutex.withLock {
        val script = if (upgrade) {
            "export DEBIAN_FRONTEND=noninteractive; pkg update -y && pkg upgrade -y"
        } else {
            "export DEBIAN_FRONTEND=noninteractive; pkg update -y"
        }
        val result = TermuxRunCommandBridge.executeShell(
            script,
            timeoutMs = 10 * 60_000L,
            label = if (upgrade) "OmniDev pkg upgrade" else "OmniDev pkg update"
        )
        if (result.isSuccess) cache.invalidateAll()
        toToolResult(result, if (upgrade) "pkg upgrade" else "pkg update")
    }

    suspend fun statusReport(): ToolExecutionResult {
        val state = probe(force = true)
        val context = appContext
        return ToolExecutionResult(buildString {
            appendLine("╔══ OmniDev Unified Runtime ═════════════════════════════════╗")
            appendLine("║ Phase        : ${state.phase}")
            appendLine("║ Android shell: ${state.privilegeBackend}")
            appendLine("║ Termux       : ${if (state.termuxPrefix != null) "✅ RunCommandService" else "❌ not ready"}")
            appendLine("║ Probe time   : ${state.probeTimeMs}ms")
            appendLine("║")
            if (context != null) {
                TermuxRunCommandBridge.capabilityReport(context).lineSequence().forEach { appendLine("║ $it") }
                appendLine("║")
            }
            appendLine("║ RUNTIMES")
            state.runtimes.entries.sortedBy { it.key }.forEach { (_, runtime) ->
                append("║ ${if (runtime.available) "✅" else "❌"} ${runtime.name.padEnd(9)}")
                append(runtime.path ?: "not found")
                runtime.version?.take(70)?.let { append(" — $it") }
                appendLine()
            }
            if (state.error != null) {
                appendLine("║")
                appendLine("║ SETUP REQUIRED")
                state.error.lineSequence().forEach { appendLine("║ $it") }
            }
            appendLine("╚════════════════════════════════════════════════════════════╝")
            appendLine()
            appendLine("Execution domains:")
            appendLine("  developer shell/packages -> Termux RunCommandService")
            appendLine("  Android privileged shell -> Shizuku UserService")
            appendLine("  ADB-equivalent shell     -> rish")
        }.trimEnd())
    }

    /**
     * The public agent surface is intentionally owned by AgentRuntimeTool.
     * This coordinator remains executable by its legacy aliases but publishes
     * no duplicate tool definitions, preventing the model from choosing between
     * multiple tools that all claim to be "the terminal".
     */
    fun getToolDefinitions(): List<ToolDefinition> = emptyList()

    suspend fun executeTool(
        @Suppress("UNUSED_PARAMETER") name: String,
        arguments: Map<String, String>,
        scopePath: String? = null
    ): ToolExecutionResult {
        val action = arguments["action"]?.trim()?.lowercase()
            ?: return err("Missing required argument 'action'.")
        val cwd = arguments["cwd"]?.takeIf { it.isNotBlank() } ?: scopePath

        return when (action) {
            "status", "env_check" -> statusReport()
            "exec", "script", "shell" -> executeShell(
                arguments["command"] ?: arguments["script"] ?: return err("Missing command/script."),
                cwd
            )
            "python_run", "python" -> runPython(
                arguments["code"] ?: return err("Missing code."),
                arguments["args"], cwd, arguments["venv_path"]
            )
            "npm" -> npmCommand(arguments["command"] ?: return err("Missing npm command."), cwd)
            "pip_install", "pip" -> pipInstall(
                arguments["packages"] ?: return err("Missing packages."),
                arguments["upgrade"]?.equals("true", true) == true,
                arguments["venv_path"]
            )
            "pkg_install", "pkg" -> pkgInstall(arguments["packages"] ?: return err("Missing packages."))
            "pkg_update", "update" -> packageUpdate(upgrade = false)
            "pkg_upgrade", "upgrade" -> packageUpdate(upgrade = true)
            "bootstrap", "setup" -> {
                val results = runBootstrap(buildStandardBootstrapPlan())
                val failed = results.any { it.second is BootstrapResult.Failed }
                ToolExecutionResult(
                    results.joinToString(prefix = "Bootstrap:\n", separator = "\n") { (id, result) ->
                        when (result) {
                            is BootstrapResult.Success -> "✅ $id: ${result.message}"
                            is BootstrapResult.AlreadyDone -> "✔️ $id: ${result.message}"
                            is BootstrapResult.Skipped -> "⏭️ $id: ${result.reason}"
                            is BootstrapResult.Failed -> "❌ $id: ${result.reason}${if (result.hint.isNotBlank()) " — ${result.hint}" else ""}"
                        }
                    },
                    isError = failed
                )
            }
            "find_binary" -> {
                val binary = arguments["binary"] ?: return err("Missing binary.")
                val path = resolveBinary(binary)
                if (path != null) ToolExecutionResult("✅ $binary → $path")
                else err("'$binary' not found.")
            }
            "ensure_tool", "provision_tool", "auto_provision" -> ensureTool(arguments)
            else -> err("Unknown runtime action '$action'.")
        }
    }

    private suspend fun ensureTool(arguments: Map<String, String>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val raw = arguments["tool"] ?: arguments["binary"] ?: arguments["command"]
        val tool = raw?.let(::sanitizeName).orEmpty()
        if (tool.isBlank()) return@withContext err("Missing tool name.")

        resolveBinary(tool)?.let { return@withContext ToolExecutionResult("✅ '$tool' is already available at $it") }

        val explicitPkg = arguments["termux_package"]?.takeIf { it.isNotBlank() }
        val pkg = explicitPkg ?: defaultTermuxPackageFor(tool, arguments["language"])
        if (!pkg.isNullOrBlank() && isTermuxUsable()) {
            val installed = pkgInstall(pkg)
            if (!installed.isError) {
                cache.invalidate(tool)
                resolveBinary(tool)?.let {
                    return@withContext ToolExecutionResult("✅ '$tool' installed via Termux: $it\n${installed.output.take(1000)}")
                }
            }
        }

        arguments["pip_package"]?.takeIf { it.isNotBlank() }?.let { pipPkg ->
            val pip = pipInstall(pipPkg)
            if (!pip.isError) {
                cache.invalidate(tool)
                resolveBinary(tool)?.let { return@withContext ToolExecutionResult("✅ '$tool' installed via pip: $it") }
            }
        }

        arguments["npm_package"]?.takeIf { it.isNotBlank() }?.let { npmPkg ->
            val npm = npmCommand("install -g ${shellQuote(npmPkg)}")
            if (!npm.isError) {
                cache.invalidate(tool)
                resolveBinary(tool)?.let { return@withContext ToolExecutionResult("✅ '$tool' installed via npm: $it") }
            }
        }

        val context = appContext
        if (context != null && tool in ToolDownloaderEngine.KNOWN_TOOLS) {
            val installed = ToolDownloaderEngine.installTool(context, tool)
            if (installed.isSuccess) {
                val path = installed.getOrNull().orEmpty()
                if (path.isNotBlank()) cache.put(tool, path)
                return@withContext ToolExecutionResult("✅ '$tool' installed by OmniDev: $path")
            }
        }

        val fallbackUrl = arguments["fallback_url"]?.takeIf { it.startsWith("https://") }
        if (context != null && fallbackUrl != null) {
            val mainClass = arguments["java_class"].orEmpty()
            if (fallbackUrl.endsWith(".jar") && mainClass.isBlank()) {
                return@withContext err("A JAR fallback requires java_class.")
            }
            val installed = ToolDownloaderEngine.installCustomTool(
                context = context,
                name = tool,
                downloadUrl = fallbackUrl,
                mainClass = mainClass,
                jvmArgs = arguments["jvm_args"] ?: "-Xmx512m"
            )
            if (installed.isSuccess) {
                val path = installed.getOrNull().orEmpty()
                if (path.isNotBlank()) cache.put(tool, path)
                return@withContext ToolExecutionResult("✅ '$tool' installed: $path")
            }
            return@withContext err("Failed to install '$tool': ${installed.exceptionOrNull()?.message}")
        }

        err("Could not provision '$tool'. ${termuxSetupHint()}")
    }

    private suspend fun termuxHealth(): ToolExecutionResult {
        if (appContext == null) return err("EnvironmentSetupManager is not initialized.")
        val result = TermuxRunCommandBridge.executeShell(
            "printf 'TERMUX_OK\\n'; printf 'prefix=%s\\n' \"\$PREFIX\"; id",
            timeoutMs = 20_000L,
            label = "OmniDev Termux health"
        )
        return toToolResult(result, "Termux health")
    }

    private suspend fun detectPrivilegeBackend(): String = try {
        val shizuku = PrivilegedExecutionManager.isShizukuReady()
        val rish = PrivilegedExecutionManager.isRishReady()
        val root = PrivilegedExecutionManager.isRootAvailable()
        when {
            shizuku && rish -> "shizuku-user-service+rish"
            shizuku -> "shizuku-user-service"
            root -> "root"
            rish -> "rish"
            else -> "none"
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Privilege detection failed: ${t.message}")
        "none"
    }

    private fun toToolResult(
        result: TermuxRunCommandBridge.TermuxCommandResult,
        operation: String
    ): ToolExecutionResult {
        val output = result.mergedOutput().trim().take(MAX_OUTPUT)
        return if (result.isSuccess) {
            ToolExecutionResult(output.ifBlank { "(no output)" })
        } else {
            val diagnostic = buildString {
                append("❌ $operation failed")
                if (result.exitCode >= 0) append(" (exit=${result.exitCode})")
                if (result.internalErrorCode != 0) append(" [Termux err=${result.internalErrorCode}]")
                if (output.isNotBlank()) appendLine().append(output)
                if (!result.transportSucceeded) {
                    appendLine()
                    append(termuxSetupHint())
                }
            }
            ToolExecutionResult(diagnostic.take(MAX_OUTPUT), isError = true)
        }
    }

    private fun termuxSetupHint(): String =
        "Termux setup: install official Termux, grant com.termux.permission.RUN_COMMAND to OmniDev, " +
            "then set allow-external-apps=true in ~/.termux/termux.properties and restart Termux."

    private fun looksRecoverablePackageFailure(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("could not get lock") ||
            lower.contains("unable to acquire") ||
            lower.contains("dpkg was interrupted") ||
            lower.contains("unmet dependencies") ||
            lower.contains("temporary failure") ||
            lower.contains("repository") ||
            lower.contains("404") ||
            lower.contains("hash sum mismatch")
    }

    private fun packageArgs(packages: String): String? {
        val tokens = packages.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > 64) return null
        if (tokens.any { token -> token.length > 512 || token.any { it.code < 32 || it == '\u007f' } }) return null
        return tokens.joinToString(" ") { shellQuote(it) }
    }

    private fun defaultTermuxPackageFor(tool: String, language: String?): String? = when (tool.lowercase()) {
        "python", "python3", "pip", "pip3" -> "python"
        "node", "nodejs", "npm", "npx" -> "nodejs"
        "git" -> "git"
        "curl" -> "curl"
        "wget" -> "wget"
        "ruby" -> "ruby"
        "perl" -> "perl"
        "clang", "cc", "gcc", "g++" -> "clang"
        "cmake" -> "cmake"
        "make" -> "make"
        "ninja" -> "ninja"
        "jq" -> "jq"
        "zip", "unzip" -> "zip unzip"
        "openssh", "ssh", "scp", "sftp" -> "openssh"
        "pkg-config", "pkgconf" -> "pkg-config"
        else -> when (language?.lowercase()) {
            "python" -> null
            "node", "javascript", "typescript" -> null
            else -> null
        }
    }

    private fun isValidBinaryPath(path: String?): Boolean =
        !path.isNullOrBlank() && path.startsWith('/') && path.length <= 1024 &&
            path.none { it == '\n' || it == '\r' || it == '\t' }

    private fun sanitizeName(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9_.+\\-]"), "")

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun err(message: String) = ToolExecutionResult(message, isError = true)
}
