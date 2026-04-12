package com.omnidev.workspace.data.ipc

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.ShizukuResult
import java.io.File

/**
 * PrivilegedExecutionManager — The patched and improved version.
 *
 * ### Execution Backends (By Priority)
 * 1. **Shizuku.newProcess()** — `ShizukuCommandTool.execute()` — shell UID
 * 2. **rish via Shizuku** — `RishShellManager.execute()` — full ADB-equivalent
 * 3. **Root/SU** — `executeViaRoot()` — root shell
 *
 * ### Fixes / Improvements
 * 1. Proper handling of `ShizukuResult.PartialSuccess`:
 * - If output is useful (exit != 127) → success
 * - Empty or command not found → fallback
 * 2. Clearer diagnostic messages on failure
 * 3. `getTermuxBootstrapHints()` — Termux installation instructions without Termux
 * 4. `installTermuxViaShizuku()` — Download and install Termux APK via Shizuku
 */
object PrivilegedExecutionManager {

    private const val TAG = "PrivMgr"
    private const val MAX_OUTPUT = 8_000
    private const val JADX_MAIN_CLASS = "jadx.cli.JadxCLI"
    private const val APKTOOL_MAIN_CLASS = "brut.apktool.Main"

    @Volatile private var rishManager: RishShellManager? = null
    @Volatile private var appContext: Context? = null
    private val rishInitLock = Any()

    // ──────────────────────────────────────────────────────────────
    // Initialization
    // ──────────────────────────────────────────────────────────────

    fun init(context: Context) {
        appContext = context.applicationContext
        if (rishManager == null) {
            synchronized(rishInitLock) {
                if (rishManager == null) {
                    rishManager = RishShellManager(context.applicationContext)
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Status
    // ──────────────────────────────────────────────────────────────

    fun getRishManager(): RishShellManager? = rishManager
    fun isShizukuReady(): Boolean =
        ShizukuCommandTool.isAvailable() && ShizukuCommandTool.hasPermission()
    fun isRishReady(): Boolean = rishManager?.isAvailable() ?: false
    fun isRootAvailable(): Boolean = runCatching {
        Runtime.getRuntime().exec(arrayOf("which", "su")).waitFor() == 0
    }.getOrDefault(false)

    // ──────────────────────────────────────────────────────────────
    // executeCommand — The core execution function
    // ──────────────────────────────────────────────────────────────

    /**
     * Executes [command] via the best available backend.
     *
     * ### Fallback Chain
     * Shizuku → rish → Root → Failure (with clear diagnostics)
     */
    suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        if (command.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Command is empty."))
        }
        val preparedCommand = enrichCommandWithOmniToolchain(command)

        // ── 1. Shizuku.newProcess() (The best — shell UID) ──
        if (ShizukuCommandTool.isAvailable()) {
            when (val r = ShizukuCommandTool.execute(preparedCommand)) {
                is ShizukuResult.Success -> {
                    Log.d(TAG, "Shizuku ✅ exit=0: ${command.take(40)}")
                    return@withContext Result.success(r.output.trim().take(MAX_OUTPUT))
                }
                is ShizukuResult.PartialSuccess -> {
                    val output = r.output.trim()
                    if (output.isNotBlank() && output != "(no output)" && r.exitCode != 127) {
                        // Useful output exists even if exit != 0 (e.g., grep, diff, etc.)
                        Log.d(TAG, "Shizuku ✅ exit=${r.exitCode} (partial OK): ${command.take(40)}")
                        return@withContext Result.success(output.take(MAX_OUTPUT))
                    } else {
                        Log.w(TAG, "Shizuku PartialSuccess without output (exit=${r.exitCode}) → fallback: ${command.take(40)}")
                    }
                }
                is ShizukuResult.PermissionRequired ->
                    Log.w(TAG, "Shizuku without permission → fallback: ${r.message}")
                is ShizukuResult.Unavailable ->
                    Log.w(TAG, "Shizuku unavailable → fallback: ${r.message}")
                is ShizukuResult.Failure ->
                    Log.w(TAG, "Shizuku failed → fallback: ${r.reason}")
            }
        } else {
            Log.d(TAG, "Shizuku inactive → try rish/root")
        }

        // ── 2. rish (full ADB-equivalent shell) ──
        if (isRishReady()) {
            Log.d(TAG, "Trying rish: ${command.take(40)}")
            val rishResult = rishManager!!.execute(preparedCommand)
            if (rishResult.isSuccess) {
                val out = rishResult.getOrThrow()
                Log.d(TAG, "rish ✅: ${command.take(40)}")
                return@withContext Result.success(out.take(MAX_OUTPUT))
            }
            Log.w(TAG, "rish failed: ${rishResult.exceptionOrNull()?.message}")
        }

        // ── 3. Root/SU ──
        if (isRootAvailable()) {
            Log.d(TAG, "Trying root: ${command.take(40)}")
            return@withContext executeViaRoot(preparedCommand)
        }

        // ── Failure: Clear diagnostics ──
        Result.failure(
            IllegalStateException(buildFailureMessage())
        )
    }

    /**
     * Builds a diagnostic failure message explaining why execution failed and the solution.
     */
    private fun buildFailureMessage(): String = buildString {
        appendLine("❌ No execution backend available.")
        appendLine()
        val shizukuAvail = ShizukuCommandTool.isAvailable()
        val shizukuPerm  = if (shizukuAvail) ShizukuCommandTool.hasPermission() else false
        appendLine("• Shizuku: ${when {
            !shizukuAvail -> "❌ Unavailable (Shizuku is not running)"
            !shizukuPerm  -> "⚠️ Available but without permission"
            else          -> "⚠️ Available but execution failed"
        }}")
        appendLine("• rish: ${if (isRishReady()) "⚠️ Available but failed" else "❌ Unavailable"}")
        appendLine("• root: ❌ Unavailable")
        appendLine()
        appendLine("Solution:")
        if (!shizukuAvail) {
            appendLine("  1. Open the Shizuku app and start it")
        } else if (!shizukuPerm) {
            appendLine("  1. Open Shizuku → Grant permission to the app")
        }
        appendLine("  2. Run: execution_diagnostics action=fix_shizuku")
    }

    private fun enrichCommandWithOmniToolchain(command: String): String {
        // If init(context) has not run yet, execute the command as-is.
        val ctx = appContext ?: return command
        val baseDir = runCatching { ctx.filesDir.canonicalFile }.getOrNull() ?: return command
        val root = runCatching { File(baseDir, "omnidev_tools").canonicalFile }.getOrNull() ?: return command
        if (!root.path.startsWith(baseDir.path + File.separator)) return command
        if (!root.exists()) return command
        val bin = File(root, "bin").absolutePath
        val customBin = File(root, "custom/bin").absolutePath
        val jadxJar = File(root, "jars/jadx-cli.jar").absolutePath
        val apktoolJar = File(root, "jars/apktool.jar").absolutePath

        val prelude = buildString {
            val qRoot = shellQuote(root.absolutePath)
            val qBin = shellQuote(bin)
            val qCustomBin = shellQuote(customBin)
            val qJadxJar = shellQuote(jadxJar)
            val qApktoolJar = shellQuote(apktoolJar)
            val qJadxMainClass = shellQuote(JADX_MAIN_CLASS)
            val qApktoolMainClass = shellQuote(APKTOOL_MAIN_CLASS)

            append("export OMNIDEV_TOOLS_ROOT=$qRoot; ")
            append("export PATH=$qBin:$qCustomBin:\$PATH; ")
            append("if [ -f $qJadxJar ]; then jadx(){ CLASSPATH=$qJadxJar app_process / $qJadxMainClass \"\$@\"; }; fi; ")
            append("if [ -f $qApktoolJar ]; then apktool(){ CLASSPATH=$qApktoolJar app_process / $qApktoolMainClass \"\$@\"; }; fi; ")
        }

        return "$prelude $command"
    }

    // POSIX-safe single-quote escaping:
    // close quote + escaped single quote + reopen quote => '\'' pattern.
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    // ──────────────────────────────────────────────────────────────
    // System info helpers
    // ──────────────────────────────────────────────────────────────

    suspend fun getDeviceState(context: Context): DeviceStateSnapshot =
        withContext(Dispatchers.IO) {
            val foregroundPkg = getForegroundPackage()
            val batteryLevel  = getBatteryLevel()
            val totalRamMb    = getTotalRamMb()
            DeviceStateSnapshot(
                buildFingerprint  = Build.FINGERPRINT,
                sdkInt            = Build.VERSION.SDK_INT,
                model             = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion    = Build.VERSION.RELEASE,
                shizukuReady      = isShizukuReady(),
                rishAvailable     = isRishReady(),
                rootAvailable     = isRootAvailable(),
                foregroundPackage = foregroundPkg,
                batteryLevel      = batteryLevel,
                totalRamMb        = totalRamMb
            )
        }

    suspend fun getSystemProperty(key: String): Result<String> =
        executeCommand("getprop ${sanitizeArgument(key)}")

    suspend fun setSystemProperty(key: String, value: String): Result<String> =
        executeCommand("setprop ${sanitizeArgument(key)} ${sanitizeArgument(value)}")

    suspend fun dumpSysInfo(service: String): Result<String> {
        val safe = service.replace(Regex("[^a-zA-Z0-9._/\\-]"), "").take(64)
        if (safe.isEmpty()) return Result.failure(IllegalArgumentException("Invalid service name."))
        return executeCommand("dumpsys $safe").map { it.take(MAX_OUTPUT) }
    }

    suspend fun listRunningProcesses(): Result<String> =
        executeCommand("ps -A").map { it.take(MAX_OUTPUT) }

    suspend fun readSetting(namespace: String, key: String): Result<String> {
        val ns = validateSettingsNamespace(namespace)
            ?: return Result.failure(IllegalArgumentException("Invalid namespace. Use: system, secure, global."))
        val safeKey = key.replace(Regex("[^a-zA-Z0-9_.]"), "")
        if (safeKey.isEmpty()) return Result.failure(IllegalArgumentException("Invalid key."))
        return executeCommand("settings get $ns $safeKey")
    }

    suspend fun writeSetting(namespace: String, key: String, value: String): Result<String> {
        val ns = validateSettingsNamespace(namespace)
            ?: return Result.failure(IllegalArgumentException("Invalid namespace."))
        val safeKey   = key.replace(Regex("[^a-zA-Z0-9_.]"), "")
        val safeValue = value.replace(Regex("[;&|`\$\\\\\n\r]"), "")
        if (safeKey.isEmpty()) return Result.failure(IllegalArgumentException("Invalid key."))
        return executeCommand("settings put $ns $safeKey $safeValue")
    }

    suspend fun listSettings(namespace: String): Result<String> {
        val ns = validateSettingsNamespace(namespace)
            ?: return Result.failure(IllegalArgumentException("Invalid namespace."))
        return executeCommand("settings list $ns").map { it.take(MAX_OUTPUT) }
    }

    suspend fun queryPackages(filter: String): Result<String> {
        val safeFilter = filter.replace(Regex("[^a-zA-Z0-9._]"), "")
        val cmd = if (safeFilter.isNotEmpty()) "pm list packages | grep -F $safeFilter"
                  else "pm list packages"
        return executeCommand(cmd).map { it.take(MAX_OUTPUT) }
    }

    suspend fun installApk(apkPath: String): Result<String> {
        val safePath = apkPath.replace(Regex("[^a-zA-Z0-9_.\\-/]"), "")
        if (!safePath.endsWith(".apk")) {
            return Result.failure(IllegalArgumentException("Path must end with .apk"))
        }
        return executeCommand("pm install -r -g $safePath")
    }

    suspend fun uninstallPackage(packageName: String): Result<String> {
        val safePkg = sanitizePackageName(packageName)
            ?: return Result.failure(IllegalArgumentException("Invalid package name."))
        return executeCommand("pm uninstall $safePkg")
    }

    suspend fun grantPermission(packageName: String, permission: String): Result<String> {
        val safePkg  = sanitizePackageName(packageName) ?: return Result.failure(IllegalArgumentException("Invalid package name."))
        val safePerm = permission.replace(Regex("[^a-zA-Z0-9._]"), "")
        return executeCommand("pm grant $safePkg $safePerm")
    }

    suspend fun revokePermission(packageName: String, permission: String): Result<String> {
        val safePkg  = sanitizePackageName(packageName) ?: return Result.failure(IllegalArgumentException("Invalid package name."))
        val safePerm = permission.replace(Regex("[^a-zA-Z0-9._]"), "")
        return executeCommand("pm revoke $safePkg $safePerm")
    }

    suspend fun getPackageInfo(packageName: String): Result<String> {
        val safePkg = sanitizePackageName(packageName) ?: return Result.failure(IllegalArgumentException("Invalid package name."))
        return executeCommand("pm dump $safePkg").map { it.take(MAX_OUTPUT) }
    }

    suspend fun forceStopApp(packageName: String): Result<String> {
        val safePkg = sanitizePackageName(packageName) ?: return Result.failure(IllegalArgumentException("Invalid package name."))
        return executeCommand("am force-stop $safePkg")
    }

    suspend fun launchComponent(component: String): Result<String> {
        if (component.isBlank()) return Result.failure(IllegalArgumentException("Component is empty."))
        val safeComponent = component.replace(Regex("[;&|`\$\\\\\n\r]"), "").take(256)
        return executeCommand("am start $safeComponent")
    }

    suspend fun sendBroadcast(action: String): Result<String> {
        val safeAction = action.replace(Regex("[^a-zA-Z0-9._]"), "")
        if (safeAction.isEmpty()) return Result.failure(IllegalArgumentException("Invalid action."))
        return executeCommand("am broadcast -a $safeAction")
    }

    suspend fun injectTap(x: Int, y: Int): Result<String> =
        executeCommand("input tap $x $y")

    suspend fun injectSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Result<String> =
        executeCommand("input swipe $x1 $y1 $x2 $y2 $durationMs")

    suspend fun injectText(text: String): Result<String> {
        if (text.isBlank()) return Result.failure(IllegalArgumentException("Text is empty."))
        return executeCommand("input text ${sanitizeArgument(text)}")
    }

    suspend fun injectKeyEvent(keyCode: Int): Result<String> =
        executeCommand("input keyevent $keyCode")

    suspend fun captureScreen(outputPath: String): Result<String> {
        val safePath = outputPath.replace(Regex("[^a-zA-Z0-9_.\\-/]"), "")
        if (safePath.isEmpty()) return Result.failure(IllegalArgumentException("Invalid path."))
        return executeCommand("screencap -p $safePath").map { safePath }
    }

    suspend fun controlService(service: String, action: String): Result<String> {
        val safeService = service.lowercase().replace(Regex("[^a-z]"), "")
        val safeAction  = action.lowercase().replace(Regex("[^a-z]"), "")
        if (safeService !in setOf("wifi", "data", "bluetooth", "nfc", "power")) {
            return Result.failure(IllegalArgumentException("Invalid service."))
        }
        if (safeAction !in setOf("enable", "disable")) {
            return Result.failure(IllegalArgumentException("Action must be 'enable' or 'disable'."))
        }
        return executeCommand("svc $safeService $safeAction")
    }

    suspend fun windowManager(subCommand: String, value: String): Result<String> {
        val safeSub = subCommand.lowercase().replace(Regex("[^a-z ]"), "").trim()
        if (safeSub !in setOf("size", "density", "size reset", "density reset")) {
            return Result.failure(IllegalArgumentException("Invalid subcommand."))
        }
        val cmd = if (value.isBlank()) "wm $safeSub"
                  else "wm $safeSub ${value.replace(Regex("[^0-9x]"), "")}"
        return executeCommand(cmd)
    }

    // ──────────────────────────────────────────────────────────────
    // Termux bootstrap (Install Termux without Termux)
    // ──────────────────────────────────────────────────────────────

    /**
     * Generates instructions to install Termux without needing an app store.
     * The agent can execute these steps via Shizuku.
     */
    fun getTermuxBootstrapHints(): String = buildString {
        appendLine("=== Install Termux via Shizuku (Store-free) ===")
        appendLine()
        appendLine("Method 1 — Download APK and install via pm install:")
        appendLine("""  # Download Termux APK from F-Droid""")
        appendLine("""  curl -L "https://f-droid.org/repo/com.termux_118.apk" \\""")
        appendLine("""       -o /data/local/tmp/termux.apk""")
        appendLine("""  # Or wget:""")
        appendLine("""  wget -O /data/local/tmp/termux.apk \\""")
        appendLine("""       "https://f-droid.org/repo/com.termux_118.apk"""")
        appendLine("""  # Install with shell privileges:""")
        appendLine("""  pm install -r -g /data/local/tmp/termux.apk""")
        appendLine()
        appendLine("Method 2 — Use agent_runtime action=tool_bootstrap:")
        appendLine("""  agent_runtime action=tool_bootstrap tool=termux""")
        appendLine()
        appendLine("Method 3 — Manual installation from F-Droid:")
        appendLine("""  https://f-droid.org/en/packages/com.termux/""")
        appendLine()
        appendLine("After installation, run in Termux:")
        appendLine("""  pkg update && pkg upgrade -y""")
        appendLine("""  pkg install -y python git curl wget nodejs""")
    }

    /**
     * Attempts to download and install Termux APK via Shizuku.
     * Requires curl or wget available via Shizuku.
     */
    suspend fun installTermuxViaShizuku(): Result<String> = withContext(Dispatchers.IO) {
        if (!isShizukuReady()) {
            return@withContext Result.failure(IllegalStateException("Shizuku is unavailable."))
        }

        val apkUrl = "https://f-droid.org/repo/com.termux_118.apk"
        val apkPath = "/data/local/tmp/termux_install.apk"
        val sb = StringBuilder()

        // Check for downloader presence
        val hasCurl = executeCommand("which curl 2>/dev/null").getOrNull()?.startsWith("/") == true
        val hasWget = executeCommand("which wget 2>/dev/null").getOrNull()?.startsWith("/") == true

        if (!hasCurl && !hasWget) {
            return@withContext Result.failure(
                IllegalStateException(
                    "No curl or wget found. Use agent_runtime action=tool_bootstrap tool=termux\n" +
                    "or install Termux manually from F-Droid."
                )
            )
        }

        // Download APK
        sb.appendLine("⬇️ Downloading Termux APK...")
        val downloadCmd = if (hasCurl) {
            "curl -L -o '$apkPath' '$apkUrl' 2>&1 && echo DOWNLOAD_OK"
        } else {
            "wget -O '$apkPath' '$apkUrl' 2>&1 && echo DOWNLOAD_OK"
        }

        val downloadResult = executeCommand(downloadCmd)
        if (downloadResult.isFailure || downloadResult.getOrDefault("").contains("DOWNLOAD_OK").not()) {
            return@withContext Result.failure(RuntimeException("Download failed: ${downloadResult.exceptionOrNull()?.message}"))
        }
        sb.appendLine("✅ Downloaded")

        // Install APK
        sb.appendLine("📦 Installing Termux...")
        val installResult = executeCommand("pm install -r -g '$apkPath'")
        val installOut = installResult.getOrNull() ?: installResult.exceptionOrNull()?.message ?: ""
        sb.appendLine(installOut)

        if (installOut.contains("Success", ignoreCase = true)) {
            sb.appendLine("✅ Termux installed successfully!")
            sb.appendLine("Open Termux and run: pkg update && pkg install python git")
            Result.success(sb.toString())
        } else {
            Result.failure(RuntimeException("Installation failed: $installOut"))
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────

    private fun executeViaRoot(command: String): Result<String> = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

        val stdoutBuf = StringBuffer()
        val stderrBuf = StringBuffer()

        val sout = Thread {
            try { process.inputStream.bufferedReader().use { r ->
                r.lineSequence().forEach { if (stdoutBuf.length < MAX_OUTPUT) stdoutBuf.appendLine(it) }
            }} catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        val serr = Thread {
            try { process.errorStream.bufferedReader().use { r ->
                r.lineSequence().forEach { if (stderrBuf.length < 3_000) stderrBuf.appendLine(it) }
            }} catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        val wait = Thread {
            try { process.waitFor() } catch (_: InterruptedException) {}
        }.apply { isDaemon = true; start() }

        wait.join(30_000L)
        if (wait.isAlive) {
            process.destroy(); sout.interrupt(); serr.interrupt()
            throw RuntimeException("Root command exceeded 30s")
        }
        sout.join(2_000L); serr.join(2_000L)

        val stdout = stdoutBuf.toString().trim()
        val stderr = stderrBuf.toString().trim()
        val exit   = process.exitValue()

        val output = when {
            stdout.isNotBlank() && stderr.isNotBlank() -> "$stdout\n[stderr]: $stderr"
            stdout.isNotBlank() -> stdout
            stderr.isNotBlank() -> stderr
            else -> "(no output)"
        }

        if (exit == 0 || stdout.isNotBlank()) output.trim().ifBlank { "(no output)" }
        else throw RuntimeException("Root exited $exit:\n$output")
    }

    private suspend fun getForegroundPackage(): String = withContext(Dispatchers.IO) {
        runCatching {
            when (val r = ShizukuCommandTool.execute(
                "dumpsys activity activities | grep mResumedActivity | head -1"
            )) {
                is ShizukuResult.Success        -> r.output.trim()
                is ShizukuResult.PartialSuccess -> r.output.trim()
                else -> "unknown"
            }
        }.getOrDefault("unknown")
    }

    private suspend fun getBatteryLevel(): Int = withContext(Dispatchers.IO) {
        runCatching {
            when (val r = ShizukuCommandTool.execute("dumpsys battery | grep level | head -1")) {
                is ShizukuResult.Success, is ShizukuResult.PartialSuccess ->
                    Regex("level:\\s*(\\d+)").find(r.toDisplayString())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
                else -> -1
            }
        }.getOrDefault(-1)
    }

    private suspend fun getTotalRamMb(): Long = withContext(Dispatchers.IO) {
        runCatching {
            when (val r = ShizukuCommandTool.execute("cat /proc/meminfo | grep MemTotal | head -1")) {
                is ShizukuResult.Success, is ShizukuResult.PartialSuccess ->
                    Regex("(\\d+)").find(r.toDisplayString())?.groupValues?.getOrNull(1)
                        ?.toLongOrNull()?.let { it / 1024 } ?: -1L
                else -> -1L
            }
        }.getOrDefault(-1L)
    }

    private fun sanitizePackageName(name: String): String? {
        val safe = name.replace(Regex("[^a-zA-Z0-9._]"), "")
        return if (safe.isEmpty()) null else safe
    }

    private fun sanitizeArgument(arg: String): String = "'${arg.replace("'", "'\\''")}'"

    private fun validateSettingsNamespace(namespace: String): String? {
        val ns = namespace.lowercase()
        return if (ns in setOf("system", "secure", "global")) ns else null
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// DeviceStateSnapshot
// ──────────────────────────────────────────────────────────────────────────────
data class DeviceStateSnapshot(
    val buildFingerprint: String,
    val sdkInt: Int,
    val model: String,
    val androidVersion: String,
    val shizukuReady: Boolean,
    val rishAvailable: Boolean,
    val rootAvailable: Boolean,
    val foregroundPackage: String,
    val batteryLevel: Int,
    val totalRamMb: Long
) {
    fun toJson(): String = buildString {
        append("{")
        append("\"buildFingerprint\":\"${buildFingerprint.jsonEscape()}\",")
        append("\"sdkInt\":$sdkInt,")
        append("\"model\":\"${model.jsonEscape()}\",")
        append("\"androidVersion\":\"${androidVersion.jsonEscape()}\",")
        append("\"shizukuReady\":$shizukuReady,")
        append("\"rishAvailable\":$rishAvailable,")
        append("\"rootAvailable\":$rootAvailable,")
        append("\"foregroundPackage\":\"${foregroundPackage.jsonEscape()}\",")
        append("\"batteryLevel\":$batteryLevel,")
        append("\"totalRamMb\":$totalRamMb")
        append("}")
    }

    private fun String.jsonEscape(): String = this
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}
