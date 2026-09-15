package com.omnidev.workspace.data.ipc

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.ShizukuResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.min

/**
 * Real rish integration for OmniDev.
 *
 * rish is NOT Shizuku.newProcess(). The official rish launcher starts app_process
 * locally with rish_shizuku.dex and ShizukuShellLoader; that loader connects to
 * Shizuku and asks the remote shell implementation to execute the command.
 *
 * Keeping these two mechanisms separate is critical:
 *  - ShizukuCommandTool -> UserService for programmatic privileged commands.
 *  - RishShellManager   -> official rish protocol for ADB-equivalent shell.
 */
class RishShellManager(private val context: Context) {

    companion object {
        private const val TAG = "RishShellManager"
        private const val DEX_NAME = "rish_shizuku.dex"
        private const val RISH_SCRIPT_NAME = "rish"
        private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        private const val APP_PROCESS = "/system/bin/app_process"
        private const val SHELL_LOADER = "rikka.shizuku.shell.ShizukuShellLoader"
        private const val TIMEOUT_MS = 45_000L
        private const val MAX_STDOUT = 64_000
        private const val MAX_STDERR = 16_000

        private val SHIZUKU_EXPORT_PATHS = listOf(
            "/data/local/tmp/rish_shizuku.dex",
            "/data/user_de/0/moe.shizuku.privileged.api/files/rish_shizuku.dex",
            "/data/user/0/moe.shizuku.privileged.api/files/rish_shizuku.dex"
        )
    }

    private val appContext = context.applicationContext
    private val localDex: File get() = File(appContext.filesDir, DEX_NAME)
    private val localRish: File get() = File(appContext.filesDir, RISH_SCRIPT_NAME)

    init {
        // PrivilegedExecutionManager.init(context) constructs this manager at app start,
        // making it a safe central place to initialise the UserService client as well.
        ShizukuCommandTool.init(appContext)
    }

    fun isAvailable(): Boolean = runCatching {
        File(APP_PROCESS).exists() &&
            ShizukuCommandTool.isAvailable() &&
            ShizukuCommandTool.hasPermission() &&
            (locateDex() != null || canExtractFromShizukuApk())
    }.getOrDefault(false)

    /** Execute exactly like `rish -c <command>`. */
    suspend fun execute(command: String): Result<String> = withContext(Dispatchers.IO) {
        if (command.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("rish command is empty"))
        }
        if (!ShizukuCommandTool.isAvailable()) {
            return@withContext Result.failure(IllegalStateException("Shizuku is not running"))
        }
        if (!ShizukuCommandTool.hasPermission()) {
            return@withContext Result.failure(IllegalStateException("Shizuku permission is not granted"))
        }

        val dex = prepareLocalDex()
            ?: return@withContext Result.failure(
                IllegalStateException(
                    "rish_shizuku.dex is unavailable. Open Shizuku and enable 'Use in terminal apps', " +
                        "or reinstall/update Shizuku so the rish asset can be extracted."
                )
            )

        ensureReadOnlyDex(dex)
        runRishProcess(dex, command)
    }

    /**
     * Multi-line scripts are passed directly to rish's remote `sh -c`.
     * Do not write them under this app's cache directory: shell UID cannot read
     * another app's private sandbox, which was a bug in the old implementation.
     */
    suspend fun executeScript(scriptContent: String): Result<String> = execute(scriptContent)

    /**
     * Prepare a private, read-only copy of the official rish loader DEX.
     * Order: cached copy -> directly readable export -> Shizuku base64 copy -> APK asset.
     */
    suspend fun prepareLocalDex(): File? = withContext(Dispatchers.IO) {
        if (isValidDex(localDex)) {
            ensureReadOnlyDex(localDex)
            return@withContext localDex
        }

        // Directly readable exported dex.
        for (path in SHIZUKU_EXPORT_PATHS) {
            val source = File(path)
            if (!isValidDex(source)) continue
            copyDex(source)?.let { return@withContext it }
        }

        // /data/local/tmp is normally readable by shell even when not by app UID.
        copyDexViaShizuku()?.let { return@withContext it }

        // Most robust fallback: extract the loader shipped inside the installed Shizuku APK.
        extractFromShizukuApk()?.let { return@withContext it }

        null
    }

    fun ensureRishScript(): String {
        val dex = localDex
        if (!isValidDex(dex)) {
            return "rish unavailable: call prepareLocalDex/rish_setup first"
        }
        ensureReadOnlyDex(dex)
        localRish.writeText(buildRishScript(dex.absolutePath))
        localRish.setExecutable(true, true)
        return localRish.absolutePath
    }

    suspend fun statusReport(): String = withContext(Dispatchers.IO) {
        val prepared = prepareLocalDex()
        val smoke = if (prepared != null && ShizukuCommandTool.isAvailable() && ShizukuCommandTool.hasPermission()) {
            execute("id").fold(
                onSuccess = { "✅ ${it.lineSequence().firstOrNull().orEmpty()}" },
                onFailure = { "❌ ${it.message}" }
            )
        } else {
            "⚠️ not executed"
        }

        buildString {
            appendLine("=== OmniDev rish status ===")
            appendLine("Shizuku binder : ${if (ShizukuCommandTool.isAvailable()) "✅" else "❌"}")
            appendLine("Shizuku grant  : ${if (ShizukuCommandTool.hasPermission()) "✅" else "❌"}")
            appendLine("app_process    : ${if (File(APP_PROCESS).exists()) "✅" else "❌"} $APP_PROCESS")
            appendLine("loader class   : $SHELL_LOADER")
            appendLine("rish dex       : ${prepared?.absolutePath ?: "❌ unavailable"}")
            appendLine("dex read-only  : ${if (prepared != null && !prepared.canWrite()) "✅" else "⚠️"}")
            appendLine("Android SDK    : ${Build.VERSION.SDK_INT}")
            appendLine("rish script    : ${if (localRish.exists()) localRish.absolutePath else "not generated"}")
            appendLine("smoke `id`     : $smoke")
            appendLine("overall        : ${if (isAvailable() && prepared != null) "✅ READY" else "❌ NOT READY"}")
        }.trimEnd()
    }

    fun locateDex(): File? {
        if (isValidDex(localDex)) return localDex
        return SHIZUKU_EXPORT_PATHS
            .asSequence()
            .map(::File)
            .firstOrNull(::isValidDex)
    }

    private fun runRishProcess(dex: File, command: String): Result<String> {
        return try {
            val process = ProcessBuilder(
                APP_PROCESS,
                "-Djava.class.path=${dex.absolutePath}",
                "/system/bin",
                "--nice-name=rish",
                SHELL_LOADER,
                "-c",
                command
            ).apply {
                redirectErrorStream(false)
                environment().apply {
                    // The loader uses this when package-name discovery from UID is ambiguous.
                    this["RISH_APPLICATION_ID"] = appContext.packageName
                    // adb-backed Shizuku cannot access Termux private paths; preserve only
                    // the clean Android shell environment here.
                    this["RISH_PRESERVE_ENV"] = "0"
                    this["ANDROID_DATA"] = "/data"
                    this["ANDROID_ROOT"] = "/system"
                    this["PATH"] = "/system/bin:/system/xbin:/vendor/bin:/product/bin:/data/local/tmp"
                    remove("LD_PRELOAD")
                    remove("LD_LIBRARY_PATH")
                    remove("PREFIX")
                    remove("TMPDIR")
                }
            }.start()

            readProcess(process, command)
        } catch (t: Throwable) {
            Result.failure(RuntimeException("rish launch failed: ${t.javaClass.simpleName}: ${t.message}", t))
        }
    }

    private fun readProcess(process: Process, command: String): Result<String> {
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val outThread = drainThread("rish-stdout", process.inputStream.bufferedReader(), stdout, MAX_STDOUT)
        val errThread = drainThread("rish-stderr", process.errorStream.bufferedReader(), stderr, MAX_STDERR)
        outThread.start()
        errThread.start()

        val waiter = Thread({
            try {
                process.waitFor()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }, "rish-waiter").apply {
            isDaemon = true
            start()
        }

        waiter.join(TIMEOUT_MS)
        if (waiter.isAlive) {
            runCatching { process.destroy() }
            waiter.interrupt()
            outThread.interrupt()
            errThread.interrupt()
            return Result.failure(
                RuntimeException("rish timeout exceeded (${TIMEOUT_MS / 1000}s): ${command.take(100)}")
            )
        }

        outThread.join(2_000L)
        errThread.join(2_000L)
        val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
        val out = stdout.toString().trimEnd()
        val err = stderr.toString().trimEnd()
        val merged = when {
            out.isNotBlank() && err.isNotBlank() -> "$out\n[stderr]\n$err"
            out.isNotBlank() -> out
            err.isNotBlank() -> err
            else -> "(no output)"
        }

        return if (exitCode == 0) {
            Result.success(merged)
        } else {
            Result.failure(RuntimeException("rish exit=$exitCode: ${merged.take(8_000)}"))
        }
    }

    private fun drainThread(
        name: String,
        reader: java.io.BufferedReader,
        target: StringBuilder,
        maxChars: Int
    ): Thread = Thread({
        try {
            reader.use { input ->
                val buffer = CharArray(4096)
                while (!Thread.currentThread().isInterrupted) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val remaining = maxChars - target.length
                    if (remaining > 0) target.append(buffer, 0, min(count, remaining))
                }
            }
        } catch (_: Throwable) {
        }
    }, name).apply { isDaemon = true }

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

    @Suppress("DEPRECATION")
    private fun canExtractFromShizukuApk(): Boolean = runCatching {
        val info = appContext.packageManager.getApplicationInfo(SHIZUKU_PKG, 0)
        File(info.sourceDir).exists()
    }.getOrDefault(false)

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
        // Android 14+ app_process rejects writable DEX files. Keeping it read-only
        // on all versions is harmless and removes an entire class of failures.
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

    private fun buildRishScript(dexPath: String): String = """
        #!/system/bin/sh
        DEX=${shellQuote(dexPath)}
        export RISH_APPLICATION_ID=${shellQuote(appContext.packageName)}
        export RISH_PRESERVE_ENV=0
        exec $APP_PROCESS -Djava.class.path="\$DEX" /system/bin --nice-name=rish $SHELL_LOADER "\$@"
    """.trimIndent() + "\n"

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
