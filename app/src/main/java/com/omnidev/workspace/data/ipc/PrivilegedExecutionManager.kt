package com.omnidev.workspace.data.ipc

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.ShizukuResult

/**
 * PrivilegedExecutionManager — unified privileged-execution backend for both the
 * in-process AI agent (via [OmniCoreAgentTool]) and external companion apps
 * (via [OmniCoreService] AIDL).
 *
 * ### Execution backends (tried in order)
 * 1. **Shizuku API** (`Shizuku.newProcess`) — preferred; ADB-level privilege, no full root required.
 * 2. **rish** (`app_process` + `rish_shizuku.dex`) — full ADB shell with piped commands,
 *    subshells, and complete environment. Requires [init] with a [Context].
 * 3. **Root / SU** — fallback when neither Shizuku backend is available.
 *
 * ### Initialisation
 * Call [init] once (e.g., from [com.omnidev.workspace.OmniDevApp.onCreate]) to supply the
 * [Context] needed by [RishShellManager]. Without init, rish is skipped silently.
 *
 * ### Security contract
 * - In-process callers (the agent) invoke this directly after the user approves
 *   the action via [com.omnidev.workspace.ui.chat.ConfirmationGate].
 * - Remote callers reach this only through [OmniCoreService], which enforces the
 *   `com.omnidev.permission.CONTROL_CORE` signature-level permission.
 *
 * ### Threading
 * Every public suspend function switches to [Dispatchers.IO] internally.
 */
object PrivilegedExecutionManager {

    private const val MAX_OUTPUT = 8_000

    // ─────────────────────────────────────────────────────────────────────
    // Context / rish initialisation
    // ─────────────────────────────────────────────────────────────────────

    @Volatile private var rishManager: RishShellManager? = null
    private val rishInitLock = Any()

    /**
     * Initialise the manager with application context.
     * Must be called before using the rish backend.
     * Safe to call multiple times — subsequent calls are no-ops if already initialised.
     */
    fun init(context: Context) {
        if (rishManager == null) {
            synchronized(rishInitLock) {
                if (rishManager == null) {
                    rishManager = RishShellManager(context.applicationContext)
                }
            }
        }
    }

    /** Expose [RishShellManager] for direct rish operations in [OmniCoreAgentTool]. */
    fun getRishManager(): RishShellManager? = rishManager

    // ─────────────────────────────────────────────────────────────────────
    // Backend availability
    // ─────────────────────────────────────────────────────────────────────

    /** Returns true if Shizuku is bound, alive, and the app holds its permission. */
    fun isShizukuReady(): Boolean =
        ShizukuCommandTool.isAvailable() && ShizukuCommandTool.hasPermission()

    /** Returns true if rish is usable on this device (app_process + DEX reachable). */
    fun isRishReady(): Boolean = rishManager?.isAvailable() ?: false

    /** Returns true if an SU binary is reachable on the device. */
    fun isRootAvailable(): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
        p.waitFor() == 0
    }.getOrDefault(false)

    // ─────────────────────────────────────────────────────────────────────
    // Core execution
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Execute [command] with elevated privileges.
     *
     * Tries backends in order:
     * 1. **Shizuku API** — `Shizuku.newProcess()` via [ShizukuCommandTool]
     * 2. **rish** — `app_process` + `rish_shizuku.dex` via [RishShellManager]
     * 3. **root / SU** — `su -c` fallback
     *
     * Returns [Result.success] with trimmed stdout, or [Result.failure] on error.
     */
    suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        if (command.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Command must not be blank."))
        }
        return@withContext when {
            isShizukuReady() -> executeViaShizuku(command)
            isRishReady() -> rishManager!!.execute(command)
            isRootAvailable() -> executeViaRoot(command)
            else -> Result.failure(
                IllegalStateException(
                    "No privileged execution backend available. " +
                    "Shizuku is not running, rish DEX is not found, and root is not accessible."
                )
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Device state
    // ─────────────────────────────────────────────────────────────────────

    /** Returns a [DeviceStateSnapshot] combining OS metadata and backend flags. */
    suspend fun getDeviceState(context: Context): DeviceStateSnapshot =
        withContext(Dispatchers.IO) {
            val foregroundPkg = getForegroundPackage()
            val batteryLevel = getBatteryLevel()
            val totalRamMb = getTotalRamMb()
            DeviceStateSnapshot(
                buildFingerprint = Build.FINGERPRINT,
                sdkInt = Build.VERSION.SDK_INT,
                model = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = Build.VERSION.RELEASE,
                shizukuReady = isShizukuReady(),
                rishAvailable = isRishReady(),
                rootAvailable = isRootAvailable(),
                foregroundPackage = foregroundPkg,
                batteryLevel = batteryLevel,
                totalRamMb = totalRamMb
            )
        }

    /** Read a system property via `getprop <key>`. */
    suspend fun getSystemProperty(key: String): Result<String> =
        executeCommand("getprop ${sanitizeArgument(key)}")

    /** Set a system property via `setprop` (requires root). */
    suspend fun setSystemProperty(key: String, value: String): Result<String> =
        executeCommand("setprop ${sanitizeArgument(key)} ${sanitizeArgument(value)}")

    /**
     * Dump a system service via `dumpsys <service>`.
     * Output is capped at [MAX_OUTPUT] chars.
     */
    suspend fun dumpSysInfo(service: String): Result<String> {
        val safeService = service.replace(Regex("[^a-zA-Z0-9._/\\-]"), "").take(64)
        if (safeService.isEmpty()) return Result.failure(IllegalArgumentException("Invalid service name."))
        return executeCommand("dumpsys $safeService").map { it.take(MAX_OUTPUT) }
    }

    /** List running processes via `ps -A`. */
    suspend fun listRunningProcesses(): Result<String> =
        executeCommand("ps -A").map { it.take(MAX_OUTPUT) }

    // ─────────────────────────────────────────────────────────────────────
    // Android settings
    // ─────────────────────────────────────────────────────────────────────

    /** Read an Android setting via `settings get <namespace> <key>`. */
    suspend fun readSetting(namespace: String, key: String): Result<String> {
        val ns = validateSettingsNamespace(namespace) ?: return Result.failure(
            IllegalArgumentException("Invalid namespace. Use: system, secure, global.")
        )
        val safeKey = key.replace(Regex("[^a-zA-Z0-9_.]"), "")
        if (safeKey.isEmpty()) return Result.failure(IllegalArgumentException("Invalid key."))
        return executeCommand("settings get $ns $safeKey")
    }

    /** Write an Android setting via `settings put <namespace> <key> <value>`. */
    suspend fun writeSetting(namespace: String, key: String, value: String): Result<String> {
        val ns = validateSettingsNamespace(namespace) ?: return Result.failure(
            IllegalArgumentException("Invalid namespace.")
        )
        val safeKey = key.replace(Regex("[^a-zA-Z0-9_.]"), "")
        // Value: block shell metacharacters and newlines to prevent injection
        val safeValue = value.replace(Regex("[;&|`\$\\\\\n\r]"), "")
        if (safeKey.isEmpty()) return Result.failure(IllegalArgumentException("Invalid key."))
        return executeCommand("settings put $ns $safeKey $safeValue")
    }

    /** List all settings in a namespace via `settings list <namespace>`. */
    suspend fun listSettings(namespace: String): Result<String> {
        val ns = validateSettingsNamespace(namespace) ?: return Result.failure(
            IllegalArgumentException("Invalid namespace.")
        )
        return executeCommand("settings list $ns").map { it.take(MAX_OUTPUT) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Package management
    // ─────────────────────────────────────────────────────────────────────

    /** List installed packages, optionally filtered by [filter]. */
    suspend fun queryPackages(filter: String): Result<String> {
        val safeFilter = filter.replace(Regex("[^a-zA-Z0-9._]"), "")
        val cmd = if (safeFilter.isNotEmpty())
            "pm list packages | grep -F $safeFilter"
        else
            "pm list packages"
        return executeCommand(cmd).map { it.take(MAX_OUTPUT) }
    }

    /** Install an APK via `pm install`. */
    suspend fun installApk(apkPath: String): Result<String> {
        val safePath = apkPath.replace(Regex("[^a-zA-Z0-9_.\\-/]"), "")
        if (!safePath.endsWith(".apk")) return Result.failure(
            IllegalArgumentException("Path must end with .apk")
        )
        return executeCommand("pm install -r -g $safePath")
    }

    /** Uninstall a package via `pm uninstall`. */
    suspend fun uninstallPackage(packageName: String): Result<String> {
        val safePkg = sanitizePackageName(packageName) ?: return Result.failure(
            IllegalArgumentException("Invalid package name.")
        )
        return executeCommand("pm uninstall $safePkg")
    }

    /** Grant a permission to a package via `pm grant`. */
    suspend fun grantPermission(packageName: String, permission: String): Result<String> {
        val safePkg = sanitizePackageName(packageName) ?: return Result.failure(
            IllegalArgumentException("Invalid package name.")
        )
        val safePerm = permission.replace(Regex("[^a-zA-Z0-9._]"), "")
        return executeCommand("pm grant $safePkg $safePerm")
    }

    /** Revoke a permission from a package via `pm revoke`. */
    suspend fun revokePermission(packageName: String, permission: String): Result<String> {
        val safePkg = sanitizePackageName(packageName) ?: return Result.failure(
            IllegalArgumentException("Invalid package name.")
        )
        val safePerm = permission.replace(Regex("[^a-zA-Z0-9._]"), "")
        return executeCommand("pm revoke $safePkg $safePerm")
    }

    /** Get detailed package info via `pm dump <packageName>`. */
    suspend fun getPackageInfo(packageName: String): Result<String> {
        val safePkg = sanitizePackageName(packageName) ?: return Result.failure(
            IllegalArgumentException("Invalid package name.")
        )
        return executeCommand("pm dump $safePkg").map { it.take(MAX_OUTPUT) }
    }

    // ─────────────────────────────────────────────────────────────────────
    // App / activity control
    // ─────────────────────────────────────────────────────────────────────

    /** Force-stop an app via `am force-stop`. */
    suspend fun forceStopApp(packageName: String): Result<String> {
        val safePkg = sanitizePackageName(packageName) ?: return Result.failure(
            IllegalArgumentException("Invalid package name.")
        )
        return executeCommand("am force-stop $safePkg")
    }

    /**
     * Launch an activity or component via `am start`.
     * [component] can be a full component name or an action/URI expression.
     */
    suspend fun launchComponent(component: String): Result<String> {
        if (component.isBlank()) return Result.failure(IllegalArgumentException("Component is blank."))
        // Allow typical am-start chars; block shell injection metacharacters including newlines
        val safeComponent = component.replace(Regex("[;&|`\$\\\\\n\r]"), "").take(256)
        return executeCommand("am start $safeComponent")
    }

    /** Send a broadcast via `am broadcast -a <action>`. */
    suspend fun sendBroadcast(action: String): Result<String> {
        val safeAction = action.replace(Regex("[^a-zA-Z0-9._]"), "")
        if (safeAction.isEmpty()) return Result.failure(IllegalArgumentException("Invalid action."))
        return executeCommand("am broadcast -a $safeAction")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Input injection
    // ─────────────────────────────────────────────────────────────────────

    /** Inject a tap gesture at (x, y) via `input tap`. */
    suspend fun injectTap(x: Int, y: Int): Result<String> =
        executeCommand("input tap $x $y")

    /** Inject a swipe gesture via `input swipe`. */
    suspend fun injectSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Result<String> =
        executeCommand("input swipe $x1 $y1 $x2 $y2 $durationMs")

    /** Type text via `input text`. */
    suspend fun injectText(text: String): Result<String> {
        if (text.isBlank()) return Result.failure(IllegalArgumentException("Text is blank."))
        // Use sanitizeArgument for proper POSIX single-quote escaping
        return executeCommand("input text ${sanitizeArgument(text)}")
    }

    /** Send a keycode via `input keyevent`. */
    suspend fun injectKeyEvent(keyCode: Int): Result<String> =
        executeCommand("input keyevent $keyCode")

    // ─────────────────────────────────────────────────────────────────────
    // Screen capture
    // ─────────────────────────────────────────────────────────────────────

    /** Capture the screen to [outputPath] via `screencap -p`. */
    suspend fun captureScreen(outputPath: String): Result<String> {
        val safePath = outputPath.replace(Regex("[^a-zA-Z0-9_.\\-/]"), "")
        if (safePath.isEmpty()) return Result.failure(IllegalArgumentException("Invalid output path."))
        return executeCommand("screencap -p $safePath").map { safePath }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Service / hardware control
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Control a hardware service via `svc <service> enable|disable`.
     * Supported services: wifi, data, bluetooth, nfc, power.
     */
    suspend fun controlService(service: String, action: String): Result<String> {
        val safeService = service.lowercase().replace(Regex("[^a-z]"), "")
        val safeAction = action.lowercase().replace(Regex("[^a-z]"), "")
        if (safeService !in setOf("wifi", "data", "bluetooth", "nfc", "power")) {
            return Result.failure(IllegalArgumentException(
                "Invalid service. Use: wifi, data, bluetooth, nfc, power."
            ))
        }
        if (safeAction !in setOf("enable", "disable")) {
            return Result.failure(IllegalArgumentException("Action must be 'enable' or 'disable'."))
        }
        return executeCommand("svc $safeService $safeAction")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Window manager
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Interact with the window manager via `wm`.
     * @param subCommand  "size", "density", "size reset", or "density reset".
     * @param value       New value for set operations; empty/blank to read.
     */
    suspend fun windowManager(subCommand: String, value: String): Result<String> {
        val safeSub = subCommand.lowercase().replace(Regex("[^a-z ]"), "").trim()
        if (safeSub !in setOf("size", "density", "size reset", "density reset")) {
            return Result.failure(IllegalArgumentException(
                "Invalid wm subcommand. Use: size, density, size reset, density reset."
            ))
        }
        val cmd = if (value.isBlank()) {
            "wm $safeSub"
        } else {
            val safeValue = value.replace(Regex("[^0-9x]"), "")
            "wm $safeSub $safeValue"
        }
        return executeCommand(cmd)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun executeViaShizuku(command: String): Result<String> {
        return when (val result = ShizukuCommandTool.execute(command)) {
            is ShizukuResult.Success -> Result.success(result.output.trim())
            is ShizukuResult.Failure -> Result.failure(RuntimeException(result.reason))
            is ShizukuResult.PermissionRequired ->
                Result.failure(SecurityException(result.message))
            is ShizukuResult.Unavailable ->
                Result.failure(IllegalStateException(result.message))
        }
    }

    private fun executeViaRoot(command: String): Result<String> = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        // Read streams before waitFor() to prevent OS pipe-buffer deadlock.
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit == 0) {
            stdout.trim().ifBlank { "(no output)" }
        } else {
            throw RuntimeException("Root command exited $exit. stderr: $stderr")
        }
    }

    private suspend fun getForegroundPackage(): String = withContext(Dispatchers.IO) {
        runCatching {
            when (val r = ShizukuCommandTool.execute(
                "dumpsys activity activities | grep mResumedActivity | head -1"
            )) {
                is ShizukuResult.Success -> r.output.trim()
                else -> "unknown"
            }
        }.getOrDefault("unknown")
    }

    private suspend fun getBatteryLevel(): Int = withContext(Dispatchers.IO) {
        runCatching {
            when (val r = ShizukuCommandTool.execute(
                "dumpsys battery | grep level | head -1"
            )) {
                is ShizukuResult.Success -> {
                    Regex("level:\\s*(\\d+)").find(r.output)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
                }
                else -> -1
            }
        }.getOrDefault(-1)
    }

    private suspend fun getTotalRamMb(): Long = withContext(Dispatchers.IO) {
        runCatching {
            when (val r = ShizukuCommandTool.execute("cat /proc/meminfo | grep MemTotal | head -1")) {
                is ShizukuResult.Success -> {
                    Regex("(\\d+)").find(r.output)?.groupValues?.getOrNull(1)?.toLongOrNull()
                        ?.let { it / 1024 } ?: -1L
                }
                else -> -1L
            }
        }.getOrDefault(-1L)
    }

    private fun sanitizePackageName(name: String): String? {
        val safe = name.replace(Regex("[^a-zA-Z0-9._]"), "")
        return if (safe.isEmpty()) null else safe
    }

    private fun sanitizeArgument(arg: String): String =
        "'${arg.replace("'", "'\\''")}'"

    private fun validateSettingsNamespace(namespace: String): String? {
        val ns = namespace.lowercase()
        return if (ns in setOf("system", "secure", "global")) ns else null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DeviceStateSnapshot
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Immutable snapshot of device state returned by [PrivilegedExecutionManager.getDeviceState].
 */
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
    /** Serialise to a compact JSON string for transport over the AIDL boundary. */
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
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
