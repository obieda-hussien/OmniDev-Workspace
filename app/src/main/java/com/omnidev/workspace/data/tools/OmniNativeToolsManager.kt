package com.omnidev.workspace.data.tools.security

import android.content.Context
import android.util.Log
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.ShizukuResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * OmniNativeToolsManager — Self-contained tool ecosystem for OmniDev Workspace.
 *
 * Downloads, verifies, and executes security research tools entirely within the app's
 * private storage, using Shizuku for privileged execution. Zero Termux dependency.
 *
 * ─── Storage Layout ────────────────────────────────────────────────────────────
 *  <filesDir>/omnidev_tools/
 *    ├── bin/          ← Native ARM64 binaries (aapt2, busybox, python3)
 *    ├── jars/         ← JAR-based tools (jadx-cli.jar, apktool.jar)
 *    ├── python/       ← Python 3.12 runtime + stdlib
 *    ├── scripts/      ← Built-in Python analysis scripts
 *    └── work/         ← Ephemeral working directory per job
 *
 * ─── Execution Strategies ──────────────────────────────────────────────────────
 *  1. Native binaries → chmod +x → execute via Shizuku newProcess()
 *  2. JAR tools       → `CLASSPATH=tool.jar app_process / MainClass args`
 *     (Android's ART runtime runs JARs without a JDK — same trick used by `am`, `pm`)
 *  3. Python scripts  → downloaded Python3 binary with isolated PYTHONHOME
 */
object OmniNativeToolsManager {

    private const val TAG = "OmniNativeTools"
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 120_000
    private const val DEFAULT_EXEC_TIMEOUT_MS = 45_000L
    const val DEFAULT_INSTALL_TIMEOUT_MS = 3_600_000L

    // ─── Tool Registry ────────────────────────────────────────────────────────

    enum class Tool(val displayName: String) {
        PYTHON("Python 3.12"),
        AAPT2("aapt2"),
        JADX("jadx-cli"),
        APKTOOL("apktool"),
        BUSYBOX("busybox"),
        DEX2JAR("dex2jar")
    }

    /**
     * Per-tool specification: download URL, expected filename, and relative executable path.
     * URLs point to ARM64 / aarch64 builds.
     *
     * Each entry has a PRIMARY url and optional FALLBACK url.
     * sha256 = null means skip hash verification (relax for tools fetched over HTTPS).
     */
    private data class ToolSpec(
        val primaryUrl: String,
        val fallbackUrl: String? = null,
        val archiveFilename: String,
        val executableRelPath: String,   // relative to toolsDir; binary or JAR
        val isArchive: Boolean,           // zip / tar.gz
        val archiveInternalPath: String? = null, // path inside archive to the binary
        val sha256: String? = null
    )

    private val TOOL_REGISTRY = mapOf(
        Tool.PYTHON to ToolSpec(
            primaryUrl  = "https://github.com/indygreg/python-build-standalone/releases/download/20241002/cpython-3.12.7+20241002-aarch64-unknown-linux-android-install_only.tar.gz",
            fallbackUrl = "https://github.com/indygreg/python-build-standalone/releases/download/20240814/cpython-3.12.5+20240814-aarch64-unknown-linux-android-install_only.tar.gz",
            archiveFilename     = "python312-android-arm64.tar.gz",
            executableRelPath   = "bin/python3",
            isArchive           = true,
            archiveInternalPath = "python/bin/python3.12"
        ),
        Tool.AAPT2 to ToolSpec(
            // Official Google Maven — ARM64 native binary packaged inside a JAR/ZIP
            primaryUrl  = "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.7.3-11417079/aapt2-8.7.3-11417079-linux-arm64.jar",
            fallbackUrl = "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.3.0-10880808/aapt2-8.3.0-10880808-linux-arm64.jar",
            archiveFilename     = "aapt2-linux-arm64.jar",
            executableRelPath   = "bin/aapt2",
            isArchive           = true,     // JAR is a ZIP; binary is at root: "aapt2"
            archiveInternalPath = "aapt2"
        ),
        Tool.JADX to ToolSpec(
            primaryUrl  = "https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip",
            fallbackUrl = "https://github.com/skylot/jadx/releases/download/v1.4.7/jadx-1.4.7.zip",
            archiveFilename     = "jadx.zip",
            executableRelPath   = "jars/jadx-cli.jar",
            isArchive           = true,
            archiveInternalPath = "lib/jadx-cli.jar"
        ),
        Tool.APKTOOL to ToolSpec(
            primaryUrl = "https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar",
            archiveFilename    = "apktool.jar",
            executableRelPath  = "jars/apktool.jar",
            isArchive          = false
        ),
        Tool.BUSYBOX to ToolSpec(
            primaryUrl  = "https://busybox.net/downloads/binaries/1.35.0-arm64-linux-musl/busybox",
            fallbackUrl = "https://github.com/meefik/busybox/releases/download/1.36.1/busybox-1.36.1-aarch64-linux-musl",
            archiveFilename    = "busybox",
            executableRelPath  = "bin/busybox",
            isArchive          = false
        ),
        Tool.DEX2JAR to ToolSpec(
            primaryUrl = "https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip",
            archiveFilename    = "dex-tools.zip",
            executableRelPath  = "jars/d2j-dex2jar.jar",
            isArchive          = true,
            archiveInternalPath = "dex-tools-v2.4/lib/dex-tools-v2.4.jar"
        )
    )

    // ─── State ────────────────────────────────────────────────────────────────

    private val downloadLock    = Mutex()
    private val installProgress = ConcurrentHashMap<Tool, Int>()  // 0–100
    private var rootDir: File?  = null

    // ─── Initialisation ───────────────────────────────────────────────────────

    fun init(context: Context) {
        rootDir = File(context.filesDir, "omnidev_tools").also { base ->
            listOf("bin", "jars", "python", "scripts", "work", "custom/bin").forEach {
                File(base, it).mkdirs()
            }
        }
        installBuiltinScripts(context)
    }

    private fun root(context: Context): File {
        if (rootDir == null) init(context)
        return rootDir!!
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /** True if the tool binary / JAR exists on disk. */
    fun isInstalled(context: Context, tool: Tool): Boolean {
        val spec = TOOL_REGISTRY[tool] ?: return false
        return File(root(context), spec.executableRelPath).exists()
    }

    /** Installation progress (0–100). -1 = not downloading. */
    fun getProgress(tool: Tool): Int = installProgress[tool] ?: -1

    /** Returns a snapshot of all tools and their installed status. */
    fun status(context: Context): Map<Tool, Boolean> =
        Tool.values().associateWith { isInstalled(context, it) }

    /** Returns total size of installed tools in bytes. */
    fun totalSize(context: Context): Long =
        root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Removes a tool's binary/JAR from disk. */
    fun uninstall(context: Context, tool: Tool) {
        val spec = TOOL_REGISTRY[tool] ?: return
        File(root(context), spec.executableRelPath).delete()
        if (tool == Tool.PYTHON) File(root(context), "python").deleteRecursively()
    }

    /**
     * Ensures the tool is installed. Downloads and installs if missing.
     * Thread-safe — concurrent calls for the same tool block on the download lock.
     *
     * @return Result.success(executable file) or Result.failure(exception)
     */
    suspend fun ensure(
        context: Context,
        tool: Tool,
        installTimeoutMs: Long = DEFAULT_INSTALL_TIMEOUT_MS
    ): Result<File> = withContext(Dispatchers.IO) {
        downloadLock.withLock {
            if (isInstalled(context, tool)) {
                return@withLock Result.success(execFile(context, tool))
            }
            download(context, tool, installTimeoutMs)
        }
    }

    /**
     * Install a custom downloadable binary into omnidev_tools/custom/bin.
     * Expects a direct executable URL (not archive) and marks it executable.
     */
    suspend fun installCustomBinary(
        context: Context,
        name: String,
        downloadUrl: String,
        installTimeoutMs: Long = DEFAULT_INSTALL_TIMEOUT_MS
    ): Result<File> = withContext(Dispatchers.IO) {
        val safeName = normalizeCustomToolName(name)
        if (safeName == null) {
            return@withContext Result.failure(IllegalArgumentException("Custom tool name is invalid"))
        }
        if (!isSupportedCustomToolUrl(downloadUrl)) {
            return@withContext Result.failure(IllegalArgumentException("Custom tool URL must use https://"))
        }

        downloadLock.withLock {
            val dest = File(root(context), "custom/bin/$safeName")
            if (dest.exists()) return@withLock Result.success(dest)

            val temp = File(root(context), "work/tmp_custom_${safeName}_${System.currentTimeMillis()}")
            val installResult = withTimeoutOrNull(installTimeoutMs) {
                try {
                    downloadFile(downloadUrl, temp) { _ -> }
                    if (temp.length() <= 0L) {
                        return@withTimeoutOrNull Result.failure<File>(
                            IllegalStateException("Downloaded file is empty for custom tool '$safeName'")
                        )
                    }
                    if (!isLikelyExecutableDownload(temp)) {
                        return@withTimeoutOrNull Result.failure<File>(
                            IllegalArgumentException(
                                "Custom tool '$safeName' must be a direct executable binary/script (ELF or shebang)"
                            )
                        )
                    }
                    dest.parentFile?.mkdirs()
                    temp.copyTo(dest, overwrite = true)

                    val chmodResult = ShizukuCommandTool.execute(
                        "chmod 755 '${dest.absolutePath}'",
                        timeoutMs = installTimeoutMs
                    )
                    if (chmodResult !is ShizukuResult.Success) {
                        val chmodFallbackOk = dest.setExecutable(true, true)
                        if (!chmodFallbackOk) {
                            return@withTimeoutOrNull Result.failure<File>(
                                IllegalStateException("Failed to mark custom tool '$safeName' as executable")
                            )
                        }
                    }
                    Result.success(dest)
                } catch (e: Exception) {
                    Result.failure<File>(e)
                } finally {
                    temp.delete()
                }
            }
            installResult ?: Result.failure(
                Exception("Custom install timed out after ${formatSeconds(installTimeoutMs)} for $safeName")
            )
        }
    }

    // ─── Execution API ────────────────────────────────────────────────────────

    /**
     * Run an arbitrary shell command via Shizuku with a custom environment.
     */
    suspend fun exec(
        cmd: String,
        timeoutMs: Long = DEFAULT_EXEC_TIMEOUT_MS
    ): ExecResult = withContext(Dispatchers.IO) {
        val res = withTimeoutOrNull(timeoutMs) { ShizukuCommandTool.execute(cmd) }
        when (res) {
            is ShizukuResult.Success       -> ExecResult.Ok(res.output)
            is ShizukuResult.PartialSuccess ->
                ExecResult.Err("Command exited ${res.exitCode}: ${res.output}")
            is ShizukuResult.Failure       -> ExecResult.Err(res.reason)
            null                           -> ExecResult.Err("Timed out after ${timeoutMs}ms")
            else                           -> ExecResult.Err("Shizuku unavailable")
        }
    }

    /**
     * Execute the aapt2 binary with the given arguments.
     * Automatically ensures aapt2 is installed first.
     */
    suspend fun aapt2(
        context: Context,
        vararg args: String,
        timeoutMs: Long = 30_000L
    ): ExecResult = withContext(Dispatchers.IO) {
        val bin = ensure(context, Tool.AAPT2).getOrElse {
            return@withContext ExecResult.Err("aapt2 not available: ${it.message}")
        }
        val escaped = args.joinToString(" ") { "'${it.replace("'", "\\'")}'" }
        exec("'${bin.absolutePath}' $escaped 2>&1", timeoutMs)
    }

    /**
     * Run a Python script.
     * Accepts inline script content or a path to a .py file.
     */
    suspend fun python(
        context: Context,
        scriptContent: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = 60_000L
    ): ExecResult = withContext(Dispatchers.IO) {
        val pythonBin = ensure(context, Tool.PYTHON).getOrElse {
            return@withContext ExecResult.Err("Python not available: ${it.message}")
        }

        val dir   = root(context)
        val pyDir = File(dir, "python")       // runtime root
        val work  = File(dir, "work")

        val scriptFile = File(work, "script_${System.currentTimeMillis()}.py")
        try {
            scriptFile.writeText(scriptContent)
            val argsStr = args.joinToString(" ") { "'${it.replace("'", "\\'")}'" }
            val cmd = buildString {
                append("PYTHONHOME='${pyDir.absolutePath}' ")
                append("PYTHONPATH='${pyDir.absolutePath}/lib/python3.12:" +
                        "${pyDir.absolutePath}/lib/python3.12/lib-dynload' ")
                append("LD_LIBRARY_PATH='${pyDir.absolutePath}/lib:" +
                        "${pyDir.absolutePath}/lib/python3.12/lib-dynload' ")
                append("'${pythonBin.absolutePath}' '${scriptFile.absolutePath}' $argsStr 2>&1")
            }
            exec(cmd, timeoutMs)
        } finally {
            scriptFile.delete()
        }
    }

    /**
     * Run a built-in named Python script (lives in scripts/).
     */
    suspend fun runBuiltinScript(
        context: Context,
        scriptName: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = 60_000L
    ): ExecResult = withContext(Dispatchers.IO) {
        val scriptFile = File(root(context), "scripts/$scriptName")
        if (!scriptFile.exists()) {
            return@withContext ExecResult.Err("Built-in script not found: $scriptName")
        }
        python(context, scriptFile.readText(), args, timeoutMs)
    }

    /**
     * Run a JAR-based tool via Android's `app_process` (uses ART runtime — no JDK needed).
     *
     * This is the same mechanism behind `adb shell am`, `adb shell pm`, etc.
     * Works for JARs that contain valid DEX (classes.dex) alongside or instead of class files.
     *
     * For jadx: mainClass = "jadx.cli.JadxCLI"
     * For apktool: mainClass = "brut.apktool.Main"
     */
    suspend fun runJar(
        context: Context,
        tool: Tool,
        mainClass: String,
        args: List<String>,
        timeoutMs: Long = 90_000L
    ): ExecResult = withContext(Dispatchers.IO) {
        val jar = ensure(context, tool).getOrElse {
            return@withContext ExecResult.Err("${tool.displayName} not available: ${it.message}")
        }
        val argsStr = args.joinToString(" ") { "'${it.replace("'", "\\'")}'" }
        val cmd = "CLASSPATH='${jar.absolutePath}' app_process / $mainClass $argsStr 2>&1"
        exec(cmd, timeoutMs)
    }

    /**
     * Decompress and analyse an APK's binary XML manifest via aapt2.
     */
    suspend fun dumpManifest(context: Context, apkPath: String): ExecResult =
        aapt2(context, "dump", "xmltree", "--file", "AndroidManifest.xml", apkPath)

    /**
     * Dump all resource strings from an APK.
     */
    suspend fun dumpStrings(context: Context, apkPath: String): ExecResult =
        aapt2(context, "dump", "strings", apkPath)

    /**
     * Run jadx to decompile an APK/DEX into Java source.
     */
    suspend fun decompileApk(
        context: Context,
        apkPath: String,
        outputDir: String,
        extraArgs: List<String> = emptyList()
    ): ExecResult {
        val args = buildList {
            add("--output-dir"); add(outputDir)
            add("--show-bad-code")
            add("--no-res")            // skip resources for speed
            addAll(extraArgs)
            add(apkPath)
        }
        return runJar(context, Tool.JADX, "jadx.cli.JadxCLI", args, timeoutMs = 120_000L)
    }

    /**
     * Run apktool to decode an APK's smali + resources.
     */
    suspend fun apktoolDecode(
        context: Context,
        apkPath: String,
        outputDir: String
    ): ExecResult {
        val args = listOf("d", apkPath, "-o", outputDir, "-f", "--no-src")
        return runJar(context, Tool.APKTOOL, "brut.apktool.Main", args, timeoutMs = 90_000L)
    }

    // ─── Download & Install ───────────────────────────────────────────────────

    private suspend fun download(
        context: Context,
        tool: Tool,
        installTimeoutMs: Long
    ): Result<File> = withContext(Dispatchers.IO) {
            val spec = TOOL_REGISTRY[tool]
                ?: return@withContext Result.failure(IllegalArgumentException("No spec for $tool"))
            val dir  = root(context)
            val temp = File(dir, "work/tmp_${tool.name}_${System.currentTimeMillis()}")
            val installResult = withTimeoutOrNull(installTimeoutMs) {
                installProgress[tool] = 0
                try {
                    // 1. Download (try primary, then fallback)
                    val downloaded = tryDownload(spec.primaryUrl, spec.fallbackUrl, temp) { pct ->
                        installProgress[tool] = (pct * 0.7).toInt()  // 0–70%
                    }
                    if (!downloaded) {
                        return@withTimeoutOrNull Result.failure<File>(Exception("Download failed for ${tool.displayName}"))
                    }

                    // 2. Verify hash (if provided)
                    if (spec.sha256 != null && sha256(temp) != spec.sha256) {
                        temp.delete()
                        return@withTimeoutOrNull Result.failure<File>(Exception("SHA-256 mismatch for ${tool.displayName}"))
                    }

                    installProgress[tool] = 75

                    // 3. Extract / install
                    val destFile = install(context, tool, temp, spec, installTimeoutMs)

                    // 4. chmod via Shizuku (handles SELinux context issues on some ROMs)
                    if (spec.executableRelPath.startsWith("bin/")) {
                        val r = ShizukuCommandTool.execute("chmod 755 '${destFile.absolutePath}'")
                        if (r !is ShizukuResult.Success) {
                            // chmod without Shizuku as fallback
                            destFile.setExecutable(true, false)
                        }
                    }

                    installProgress[tool] = 100
                    Log.i(TAG, "✅ ${tool.displayName} installed → ${destFile.absolutePath}")
                    Result.success(destFile)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to install ${tool.displayName}: ${e.message}")
                    Result.failure<File>(e)
                } finally {
                    temp.delete()
                    installProgress.remove(tool)
                }
            }
            installResult ?: Result.failure(
                Exception("Install timed out after ${formatSeconds(installTimeoutMs)} for ${tool.displayName}")
            )
        }

    private fun tryDownload(
        primaryUrl: String,
        fallbackUrl: String?,
        dest: File,
        onProgress: (Float) -> Unit
    ): Boolean {
        return runCatching { downloadFile(primaryUrl, dest, onProgress) }.isSuccess
            || (fallbackUrl != null && runCatching { downloadFile(fallbackUrl, dest, onProgress) }.isSuccess)
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Float) -> Unit) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout    = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OmniDev-Workspace/2.0")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw Exception("HTTP ${conn.responseCode} from $url")
            }
            val total = conn.contentLengthLong.coerceAtLeast(1L)
            var read  = 0L

            dest.parentFile?.mkdirs()
            conn.inputStream.use { ins ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(16_384)
                    var n: Int
                    while (ins.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        read += n
                        onProgress(read.toFloat() / total)
                    }
                }
            }
        } finally {
            conn?.disconnect()
        }
    }

    /** Extracts / copies the downloaded file and returns the final executable File. */
    private suspend fun install(
        context: Context,
        tool: Tool,
        source: File,
        spec: ToolSpec,
        installTimeoutMs: Long
    ): File = withContext(Dispatchers.IO) {
        val dir  = root(context)
        val dest = File(dir, spec.executableRelPath).also { it.parentFile?.mkdirs() }

        if (!spec.isArchive) {
            // Plain binary or JAR — just copy to destination
            source.copyTo(dest, overwrite = true)
            return@withContext dest
        }

        // Archive (zip or tar.gz)
        val archiveName = spec.archiveFilename.lowercase()
        when {
            archiveName.endsWith(".tar.gz") || archiveName.endsWith(".tgz") ->
                extractTarGz(source, dest, spec, dir, tool, installTimeoutMs)
            archiveName.endsWith(".zip") || archiveName.endsWith(".jar") ->
                extractZip(source, dest, spec, dir)
            else -> source.copyTo(dest, overwrite = true)
        }
        dest
    }

    private suspend fun extractTarGz(
        source: File,
        dest: File,
        spec: ToolSpec,
        dir: File,
        tool: Tool,
        installTimeoutMs: Long
    ) = withContext(Dispatchers.IO) {
        // Use Shizuku for tar extraction — avoids Java tar parsing edge cases
        val extractDir = File(dir, "work/extract_${tool.name}")
        extractDir.mkdirs()

        val tarResult = ShizukuCommandTool.execute(
            "tar -xzf '${source.absolutePath}' -C '${extractDir.absolutePath}' 2>&1",
            timeoutMs = installTimeoutMs
        )
        if (tarResult !is ShizukuResult.Success) {
            throw Exception("tar extraction failed: ${tarResult.toDisplayString()}")
        }

        // For Python: the runtime dir is the entire extracted tree
        if (tool == Tool.PYTHON) {
            val runtimeDest = File(dir, "python")
            runtimeDest.deleteRecursively()

            // Find the root of the extracted Python distribution
            val pythonRoot = extractDir.listFiles()?.firstOrNull { it.isDirectory }
                ?: extractDir

            ShizukuCommandTool.execute(
                "cp -r '${pythonRoot.absolutePath}' '${runtimeDest.absolutePath}'",
                timeoutMs = installTimeoutMs
            )

            // Symlink or copy python3.12 → python3
            val py312 = File(runtimeDest, "bin/python3.12")
            val py3   = File(runtimeDest, "bin/python3")
            if (py312.exists() && !py3.exists()) {
                py312.copyTo(py3, overwrite = true)
                py3.setExecutable(true, false)
            }
            dest.parentFile?.mkdirs()
            if (py3.exists()) py3.copyTo(dest, overwrite = true)

        } else {
            // General case: find the specific binary inside the extracted dir
            val internalPath = spec.archiveInternalPath
            val found = if (internalPath != null) {
                File(extractDir, internalPath).takeIf { it.exists() }
                    ?: findFileRecursive(extractDir, File(internalPath).name)
            } else {
                findFileRecursive(extractDir, File(spec.executableRelPath).name)
            }
            found?.copyTo(dest, overwrite = true)
                ?: throw Exception("Could not find target binary inside archive")
        }

        extractDir.deleteRecursively()
    }

    private fun extractZip(
        source: File,
        dest: File,
        spec: ToolSpec,
        dir: File
    ) {
        java.util.zip.ZipFile(source).use { zip ->
            val internalPath = spec.archiveInternalPath
            val targetName   = if (internalPath != null) File(internalPath).name
                               else File(spec.executableRelPath).name

            val entry = if (internalPath != null) {
                zip.getEntry(internalPath) ?: zip.entries().asSequence()
                    .firstOrNull { it.name.endsWith("/$targetName") || it.name == targetName }
            } else {
                zip.entries().asSequence().firstOrNull { it.name.endsWith(targetName) }
            }

            entry?.let { e ->
                dest.parentFile?.mkdirs()
                zip.getInputStream(e).use { ins ->
                    FileOutputStream(dest).use { out -> ins.copyTo(out) }
                }
            } ?: throw Exception("Entry '$internalPath' not found in ZIP")
        }
    }

    private fun findFileRecursive(dir: File, name: String): File? =
        dir.walkTopDown().firstOrNull { it.isFile && it.name == name }

    private fun execFile(context: Context, tool: Tool): File {
        val spec = TOOL_REGISTRY[tool]!!
        return File(root(context), spec.executableRelPath)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            var n: Int
            while (ins.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun normalizeCustomToolName(raw: String): String? {
        val normalized = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9._-]"), "_")
            .take(64)
        if (normalized.isBlank()) return null
        if (normalized.contains("..")) return null
        if (normalized.startsWith(".")) return null
        return normalized
    }

    fun isSupportedCustomToolUrl(url: String): Boolean = url.startsWith("https://")

    private fun isLikelyExecutableDownload(file: File): Boolean {
        file.inputStream().use { ins ->
            val header4 = ByteArray(4)
            val read = ins.read(header4)
            if (read >= 4 &&
                header4[0] == 0x7f.toByte() &&
                header4[1] == 'E'.code.toByte() &&
                header4[2] == 'L'.code.toByte() &&
                header4[3] == 'F'.code.toByte()
            ) {
                return true
            }
            if (read >= 2 && header4[0] == '#'.code.toByte() && header4[1] == '!'.code.toByte()) {
                return true
            }
        }
        return false
    }

    private fun formatSeconds(ms: Long): String {
        val seconds = ms / 1000.0
        val isWholeSeconds = kotlin.math.abs(seconds - seconds.toLong()) < 1e-6
        return if (isWholeSeconds) {
            "${seconds.toLong()}s"
        } else {
            "%.1fs".format(seconds)
        }
    }

    // ─── Built-in Python Scripts ──────────────────────────────────────────────

    /**
     * Write built-in analysis scripts to disk so they can be called quickly
     * without embedding them as long strings at call-site.
     */
    private fun installBuiltinScripts(context: Context) {
        val scriptsDir = File(root(context), "scripts")

        // DEX deep string scanner — advanced pattern matching beyond basic grep
        File(scriptsDir, "dex_scanner.py").writeText(DEX_SCANNER_SCRIPT)

        // Certificate analyzer — parse X.509 certs from APK
        File(scriptsDir, "cert_analyzer.py").writeText(CERT_ANALYZER_SCRIPT)

        // Secret hunter — regex-based secret detection across decompiled source
        File(scriptsDir, "secret_hunter.py").writeText(SECRET_HUNTER_SCRIPT)
    }

    // ─── Status JSON ─────────────────────────────────────────────────────────

    fun statusJson(context: Context): JSONObject {
        val root = JSONObject()
        Tool.values().forEach { tool ->
            val installed = isInstalled(context, tool)
            val obj = JSONObject()
            obj.put("installed", installed)
            if (installed) {
                val f = execFile(context, tool)
                obj.put("size_kb", f.length() / 1024)
                obj.put("path", f.absolutePath)
            }
            val progress = installProgress[tool]
            if (progress != null) obj.put("download_progress", progress)
            root.put(tool.name, obj)
        }
        root.put("total_size_mb", "%.1f".format(totalSize(context) / 1_048_576.0))
        return root
    }

    // ─── ExecResult ──────────────────────────────────────────────────────────

    sealed class ExecResult {
        data class Ok(val output: String) : ExecResult()
        data class Err(val message: String) : ExecResult()

        val isOk: Boolean get() = this is Ok
        fun outputOrNull(): String? = (this as? Ok)?.output
        fun errorOrNull(): String? = (this as? Err)?.message

        fun toToolOutput(): String = when (this) {
            is Ok  -> output
            is Err -> "ERROR: $message"
        }
    }

    // ─── Built-in Script Bodies ───────────────────────────────────────────────

    private val DEX_SCANNER_SCRIPT = """
#!/usr/bin/env python3
\"\"\"
OmniDev DEX Scanner — advanced secret / crypto / URL pattern analysis.
Usage: dex_scanner.py <apk_path> [--json]
\"\"\"
import sys, re, zipfile, json, struct

APK_PATH = sys.argv[1] if len(sys.argv) > 1 else None
JSON_OUT  = "--json" in sys.argv

PATTERNS = {
    "aws_key"        : r'(?<![A-Z0-9])AKIA[0-9A-Z]{16}(?![A-Z0-9])',
    "jwt_token"      : r'eyJ[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+',
    "google_api_key" : r'AIza[0-9A-Za-z\-_]{35}',
    "openai_key"     : r'sk-[a-zA-Z0-9]{20,60}',
    "firebase_url"   : r'https://[a-z0-9-]+\.firebaseio\.com',
    "hardcoded_pw"   : r'(?i)(?:password|passwd|pwd)\s*[:=]\s*["\']([^"\']{6,})["\']',
    "private_ip"     : r'(?:192\.168|10\.[0-9]+|172\.(1[6-9]|2[0-9]|3[01]))\.[0-9]+\.[0-9]+',
    "weak_hash_md5"  : r'\bMD5\b',
    "weak_hash_sha1" : r'\bSHA-?1\b',
    "trust_all_ssl"  : r'(?i)trustAll|X509TrustManager|checkServerTrusted',
    "runtime_exec"   : r'Runtime\.getRuntime\(\)\.exec',
    "world_readable" : r'MODE_WORLD_READABLE|MODE_WORLD_WRITEABLE',
    "http_cleartext" : r'http://(?!localhost|127\.0\.0\.1)[a-zA-Z0-9]',
}

def extract_strings(apk_path):
    strings = []
    try:
        with zipfile.ZipFile(apk_path) as z:
            for name in z.namelist():
                if re.match(r'classes\d*\.dex', name):
                    data = z.read(name)
                    # Extract printable ASCII strings (length >= 5)
                    cur = []
                    for b in data:
                        if 0x20 <= b <= 0x7e:
                            cur.append(chr(b))
                        else:
                            if len(cur) >= 5:
                                strings.append(''.join(cur))
                            cur = []
                    if len(cur) >= 5:
                        strings.append(''.join(cur))
    except Exception as e:
        print(f"ZIP error: {e}", file=sys.stderr)
    return strings

def scan(strings):
    findings = {}
    joined = '\\n'.join(strings)
    for name, pat in PATTERNS.items():
        matches = re.findall(pat, joined)[:5]
        if matches:
            findings[name] = [m[:120] for m in matches]
    return findings

if not APK_PATH:
    print("Usage: dex_scanner.py <apk_path> [--json]")
    sys.exit(1)

strs = extract_strings(APK_PATH)
findings = scan(strs)

if JSON_OUT:
    print(json.dumps({"string_count": len(strs), "findings": findings}, indent=2))
else:
    print(f"Scanned {len(strs):,} strings from {APK_PATH}")
    if not findings:
        print("✅ No high-signal patterns found.")
    else:
        for name, matches in findings.items():
            print(f"\\n⚠️  {name}:")
            for m in matches:
                print(f"   {m}")
""".trimIndent()

    private val CERT_ANALYZER_SCRIPT = """
#!/usr/bin/env python3
\"\"\"
OmniDev Certificate Analyzer — inspect signing certificates in an APK.
Usage: cert_analyzer.py <apk_path>
\"\"\"
import sys, zipfile, hashlib, struct, re, datetime

def parse_cert(der_bytes):
    sha1   = hashlib.sha1(der_bytes).hexdigest().upper()
    sha256 = hashlib.sha256(der_bytes).hexdigest().upper()
    # Heuristic: look for "Android Debug" in subject bytes
    text   = der_bytes.decode('latin-1', errors='replace')
    debug  = 'Android Debug' in text or 'androiddebugkey' in text.lower()
    return sha1, sha256, debug

apk = sys.argv[1] if len(sys.argv) > 1 else None
if not apk:
    print("Usage: cert_analyzer.py <apk_path>"); sys.exit(1)

try:
    with zipfile.ZipFile(apk) as z:
        certs = [n for n in z.namelist() if re.match(r'META-INF/.+\.(RSA|DSA|EC)', n)]
        if not certs:
            print("No signing certificate found (META-INF/*.RSA/DSA/EC missing)")
            sys.exit(0)
        for cname in certs:
            raw   = z.read(cname)
            # DER-encoded cert is after PKCS#7 wrapper — search for SEQUENCE OF
            # (simplified: use last 0x30 82 block as a heuristic)
            m = re.search(b'\\x30\\x82', raw)
            der = raw[m.start():] if m else raw
            sha1, sha256, debug = parse_cert(der)
            print(f"Certificate : {cname}")
            print(f"SHA-1       : {':'.join(sha1[i:i+2] for i in range(0,40,2))}")
            print(f"SHA-256     : {':'.join(sha256[i:i+2] for i in range(0,64,2))}")
            print(f"Debug-signed: {'⚠️  YES — do not ship!' if debug else '✅  No'}")
except Exception as e:
    print(f"Error: {e}", file=sys.stderr)
""".trimIndent()

    private val SECRET_HUNTER_SCRIPT = """
#!/usr/bin/env python3
\"\"\"
OmniDev Secret Hunter — scan decompiled source tree for secrets.
Usage: secret_hunter.py <source_dir> [--json]
\"\"\"
import sys, os, re, json

SRC_DIR  = sys.argv[1] if len(sys.argv) > 1 else "."
JSON_OUT = "--json" in sys.argv

RULES = [
    ("CRITICAL", "AWS Access Key",     r'(?<![A-Z0-9])AKIA[0-9A-Z]{16}(?![A-Z0-9])'),
    ("CRITICAL", "OpenAI API Key",     r'sk-[a-zA-Z0-9]{32,}'),
    ("CRITICAL", "Google API Key",     r'AIza[0-9A-Za-z\\-_]{35}'),
    ("CRITICAL", "JWT Token",          r'eyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+'),
    ("HIGH",     "Hardcoded Password", r'(?i)(?:password|passwd)[\\s]*[:=][\\s]*[\"\\']([^\"\\']{8,})[\"\\']'),
    ("HIGH",     "Hardcoded Secret",   r'(?i)(?:secret|api_key|apikey)[\\s]*[:=][\\s]*[\"\\']([^\"\\']{8,})[\"\\']'),
    ("HIGH",     "Private Key Header", r'-----BEGIN (?:RSA |EC )?PRIVATE KEY-----'),
    ("MEDIUM",   "Firebase URL",       r'https://[a-z0-9-]+\\.firebaseio\\.com'),
    ("MEDIUM",   "Cleartext URL",      r'http://(?!localhost|127\\.)[a-zA-Z0-9][^\"\'\\s]{5,}'),
    ("LOW",      "TODO with secret",   r'(?i)TODO.*(?:password|secret|key|token)'),
]

findings = []
scanned  = 0

for root, _, files in os.walk(SRC_DIR):
    for fname in files:
        if not fname.endswith(('.java', '.kt', '.xml', '.json', '.yaml', '.properties', '.gradle')):
            continue
        fpath = os.path.join(root, fname)
        try:
            text = open(fpath, encoding='utf-8', errors='replace').read()
            scanned += 1
            for severity, name, pat in RULES:
                for m in re.finditer(pat, text):
                    line_no = text[:m.start()].count('\\n') + 1
                    findings.append({
                        "severity" : severity,
                        "rule"     : name,
                        "file"     : os.path.relpath(fpath, SRC_DIR),
                        "line"     : line_no,
                        "match"    : m.group(0)[:100]
                    })
        except Exception:
            pass

if JSON_OUT:
    print(json.dumps({"files_scanned": scanned, "findings": findings}, indent=2))
else:
    print(f"Scanned {scanned} files | {len(findings)} findings")
    for f in sorted(findings, key=lambda x: x['severity']):
        print(f"[{f['severity']}] {f['rule']} @ {f['file']}:{f['line']} → {f['match']}")
""".trimIndent()
}
