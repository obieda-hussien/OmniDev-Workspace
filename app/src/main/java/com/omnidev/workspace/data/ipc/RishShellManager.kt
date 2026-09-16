package com.omnidev.workspace.data.ipc

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.ShizukuResult
import com.omnidev.workspace.data.tools.TermuxRunCommandBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * rish integration with an explicit sandbox boundary.
 *
 * Programmatic privileged commands in OmniDev use [ShizukuCommandTool] / UserService.
 * rish is a terminal integration and is installed/executed inside Termux through the
 * official RunCommandService transport. Termux must never be asked to execute files
 * from OmniDev's `/data/user/0/com.omnidev.workspace` private sandbox.
 *
 * READY is deliberately strict: an actual Termux-side `rish -c id` must return shell
 * (uid 2000) or root (uid 0). DEX presence and a live Shizuku binder are prerequisites,
 * not proof that rish works.
 */
class RishShellManager(private val context: Context) {

    companion object {
        private const val TAG = "RishShellManager"
        private const val DEX_NAME = "rish_shizuku.dex"
        private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        private const val APP_PROCESS = "/system/bin/app_process"
        private const val SHELL_LOADER = "rikka.shizuku.shell.ShizukuShellLoader"
        private const val TERMUX_INSTALL_DIR = "\$PREFIX/opt/omnidev-rish"
        private const val TERMUX_RISH = "\$PREFIX/bin/rish"
        private const val PROBE_TIMEOUT_MS = 45_000L

        private val SHIZUKU_EXPORT_PATHS = listOf(
            "/data/local/tmp/rish_shizuku.dex",
            "/data/user_de/0/moe.shizuku.privileged.api/files/rish_shizuku.dex",
            "/data/user/0/moe.shizuku.privileged.api/files/rish_shizuku.dex"
        )
    }

    private val appContext = context.applicationContext
    private val localDex: File get() = File(appContext.filesDir, DEX_NAME)

    @Volatile
    private var lastHealth: RishRuntimeHealth = RishRuntimeHealth(
        state = RishRuntimeHealth.State.UNKNOWN,
        summary = "rish has not been functionally probed yet"
    )

    init {
        ShizukuCommandTool.init(appContext)
        TermuxRunCommandBridge.init(appContext)
    }

    /**
     * Synchronous callers get the last *functional* probe only. We intentionally
     * default to false instead of guessing readiness from files/binder state.
     */
    fun isAvailable(): Boolean = lastHealth.ready

    fun cachedHealth(): RishRuntimeHealth = lastHealth

    /** Execute exactly like Termux `rish -c <command>`. */
    suspend fun execute(command: String): Result<String> = withContext(Dispatchers.IO) {
        if (command.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("rish command is empty"))
        }

        val health = refreshHealth()
        if (!health.ready) {
            return@withContext Result.failure(
                IllegalStateException(
                    "rish is not healthy (${health.state}): ${health.summary}. " +
                        RishFailureClassifier.remediation(health.state)
                )
            )
        }

        val result = TermuxRunCommandBridge.executeShell(
            script = "rish -c ${shellQuote(command)}",
            timeoutMs = PROBE_TIMEOUT_MS,
            label = "OmniDev rish"
        )
        if (result.isSuccess) {
            Result.success(result.mergedOutput().ifBlank { "(no output)" })
        } else {
            val merged = result.mergedOutput()
            val state = RishFailureClassifier.classify(
                exitCode = result.exitCode,
                output = merged,
                transportSucceeded = result.transportSucceeded
            )
            lastHealth = healthForFailure(state, merged)
            Result.failure(
                IllegalStateException(
                    "rish execution failed (${state.name}): ${merged.take(4_000)}\n" +
                        RishFailureClassifier.remediation(state)
                )
            )
        }
    }

    suspend fun executeScript(scriptContent: String): Result<String> = execute(scriptContent)

    /**
     * Installs a valid rish launcher *inside Termux private storage*.
     *
     * We stage only the official loader DEX and official-form launcher script.
     * We intentionally never extract/copy `librish.so`, never set LD_LIBRARY_PATH,
     * and never add -Djava.library.path. Native loading belongs to Shizuku itself.
     */
    suspend fun installIntoTermux(): RishRuntimeHealth = withContext(Dispatchers.IO) {
        if (!ShizukuCommandTool.isAvailable()) {
            return@withContext remember(
                RishRuntimeHealth.State.SHIZUKU_UNAVAILABLE,
                "Shizuku binder is not running"
            )
        }
        if (!ShizukuCommandTool.hasPermission()) {
            return@withContext remember(
                RishRuntimeHealth.State.SHIZUKU_PERMISSION_REQUIRED,
                "OmniDev does not have Shizuku permission"
            )
        }
        if (!TermuxRunCommandBridge.isTermuxInstalled()) {
            return@withContext remember(
                RishRuntimeHealth.State.TERMUX_UNAVAILABLE,
                "Termux is not installed/visible"
            )
        }

        val dex = prepareLocalDex()
            ?: return@withContext remember(
                RishRuntimeHealth.State.DEX_UNAVAILABLE,
                "Could not obtain the official rish_shizuku.dex from Shizuku"
            )

        val dex64 = Base64.encodeToString(dex.readBytes(), Base64.NO_WRAP)
        val script64 = Base64.encodeToString(
            buildTermuxRishScript().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        val installScript = """
set -eu
DEST="$TERMUX_INSTALL_DIR"
BIN="$TERMUX_RISH"
mkdir -p "\$PREFIX/opt"

# Repair the historically broken layout where \$PREFIX/bin/rish became a directory.
if [ -d "\$BIN" ] && [ ! -L "\$BIN" ]; then
  BACKUP="\$PREFIX/opt/omnidev-rish-broken-\$(date +%s)"
  mv "\$BIN" "\$BACKUP"
  printf 'Repaired broken rish directory; backup=%s\n' "\$BACKUP"
fi
rm -f "\$BIN"
mkdir -p "\$DEST"

printf '%s' '${shellLiteral(dex64)}' | base64 -d > "\$DEST/$DEX_NAME"
printf '%s' '${shellLiteral(script64)}' | base64 -d > "\$DEST/rish"
chmod 400 "\$DEST/$DEX_NAME"
chmod 700 "\$DEST/rish"
ln -s "\$DEST/rish" "\$BIN"

# Never install/copy librish.so here. Shizuku owns its native loader.
rm -f "\$PREFIX/lib/librish.so.omnidev" 2>/dev/null || true
hash -r 2>/dev/null || true

printf 'rish_script=%s\n' "\$DEST/rish"
printf 'rish_dex=%s\n' "\$DEST/$DEX_NAME"
printf 'rish_link=%s\n' "\$BIN"
"\$BIN" -c 'id'
""".trimIndent()

        val result = TermuxRunCommandBridge.executeShell(
            script = installScript,
            timeoutMs = 90_000L,
            label = "Install OmniDev rish"
        )
        val merged = result.mergedOutput()
        val state = RishFailureClassifier.classify(
            exitCode = result.exitCode,
            output = merged,
            transportSucceeded = result.transportSucceeded
        )

        if (state == RishRuntimeHealth.State.READY) {
            remember(
                state,
                "Termux rish installed and functional",
                details = "Installed under $TERMUX_INSTALL_DIR; launcher symlink $TERMUX_RISH",
                smokeOutput = merged
            )
        } else {
            remember(
                state,
                "Termux rish installation completed but functional smoke-test failed",
                details = RishFailureClassifier.remediation(state),
                smokeOutput = merged
            )
        }
    }

    /** Probe the real Termux launcher and cache only the real result. */
    suspend fun refreshHealth(): RishRuntimeHealth = withContext(Dispatchers.IO) {
        if (!ShizukuCommandTool.isAvailable()) {
            return@withContext remember(
                RishRuntimeHealth.State.SHIZUKU_UNAVAILABLE,
                "Shizuku binder is not running"
            )
        }
        if (!ShizukuCommandTool.hasPermission()) {
            return@withContext remember(
                RishRuntimeHealth.State.SHIZUKU_PERMISSION_REQUIRED,
                "OmniDev does not have Shizuku permission"
            )
        }
        if (!TermuxRunCommandBridge.isTermuxInstalled()) {
            return@withContext remember(
                RishRuntimeHealth.State.TERMUX_UNAVAILABLE,
                "Termux is not installed/visible"
            )
        }

        val probe = """
if [ -d "\$PREFIX/bin/rish" ] && [ ! -L "\$PREFIX/bin/rish" ]; then
  echo 'OMNIDEV_RISH_LAYOUT_ERROR: $PREFIX/bin/rish is a directory'
  exit 64
fi
if [ ! -x "\$PREFIX/bin/rish" ]; then
  echo 'rish: command not found'
  exit 127
fi
if [ ! -r "\$PREFIX/opt/omnidev-rish/$DEX_NAME" ]; then
  echo 'Cannot find rish_shizuku.dex in OmniDev Termux install'
  exit 65
fi
"\$PREFIX/bin/rish" -c 'id'
""".trimIndent()

        val result = TermuxRunCommandBridge.executeShell(
            script = probe,
            timeoutMs = PROBE_TIMEOUT_MS,
            label = "Probe OmniDev rish"
        )
        val merged = result.mergedOutput()
        val state = when {
            merged.contains("OMNIDEV_RISH_LAYOUT_ERROR") -> RishRuntimeHealth.State.TERMUX_LAYOUT_BROKEN
            else -> RishFailureClassifier.classify(
                exitCode = result.exitCode,
                output = merged,
                transportSucceeded = result.transportSucceeded
            )
        }

        if (state == RishRuntimeHealth.State.READY) {
            remember(state, "rish smoke-test succeeded as shell/root", smokeOutput = merged)
        } else {
            remember(
                state,
                "rish smoke-test failed",
                details = RishFailureClassifier.remediation(state),
                smokeOutput = merged
            )
        }
    }

    suspend fun statusReport(): String = withContext(Dispatchers.IO) {
        val health = refreshHealth()
        val prepared = locateDex() ?: prepareLocalDex()
        val version = shizukuVersionName()

        buildString {
            appendLine("=== OmniDev rish status ===")
            appendLine("Shizuku binder : ${if (ShizukuCommandTool.isAvailable()) "✅" else "❌"}")
            appendLine("Shizuku grant  : ${if (ShizukuCommandTool.hasPermission()) "✅" else "❌"}")
            appendLine("Shizuku version: ${version ?: "unknown"}")
            appendLine("app_process    : ${if (File(APP_PROCESS).exists()) "✅" else "❌"} $APP_PROCESS")
            appendLine("loader class   : $SHELL_LOADER")
            appendLine("source dex     : ${prepared?.absolutePath ?: "❌ unavailable"}")
            appendLine("Android SDK    : ${Build.VERSION.SDK_INT}")
            appendLine("Termux layout  : $TERMUX_INSTALL_DIR + $TERMUX_RISH")
            appendLine("functional state: ${health.state}")
            appendLine("smoke `id`     : ${if (health.ready) "✅" else "❌"} ${health.smokeOutput.take(2_000)}")
            if (health.details.isNotBlank()) appendLine("remediation    : ${health.details}")
            appendLine("overall        : ${if (health.ready) "✅ READY" else "❌ NOT READY"}")
            if (health.state == RishRuntimeHealth.State.NATIVE_LIBRARY_LOAD_FAILURE) {
                appendLine("guardrail      : DO NOT copy librish.so or modify LD_LIBRARY_PATH/java.library.path")
                appendLine("fallback       : OmniDev privileged commands remain available through Shizuku UserService")
            }
        }.trimEnd()
    }

    /**
     * Prepare a private read-only copy only as staging material for Termux install.
     * This app-private DEX is never presented as the terminal executable path.
     */
    suspend fun prepareLocalDex(): File? = withContext(Dispatchers.IO) {
        if (isValidDex(localDex)) {
            ensureReadOnlyDex(localDex)
            return@withContext localDex
        }

        for (path in SHIZUKU_EXPORT_PATHS) {
            val source = File(path)
            if (!isValidDex(source)) continue
            copyDex(source)?.let { return@withContext it }
        }

        copyDexViaShizuku()?.let { return@withContext it }
        extractFromShizukuApk()?.let { return@withContext it }
        null
    }

    fun locateDex(): File? {
        if (isValidDex(localDex)) return localDex
        return SHIZUKU_EXPORT_PATHS
            .asSequence()
            .map(::File)
            .firstOrNull(::isValidDex)
    }

    /** Legacy compatibility: never return an app-private path for Termux execution. */
    @Deprecated("Use installIntoTermux(); app-private rish scripts are not executable from Termux")
    fun ensureRishScript(): String =
        "rish launcher is Termux-owned; call installIntoTermux()/privileged_tool action=rish_setup"

    private fun buildTermuxRishScript(): String = """
#!/system/bin/sh
BASEDIR=\$(dirname "\$0")
DEX="\$BASEDIR/$DEX_NAME"

if [ ! -f "\$DEX" ]; then
  echo "Cannot find \$DEX; re-run OmniDev rish_setup or export rish again from Shizuku"
  exit 1
fi

if [ \$(getprop ro.build.version.sdk) -ge 34 ]; then
  if [ -w "\$DEX" ]; then
    chmod 400 "\$DEX" 2>/dev/null || true
  fi
  if [ -w "\$DEX" ]; then
    echo "On Android 14+, app_process cannot load writable dex."
    echo "Cannot remove the write permission of \$DEX."
    exit 1
  fi
fi

export RISH_APPLICATION_ID="com.termux"
export RISH_PRESERVE_ENV=0
exec $APP_PROCESS -Djava.class.path="\$DEX" /system/bin --nice-name=rish $SHELL_LOADER "\$@"
""".trimIndent() + "\n"

    private fun copyDex(source: File): File? = runCatching {
        prepareDestinationForWrite()
        source.inputStream().use { input ->
            localDex.outputStream().use { output -> input.copyTo(output) }
        }
        if (!isValidDex(localDex)) error("copied rish dex is invalid")
        ensureReadOnlyDex(localDex)
        Log.i(TAG, "Prepared rish dex from ${source.absolutePath} (${localDex.length()} bytes)")
        localDex
    }.onFailure {
        Log.w(TAG, "Cannot copy rish dex from ${source.absolutePath}: ${it.message}")
    }.getOrNull()

    private suspend fun copyDexViaShizuku(): File? {
        for (path in SHIZUKU_EXPORT_PATHS) {
            val result = ShizukuCommandTool.execute(
                "if [ -r ${shellQuote(path)} ]; then base64 ${shellQuote(path)}; else exit 4; fi",
                timeoutMs = 20_000L
            )
            val encoded = when (result) {
                is ShizukuResult.Success -> result.output
                else -> continue
            }
            val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: continue
            if (bytes.size < 1024 || !hasDexMagic(bytes)) continue

            val written = runCatching {
                prepareDestinationForWrite()
                localDex.writeBytes(bytes)
                ensureReadOnlyDex(localDex)
                localDex
            }.getOrNull()
            if (written != null && isValidDex(written)) return written
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun extractFromShizukuApk(): File? = runCatching {
        val info = appContext.packageManager.getApplicationInfo(SHIZUKU_PKG, 0)
        ZipFile(info.sourceDir).use { zip ->
            val entry = findRishDexEntry(zip)
                ?: error("rish_shizuku.dex asset not found in Shizuku APK")
            prepareDestinationForWrite()
            zip.getInputStream(entry).use { input ->
                localDex.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (!isValidDex(localDex)) error("extracted rish dex is invalid")
        ensureReadOnlyDex(localDex)
        Log.i(TAG, "Extracted official rish dex from installed Shizuku APK")
        localDex
    }.onFailure {
        Log.w(TAG, "Failed to extract rish dex from Shizuku APK: ${it.message}")
    }.getOrNull()

    private fun findRishDexEntry(zip: ZipFile): ZipEntry? {
        zip.getEntry("assets/$DEX_NAME")?.let { return it }
        zip.getEntry(DEX_NAME)?.let { return it }
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory && entry.name.endsWith("/$DEX_NAME")) return entry
        }
        return null
    }

    private fun prepareDestinationForWrite() {
        if (localDex.exists()) {
            localDex.setWritable(true, true)
            if (!localDex.delete()) error("cannot replace stale rish dex")
        }
        localDex.parentFile?.mkdirs()
    }

    private fun ensureReadOnlyDex(dex: File) {
        dex.setReadable(true, true)
        dex.setWritable(false, false)
        dex.setExecutable(false, false)
    }

    private fun isValidDex(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 1024) return@runCatching false
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && hasDexMagic(magic)
        }
    }.getOrDefault(false)

    private fun hasDexMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
            bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte()

    @Suppress("DEPRECATION")
    private fun shizukuVersionName(): String? = runCatching {
        appContext.packageManager.getPackageInfo(SHIZUKU_PKG, 0).versionName
    }.getOrNull()

    private fun remember(
        state: RishRuntimeHealth.State,
        summary: String,
        details: String = RishFailureClassifier.remediation(state),
        smokeOutput: String = ""
    ): RishRuntimeHealth {
        return RishRuntimeHealth(
            state = state,
            summary = summary,
            details = details,
            smokeOutput = smokeOutput
        ).also { lastHealth = it }
    }

    private fun healthForFailure(state: RishRuntimeHealth.State, output: String): RishRuntimeHealth =
        remember(
            state = state,
            summary = "rish execution failed",
            details = RishFailureClassifier.remediation(state),
            smokeOutput = output
        )

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /** Base64 only contains a safe alphabet; kept explicit to make shell embedding obvious. */
    private fun shellLiteral(value: String): String = value
}
