package com.omnidev.workspace.data.tools

import android.content.Context
import android.util.Base64
import android.util.Log
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

// ═══════════════════════════════════════════════════════════════════════════════
// TOOL DOWNLOADER ENGINE — v1.0
//
// Solves three root-cause failures:
//
//  1. No download mechanism without curl/wget/Termux:
//     Uses OkHttp (already in dependencies) to download binary files directly
//     from within the app. No external tools needed.
//
//  2. JAR tools need Java:
//     Android has ART/dalvikvm at known paths. We locate it, download the JAR,
//     and create a shell launcher script pointing to both.
//
//  3. File accessibility between app and Shizuku shell:
//     Downloads go to external files dir (/sdcard/Android/data/<pkg>/files/omni_tools/)
//     which the shell UID (2000) can read. Launcher scripts go to
//     /data/local/tmp/omni_tools/ via Shizuku.
//
// Flow:
//   OkHttp → external files dir (JAR/binary)
//     ↓
//   Shizuku → create launcher at /data/local/tmp/omni_tools/<name>
//     ↓
//   Agent tools → execute launcher via Shizuku shell
// ═══════════════════════════════════════════════════════════════════════════════

object ToolDownloaderEngine {

    private const val TAG = "ToolDownloaderEngine"

    // ── Context ────────────────────────────────────────────────────────────────

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ── Constants ──────────────────────────────────────────────────────────────

    /** Where launchers/binaries are installed (writable + executable by shell UID) */
    const val INSTALL_DIR = "/data/local/tmp/omni_tools"

    /** Dalvikvm binary locations, ordered by preference */
    val DALVIKVM_PATHS = listOf(
        "/apex/com.android.art/bin/dalvikvm",
        "/apex/com.android.art/bin/dalvikvm64",
        "/apex/com.android.art/bin/dalvikvm32",
        "/system/bin/dalvikvm",
        "/system/bin/dalvikvm64",
        "/system/bin/dalvikvm32"
    )

    // ── Known Tool Registry ────────────────────────────────────────────────────

    enum class ToolType { JAR, BINARY }

    data class KnownTool(
        val name: String,
        val downloadUrl: String,
        val type: ToolType,
        val mainClass: String = "",
        val jarFileName: String = "",
        val jvmArgs: String = "-Xmx512m -Duser.language=en -Dfile.encoding=UTF-8",
        val verifyArgs: String = "--version",
        val description: String = ""
    )

    val KNOWN_TOOLS: Map<String, KnownTool> = mapOf(

        "apktool" to KnownTool(
            name        = "apktool",
            downloadUrl = "https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar",
            type        = ToolType.JAR,
            mainClass   = "brut.apktool.Main",
            jarFileName = "apktool_2.9.3.jar",
            jvmArgs     = "-Xmx512m -Duser.language=en -Dfile.encoding=UTF-8 -Djava.awt.headless=true",
            verifyArgs  = "version",
            description = "APK reverse engineering — decode/rebuild Android APKs"
        ),

        "jadx" to KnownTool(
            name        = "jadx",
            downloadUrl = "https://github.com/skylot/jadx/releases/download/v1.5.0/jadx-1.5.0-all.jar",
            type        = ToolType.JAR,
            mainClass   = "jadx.cli.JadxCLI",
            jarFileName = "jadx-1.5.0-all.jar",
            jvmArgs     = "-Xmx1g -Dfile.encoding=UTF-8 -Djava.awt.headless=true",
            verifyArgs  = "--version",
            description = "Java/Kotlin decompiler — converts DEX bytecode to readable source"
        ),

        "dex2jar" to KnownTool(
            name        = "dex2jar",
            downloadUrl = "https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip",
            type        = ToolType.BINARY,  // Handled as zip extraction
            jarFileName = "dex-tools-v2.4.zip",
            description = "Converts DEX files to JAR for analysis"
        )
    )

    // ── OkHttp Client ──────────────────────────────────────────────────────────

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)  // Large JARs need time
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Download any file via OkHttp to [destFile].
     * No curl/wget/Termux required — uses Android's network stack directly.
     *
     * @param onProgress Optional callback with (bytesDownloaded, totalBytes).
     *                   totalBytes is -1 when Content-Length is unknown.
     */
    suspend fun downloadFile(
        url: String,
        destFile: File,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "⬇ Downloading: $url")
            Log.i(TAG, "   → ${destFile.absolutePath}")

            destFile.parentFile?.mkdirs()

            val request = Request.Builder()
                .url(url.trim())
                .header("User-Agent", "OmniDev-Workspace/2.0 (Android; dalvikvm)")
                .header("Accept", "*/*")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("HTTP ${response.code}: ${response.message} for $url")
                )
            }

            val body = response.body
                ?: return@withContext Result.failure(Exception("Empty response body from $url"))

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        onProgress?.invoke(downloadedBytes, totalBytes)
                    }
                }
            }

            val sizeMb = "%.1f".format(destFile.length() / 1_048_576.0)
            Log.i(TAG, "✅ Download complete: ${destFile.name} ($sizeMb MB)")
            Result.success(destFile)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Download failed: $url", e)
            if (destFile.exists() && destFile.length() == 0L) destFile.delete()
            Result.failure(e)
        }
    }

    /**
     * Install a known tool by name.
     * Handles download, JAR launcher creation, binary installation.
     *
     * @param context Android context (needed for external files dir)
     * @param toolName Must match a key in [KNOWN_TOOLS]
     * @param onProgress Status messages for the agent
     * @return Result with the absolute path to the installed launcher/binary
     */
    suspend fun installTool(
        context: Context,
        toolName: String,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val spec = KNOWN_TOOLS[toolName.lowercase()]
            ?: return@withContext Result.failure(
                Exception("Unknown tool: '$toolName'. Known tools: ${KNOWN_TOOLS.keys.joinToString()}")
            )
        installToolSpec(context, spec, onProgress)
    }

    /**
     * Install a custom tool by direct URL.
     * For JARs: provide mainClass. For binaries: leave mainClass empty.
     */
    suspend fun installCustomTool(
        context: Context,
        name: String,
        downloadUrl: String,
        mainClass: String = "",
        jvmArgs: String = "-Xmx512m",
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!downloadUrl.startsWith("https://")) {
            return@withContext Result.failure(Exception("Only HTTPS URLs are allowed for security."))
        }

        val sanitizedName = name.replace(Regex("[^a-zA-Z0-9_\\-]"), "")
        if (sanitizedName.isEmpty()) {
            return@withContext Result.failure(Exception("Invalid tool name: '$name'"))
        }

        val type = if (mainClass.isNotBlank() || downloadUrl.endsWith(".jar")) {
            ToolType.JAR
        } else {
            ToolType.BINARY
        }

        val spec = KnownTool(
            name        = sanitizedName,
            downloadUrl = downloadUrl,
            type        = type,
            mainClass   = mainClass,
            jarFileName = downloadUrl.substringAfterLast("/"),
            jvmArgs     = jvmArgs,
            description = "Custom tool"
        )

        installToolSpec(context, spec, onProgress)
    }

    /**
     * Check if a tool is installed and the launcher exists and is executable.
     */
    suspend fun isInstalled(toolName: String): Boolean = withContext(Dispatchers.IO) {
        val path = "$INSTALL_DIR/$toolName"
        // Quick file existence check first
        if (!File(path).exists()) {
            // File might be in Shizuku-only space — verify via shell
            val r = PrivilegedExecutionManager.executeCommand(
                "test -f '$path' && test -x '$path' && echo INSTALLED"
            )
            return@withContext r.getOrDefault("").contains("INSTALLED")
        }
        true
    }

    /**
     * Get the full path to an installed tool, or null if not installed.
     */
    suspend fun getToolPath(toolName: String): String? {
        val path = "$INSTALL_DIR/$toolName"
        return if (isInstalled(toolName)) path else null
    }

    /**
     * List all tools installed in [INSTALL_DIR].
     */
    suspend fun listInstalled(): List<String> = withContext(Dispatchers.IO) {
        val result = PrivilegedExecutionManager.executeCommand(
            "ls '$INSTALL_DIR' 2>/dev/null"
        )
        val output = result.getOrDefault("")
        if (output.isBlank() || output.contains("No such file")) return@withContext emptyList()
        output.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith(".") }
    }

    /**
     * Find the first working dalvikvm binary on this device.
     */
    fun findDalvikvm(): String? =
        DALVIKVM_PATHS.firstOrNull { File(it).exists() }

    /**
     * Generate a comprehensive status report string.
     */
    suspend fun statusReport(context: Context): String = buildString {
        appendLine("╔══ Tool Downloader Engine ═══════════════════════════════════╗")

        val dalvikvm = findDalvikvm()
        appendLine("║ ART/dalvikvm: ${if (dalvikvm != null) "✅ $dalvikvm" else "❌ not found"}")

        val extDir = context.getExternalFilesDir("omni_tools")
        appendLine("║ Download dir: ${extDir?.absolutePath ?: "❌ external storage unavailable"}")
        appendLine("║ Install dir : $INSTALL_DIR")
        appendLine("║")

        appendLine("║ KNOWN TOOLS:")
        KNOWN_TOOLS.forEach { (name, spec) ->
            val installed = isInstalled(name)
            val jarCached = spec.jarFileName.isNotBlank() && run {
                val f = File(extDir, spec.jarFileName)
                f.exists() && f.length() > 0
            }
            val status = when {
                installed -> "✅ installed"
                jarCached -> "📦 JAR cached (launcher missing)"
                else      -> "❌ not installed"
            }
            appendLine("║   $status — $name")
        }

        val customInstalled = listInstalled().filter { it !in KNOWN_TOOLS.keys }
        if (customInstalled.isNotEmpty()) {
            appendLine("║ CUSTOM TOOLS: ${customInstalled.joinToString()}")
        }

        appendLine("╚═══════════════════════════════════════════════════════════════╝")
    }

    // ── Private: Core Installation Logic ──────────────────────────────────────

    private suspend fun installToolSpec(
        context: Context,
        spec: KnownTool,
        onProgress: ((String) -> Unit)?
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Determine download destination
            // External files dir is readable by shell UID (ADB/Shizuku convention)
            val extToolsDir = context.getExternalFilesDir("omni_tools")
                ?: return@withContext Result.failure(
                    Exception("External storage unavailable. Cannot cache JAR files.")
                )
            extToolsDir.mkdirs()

            val jarFileName = spec.jarFileName.ifBlank { "${spec.name}.jar" }
            val destFile = File(extToolsDir, jarFileName)

            // Download if not cached or too small to be valid
            if (!destFile.exists() || destFile.length() < 4096) {
                onProgress?.invoke("⬇ Downloading ${spec.name}…")
                onProgress?.invoke("  URL: ${spec.downloadUrl}")

                val downloadResult = downloadFile(
                    url = spec.downloadUrl,
                    destFile = destFile,
                    onProgress = { dl, total ->
                        if (total > 0) {
                            val pct = (dl * 100 / total).toInt()
                            val mb = "%.1f".format(dl / 1_048_576.0)
                            val totalMb = "%.1f".format(total / 1_048_576.0)
                            onProgress?.invoke("  $pct% — ${mb}MB / ${totalMb}MB")
                        } else {
                            val mb = "%.1f".format(dl / 1_048_576.0)
                            onProgress?.invoke("  ${mb}MB downloaded…")
                        }
                    }
                )

                if (downloadResult.isFailure) {
                    return@withContext Result.failure(downloadResult.exceptionOrNull()!!)
                }

                val sizeMb = "%.1f".format(destFile.length() / 1_048_576.0)
                onProgress?.invoke("✅ Download complete: ${destFile.name} ($sizeMb MB)")
            } else {
                val sizeMb = "%.1f".format(destFile.length() / 1_048_576.0)
                onProgress?.invoke("📦 Using cached download: ${destFile.name} ($sizeMb MB)")
            }

            // Now install
            return@withContext when (spec.type) {
                ToolType.JAR    -> createJarLauncher(spec, destFile, onProgress)
                ToolType.BINARY -> installBinary(spec, destFile, onProgress)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Installation failed for ${spec.name}", e)
            Result.failure(e)
        }
    }

    /**
     * Creates a shell script launcher that invokes dalvikvm with the JAR.
     *
     * Uses base64 injection — NOT heredoc (<<'EOF') — because /bin/sh on Android
     * does NOT support heredoc syntax and it silently fails.
     *
     * Layout:
     *   /sdcard/Android/data/<pkg>/files/omni_tools/<name>.jar  ← JAR (written by app)
     *   /data/local/tmp/omni_tools/<name>                        ← launcher (by Shizuku)
     */
    private suspend fun createJarLauncher(
        spec: KnownTool,
        jarFile: File,
        onProgress: ((String) -> Unit)?
    ): Result<String> = withContext(Dispatchers.IO) {
        val dalvikvm = findDalvikvm()
        if (dalvikvm == null) {
            return@withContext Result.failure(
                Exception(
                    "ART/dalvikvm not found. Checked: ${DALVIKVM_PATHS.joinToString()}\n" +
                    "Cannot run JAR files without Java runtime."
                )
            )
        }

        onProgress?.invoke("🔧 Creating launcher using: $dalvikvm")

        val launcherPath = "$INSTALL_DIR/${spec.name}"
        val jarPath = jarFile.absolutePath

        // Build the launcher script content
        // IMPORTANT: We use POSIX sh — no bash-isms allowed
        val launcherContent = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# OmniDev launcher — ${spec.name}")
            appendLine("# JAR: $jarPath")
            appendLine("# Created by ToolDownloaderEngine")
            appendLine("exec '$dalvikvm' \\")
            appendLine("  ${spec.jvmArgs} \\")
            appendLine("  -classpath '$jarPath' \\")
            appendLine("  '${spec.mainClass}' \\")
            appendLine("  \"\$@\"")
        }

        // Encode launcher as base64 and write via Shizuku
        // This bypasses all quoting/heredoc issues in /bin/sh
        val b64 = Base64.encodeToString(
            launcherContent.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        // Single compound command: mkdir + write via base64 + chmod
        val installCmd = buildString {
            append("mkdir -p '$INSTALL_DIR'")
            append(" && printf '%s' '$b64' | base64 -d > '$launcherPath'")
            append(" && chmod 755 '$launcherPath'")
            append(" && echo 'LAUNCHER_CREATED'")
        }

        onProgress?.invoke("🔧 Writing launcher to: $launcherPath")
        val writeResult = PrivilegedExecutionManager.executeCommand(installCmd)

        val writeOutput = writeResult.getOrDefault("")
        if (!writeOutput.contains("LAUNCHER_CREATED")) {
            val error = writeResult.exceptionOrNull()?.message ?: "No LAUNCHER_CREATED marker"
            return@withContext Result.failure(
                Exception("Failed to create launcher at $launcherPath: $error\nOutput: $writeOutput")
            )
        }

        // Verify the launcher is executable
        onProgress?.invoke("🔍 Verifying launcher…")
        val verifyResult = PrivilegedExecutionManager.executeCommand(
            "'$launcherPath' ${spec.verifyArgs} 2>&1 | head -5"
        )
        val verifyOutput = verifyResult.getOrDefault("").trim()
        onProgress?.invoke("✅ ${spec.name} installed → $launcherPath")
        if (verifyOutput.isNotBlank()) {
            onProgress?.invoke("   Version: ${verifyOutput.lines().first().take(100)}")
        }

        Log.i(TAG, "✅ Installed ${spec.name} → $launcherPath (dalvikvm: $dalvikvm)")
        Result.success(launcherPath)
    }

    /**
     * For binary tools: copy to install dir and chmod +x.
     */
    private suspend fun installBinary(
        spec: KnownTool,
        binaryFile: File,
        onProgress: ((String) -> Unit)?
    ): Result<String> = withContext(Dispatchers.IO) {
        val destPath = "$INSTALL_DIR/${spec.name}"
        val srcPath = binaryFile.absolutePath

        onProgress?.invoke("🔧 Installing binary: ${spec.name}")

        val installCmd = buildString {
            append("mkdir -p '$INSTALL_DIR'")
            append(" && cp '$srcPath' '$destPath'")
            append(" && chmod 755 '$destPath'")
            append(" && echo 'BINARY_INSTALLED'")
        }

        val result = PrivilegedExecutionManager.executeCommand(installCmd)
        val output = result.getOrDefault("")

        return@withContext if (output.contains("BINARY_INSTALLED")) {
            onProgress?.invoke("✅ ${spec.name} installed → $destPath")
            Result.success(destPath)
        } else {
            val error = result.exceptionOrNull()?.message ?: "Missing BINARY_INSTALLED marker"
            Result.failure(Exception("Binary installation failed: $error\nOutput: $output"))
        }
    }

    // ── Reinstall launcher (JAR already cached) ────────────────────────────────

    /**
     * Recreate launcher for a tool whose JAR is already cached locally.
     * Useful when the launcher was deleted or dalvikvm path changed.
     */
    suspend fun reinstallLauncher(
        context: Context,
        toolName: String,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val spec = KNOWN_TOOLS[toolName.lowercase()]
            ?: return@withContext Result.failure(Exception("Unknown tool: $toolName"))

        val extDir = context.getExternalFilesDir("omni_tools")
            ?: return@withContext Result.failure(Exception("External storage unavailable"))

        val jarFile = File(extDir, spec.jarFileName.ifBlank { "$toolName.jar" })
        if (!jarFile.exists() || jarFile.length() < 4096) {
            return@withContext Result.failure(
                Exception("JAR not cached. Run install_tool $toolName to download first.")
            )
        }

        onProgress?.invoke("📦 JAR found: ${jarFile.name} (${jarFile.length() / 1024}KB)")
        createJarLauncher(spec, jarFile, onProgress)
    }

    // ── Tool Definitions for CompositeToolManager ─────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "install_tool",
            description = """
Install a security/research tool using OmniDev's built-in downloader.
No curl, wget, or Termux required — uses Android's HTTP stack directly.
Tools are downloaded once and cached; launcher recreated if missing.

Known tools (use action=install):
  apktool  — APK decode/rebuild (dalvikvm + JAR, ~24MB)
  jadx     — APK decompiler to Java source (dalvikvm + JAR, ~50MB)

Actions:
  install         — Download + install a known or custom tool
  reinstall_launcher — Recreate launcher for already-cached JAR (fast)
  status          — Show installer status and cached tools
  list            — List installed tools in $INSTALL_DIR
  verify          — Verify a tool is working

For custom tools: provide download_url (HTTPS). If it's a JAR, also provide main_class.
            """.trimIndent(),
            parameters = listOf(
                ToolParameter("action",       "string", "install | reinstall_launcher | status | list | verify", required = true),
                ToolParameter("tool_name",    "string", "Known tool name (apktool, jadx) or custom name", required = false),
                ToolParameter("download_url", "string", "Direct HTTPS URL to JAR or binary (for custom tools)", required = false),
                ToolParameter("main_class",   "string", "Java main class (for custom JAR tools)", required = false),
                ToolParameter("jvm_args",     "string", "Custom JVM args (default: -Xmx512m)", required = false)
            )
        ),
        ToolDefinition(
            name = "tools_status",
            description = "Show runtime/tools status report including ART/dalvikvm path, cached JARs, and installed tools.",
            parameters = emptyList()
        )
    )

    suspend fun executeTool(
        context: Context,
        action: String,
        args: Map<String, String>
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        when (action.lowercase().trim()) {

            "install" -> {
                val toolName = args["tool_name"]?.trim()
                    ?: return@withContext ToolExecutionResult(
                        "Missing 'tool_name'. Known tools: ${KNOWN_TOOLS.keys.joinToString()}", isError = true
                    )

                val log = StringBuilder()
                val progressFn: (String) -> Unit = { msg -> log.appendLine(msg) }

                val result = if (toolName.lowercase() in KNOWN_TOOLS) {
                    installTool(context, toolName, progressFn)
                } else {
                    val url = args["download_url"]
                        ?: return@withContext ToolExecutionResult(
                            "Unknown tool '$toolName'. Provide 'download_url' for custom tools.", isError = true
                        )
                    installCustomTool(
                        context     = context,
                        name        = toolName,
                        downloadUrl = url,
                        mainClass   = args["main_class"] ?: "",
                        jvmArgs     = args["jvm_args"] ?: "-Xmx512m",
                        onProgress  = progressFn
                    )
                }

                val output = log.toString().trimEnd()
                if (result.isSuccess) {
                    ToolExecutionResult("$output\n\nInstalled → ${result.getOrNull()}")
                } else {
                    ToolExecutionResult(
                        "$output\n\n❌ Install failed: ${result.exceptionOrNull()?.message}",
                        isError = true
                    )
                }
            }

            "reinstall_launcher" -> {
                val toolName = args["tool_name"]
                    ?: return@withContext ToolExecutionResult("Missing 'tool_name'", isError = true)
                val log = StringBuilder()
                val result = reinstallLauncher(context, toolName) { log.appendLine(it) }
                val output = log.toString().trimEnd()
                if (result.isSuccess) {
                    ToolExecutionResult("$output\n\nLauncher → ${result.getOrNull()}")
                } else {
                    ToolExecutionResult("$output\n\n❌ ${result.exceptionOrNull()?.message}", isError = true)
                }
            }

            "status" -> ToolExecutionResult(statusReport(context))

            "list" -> {
                val installed = listInstalled()
                if (installed.isEmpty()) {
                    ToolExecutionResult("No tools installed in $INSTALL_DIR.\nUse action=install tool_name=apktool to get started.")
                } else {
                    ToolExecutionResult("Installed tools in $INSTALL_DIR:\n${installed.joinToString("\n") { "  • $it" }}")
                }
            }

            "verify" -> {
                val toolName = args["tool_name"]
                    ?: return@withContext ToolExecutionResult("Missing 'tool_name'", isError = true)
                val path = "$INSTALL_DIR/$toolName"
                val spec = KNOWN_TOOLS[toolName.lowercase()]
                val verifyArgs = spec?.verifyArgs ?: "--version"
                val result = PrivilegedExecutionManager.executeCommand(
                    "'$path' $verifyArgs 2>&1 | head -10"
                )
                val output = result.getOrDefault("").trim()
                if (output.isBlank() || result.isFailure) {
                    ToolExecutionResult(
                        "❌ $toolName not working at $path\n${result.exceptionOrNull()?.message}",
                        isError = true
                    )
                } else {
                    ToolExecutionResult("✅ $toolName is working:\n$output")
                }
            }

            else -> ToolExecutionResult(
                "Unknown action: '$action'. Use: install, reinstall_launcher, status, list, verify",
                isError = true
            )
        }
    }

    // ── Legacy shim API (used by AgentRuntimeTool and CompositeToolManager) ───

    /**
     * Context-free install_tool entry point used by [AgentRuntimeTool].
     * Delegates to [EnvironmentSetupManager.executeTool] (ensure_tool action)
     * or [executeTool] if context is available.
     */
    suspend fun installTool(args: Map<String, String>): ToolExecutionResult {
        val ctx = appContext
        val toolName = args["tool"]?.trim() ?: args["tool_name"]?.trim().orEmpty()
        if (toolName.isBlank()) return ToolExecutionResult("install_tool requires 'tool'.", isError = true)

        // If context available and it's a known ToolDownloaderEngine tool, use full engine
        if (ctx != null && toolName.lowercase() in KNOWN_TOOLS) {
            val log = StringBuilder()
            val result = installTool(ctx, toolName) { log.appendLine(it) }
            val output = log.toString().trimEnd()
            return if (result.isSuccess) {
                ToolExecutionResult("$output\n\n✅ Installed → ${result.getOrNull()}")
            } else {
                // Fall through to EnvironmentSetupManager
                val payload = buildMap {
                    put("action", "ensure_tool")
                    put("tool", toolName)
                    args["language"]?.let { put("language", it) }
                    args["termux_package"]?.let { put("termux_package", it) }
                    args["pip_package"]?.let { put("pip_package", it) }
                    args["npm_package"]?.let { put("npm_package", it) }
                    args["fallback_url"]?.let { put("fallback_url", it) }
                    args["java_class"]?.let { put("java_class", it) }
                    args["verify_command"]?.let { put("verify_command", it) }
                }
                EnvironmentSetupManager.executeTool("advanced_terminal", payload)
            }
        }

        val payload = buildMap {
            put("action", "ensure_tool")
            put("tool", toolName)
            args["language"]?.let { put("language", it) }
            args["termux_package"]?.let { put("termux_package", it) }
            args["pip_package"]?.let { put("pip_package", it) }
            args["npm_package"]?.let { put("npm_package", it) }
            args["fallback_url"]?.let { put("fallback_url", it) }
            args["java_class"]?.let { put("java_class", it) }
            args["verify_command"]?.let { put("verify_command", it) }
        }
        return EnvironmentSetupManager.executeTool("advanced_terminal", payload)
    }

    /**
     * Context-free tools_status used by [AgentRuntimeTool].
     */
    suspend fun toolsStatus(): ToolExecutionResult {
        val ctx = appContext
        return if (ctx != null) {
            ToolExecutionResult(statusReport(ctx))
        } else {
            EnvironmentSetupManager.statusReport()
        }
    }

    /**
     * Context-aware execute() used by [CompositeToolManager].
     */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult {
        val ctx = appContext
        return when (name) {
            "install_tool" -> {
                if (ctx != null) {
                    val action = args["action"] ?: "install"
                    executeTool(ctx, action, args)
                } else {
                    installTool(args)
                }
            }
            "tools_status" -> toolsStatus()
            else -> ToolExecutionResult("Unknown tool downloader action: $name", isError = true)
        }
    }

    // ── Download helpers (used by AgentRuntimeTool) ────────────────────────────

    /**
     * Download a file via OkHttp (if context available) or shell curl/wget fallback.
     */
    suspend fun downloadFile(url: String, destPath: String?): ToolExecutionResult {
        val ctx = appContext
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolExecutionResult("Invalid URL: '$url'", isError = true)
        }
        val cleanUrl = url.substringBefore("?")
        val filename = cleanUrl.substringAfterLast("/")
            .replace(Regex("[^a-zA-Z0-9_.\\-]"), "")
            .ifBlank { "download_${System.currentTimeMillis()}" }
        val dest = destPath?.takeIf { !it.contains("..") } ?: "/data/local/tmp/$filename"

        val log = StringBuilder()

        // Path A: OkHttp via app context (no curl/wget required)
        if (ctx != null) {
            log.appendLine("⬇ Downloading via OkHttp: $url")
            log.appendLine("   → $dest")
            val extDir = ctx.getExternalFilesDir("downloads") ?: ctx.cacheDir
            extDir.mkdirs()
            val tmpFile = File(extDir, filename)

            val result = downloadFile(
                url = url,
                destFile = tmpFile,
                onProgress = { dl, total ->
                    if (total > 0 && dl % (1024 * 1024) < 8192) {
                        val pct = (dl * 100 / total).toInt()
                        log.appendLine("   $pct% — ${dl / 1024}KB / ${total / 1024}KB")
                    }
                }
            )

            if (result.isSuccess) {
                val sizeMb = "%.2f".format(tmpFile.length() / 1_048_576.0)
                log.appendLine("✅ Downloaded: $filename ($sizeMb MB)")
                val copyResult = EnvironmentSetupManager.executeShell(
                    "mkdir -p \$(dirname '${dest}') && cp '${tmpFile.absolutePath}' '$dest' && chmod 644 '$dest' && echo 'COPY_OK'",
                    useBase64 = false
                )
                return if (copyResult.output.contains("COPY_OK")) {
                    ToolExecutionResult("${log.toString().trimEnd()}\n\n✅ File ready: $dest ($sizeMb MB)")
                } else {
                    ToolExecutionResult(
                        "${log.toString().trimEnd()}\n\n⚠️ Download OK but copy to '$dest' failed.\nFile is at: ${tmpFile.absolutePath}\nCopy error: ${copyResult.output.take(200)}",
                        isError = false
                    )
                }
            } else {
                log.appendLine("⚠️ OkHttp failed: ${result.exceptionOrNull()?.message}")
                log.appendLine("   Trying shell fallback…")
            }
        }

        // Path B: Shell curl/wget fallback
        val cmd = """
            dir_path="$(dirname '${dest.replace("'", "'\\''")}')"; 
            mkdir -p "${'$'}dir_path";
            if command -v curl >/dev/null 2>&1; then
              curl -fsSL -o '${dest.replace("'", "\\'")}' '${url.replace("'", "\\'")}' 2>&1
            elif command -v wget >/dev/null 2>&1; then
              wget -q -O '${dest.replace("'", "\\'")}' '${url.replace("'", "\\'")}' 2>&1
            else
              echo "Missing curl and wget"; exit 1
            fi
        """.trimIndent()
        val res = EnvironmentSetupManager.executeShell(cmd, useBase64 = true)
        return if (!res.isError) {
            ToolExecutionResult("${log.toString().trimEnd()}\n✅ Downloaded to: $dest")
        } else {
            ToolExecutionResult(
                "${log.toString().trimEnd()}\n❌ All download methods failed:\n${res.output.take(500)}",
                isError = true
            )
        }
    }

    /**
     * Download and execute a script.
     */
    suspend fun downloadExec(url: String, extraArgs: String, cwd: String?): ToolExecutionResult {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolExecutionResult("Invalid URL: '$url'", isError = true)
        }
        val tmp = "/data/local/tmp/omni_dl_exec_${System.currentTimeMillis()}.sh"
        val dlResult = downloadFile(url, tmp)
        if (dlResult.isError) {
            return ToolExecutionResult("❌ Failed to download script from '$url':\n${dlResult.output}", isError = true)
        }
        val argPart = if (extraArgs.isBlank()) "" else " ${EnvironmentSetupManager.shellQuote(extraArgs)}"
        val script = buildString {
            if (!cwd.isNullOrBlank()) appendLine("cd '${cwd.replace("'", "'\\''")}' || exit 1")
            appendLine("chmod +x '$tmp'")
            appendLine("sh '$tmp'$argPart")
            appendLine("EXIT=\$?")
            appendLine("rm -f '$tmp'")
            appendLine("exit \$EXIT")
        }
        return EnvironmentSetupManager.executeShell(script, useBase64 = true)
    }

    /**
     * Download and verify a file's SHA256 checksum.
     */
    suspend fun downloadVerify(url: String, checksum: String, dest: String?): ToolExecutionResult {
        val expected = checksum.lowercase().replace(Regex("[^a-f0-9]"), "")
        if (expected.length != 64) return ToolExecutionResult("checksum must be a 64-char SHA256 hex string.", isError = true)
        val dl = downloadFile(url, dest)
        if (dl.isError) return dl

        val filePath = dl.output.lines()
            .firstOrNull { it.contains("File ready:") || it.contains("Downloaded to:") }
            ?.let {
                when {
                    it.contains("File ready:") -> it.substringAfter("File ready:").trim().substringBefore(" ")
                    it.contains("Downloaded to:") -> it.substringAfter("Downloaded to:").trim()
                    else -> null
                }
            }
            ?: dest
            ?: return ToolExecutionResult("Could not determine downloaded file path for verification.", isError = true)

        // Try sha256sum via shell first
        val hashCmd = EnvironmentSetupManager.executeShell("sha256sum '$filePath' 2>&1 | cut -d' ' -f1")
        val shellHash = hashCmd.output.trim().lowercase().takeIf {
            it.matches(Regex("[a-f0-9]{64}"))
        }

        // Fallback: compute hash in Kotlin from copied file
        val actualHash: String = shellHash ?: run {
            val ctx = appContext
            val localFile = ctx?.getExternalFilesDir("downloads")?.let { d ->
                d.listFiles()?.firstOrNull { it.name == filePath.substringAfterLast("/") }
            }
            if (localFile != null && localFile.exists()) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                localFile.inputStream().use { stream ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (stream.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } else {
                return ToolExecutionResult(
                    "❌ Cannot verify checksum — no sha256sum and file not accessible.\n$filePath",
                    isError = true
                )
            }
        }

        return if (actualHash == expected) {
            ToolExecutionResult("✅ Checksum verified (SHA-256 matches).\nFile: $filePath")
        } else {
            EnvironmentSetupManager.executeShell("rm -f '$filePath'")
            ToolExecutionResult(
                "❌ SHA-256 MISMATCH — file deleted.\nExpected: $expected\nActual:   $actualHash",
                isError = true
            )
        }
    }
}
