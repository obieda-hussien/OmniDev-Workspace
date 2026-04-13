package com.omnidev.workspace.data.tools.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.ShizukuResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * VulnResearchToolchain — Orchestrates OmniNativeToolsManager to provide
 * high-level vulnerability research operations.
 *
 * Every public function is self-contained: it auto-downloads the required tools
 * if missing, then runs the analysis through Shizuku.
 *
 * ─── Available Operations ──────────────────────────────────────────────────────
 *  setupTools()          → Download & install all tools (show progress to user)
 *  deepStaticAnalysis()  → aapt2 + Python DEX scanner + cert analysis
 *  decompileToSource()   → jadx full APK decompilation to Java source
 *  decodeApktool()       → apktool smali decode
 *  scanSecretsInSource() → Python secret hunter across decompiled tree
 *  aapt2Analysis()       → Full aapt2 manifest + resources dump
 *  fullPipeline()        → All of the above in parallel
 */
object VulnResearchToolchain {

    private const val TAG = "VulnToolchain"
    data class CustomToolSpec(val name: String, val url: String)

    // ─── Tool Setup ───────────────────────────────────────────────────────────

    /**
     * Download and install all security research tools.
     * Returns a status JSON describing what was installed.
     */
    suspend fun setupTools(
        context: Context,
        tools: List<OmniNativeToolsManager.Tool> = listOf(
            OmniNativeToolsManager.Tool.AAPT2,
            OmniNativeToolsManager.Tool.BUSYBOX,
            OmniNativeToolsManager.Tool.JADX,
            OmniNativeToolsManager.Tool.APKTOOL,
            OmniNativeToolsManager.Tool.PYTHON,
            OmniNativeToolsManager.Tool.DEX2JAR
        ),
        installTimeoutMs: Long = OmniNativeToolsManager.DEFAULT_INSTALL_TIMEOUT_MS,
        onProgress: (String) -> Unit = {}
    ): JSONObject = withContext(Dispatchers.IO) {
        val results = JSONObject()

        tools.forEach { tool ->
            onProgress("Installing ${tool.displayName}...")
            val result = OmniNativeToolsManager.ensure(context, tool, installTimeoutMs = installTimeoutMs)
            results.put(tool.name, if (result.isSuccess) "✅ installed" else "❌ ${result.exceptionOrNull()?.message}")
            onProgress("${tool.displayName}: ${if (result.isSuccess) "done" else "failed"}")
        }

        results.put("storage_mb", "%.1f".format(OmniNativeToolsManager.totalSize(context) / 1_048_576.0))
        results
    }

    suspend fun setupCustomTools(
        context: Context,
        customTools: List<CustomToolSpec>,
        installTimeoutMs: Long = OmniNativeToolsManager.DEFAULT_INSTALL_TIMEOUT_MS,
        onProgress: (String) -> Unit = {}
    ): JSONObject = withContext(Dispatchers.IO) {
        val results = JSONObject()
        customTools.forEach { spec ->
            onProgress("Installing custom tool ${spec.name}...")
            val result = OmniNativeToolsManager.installCustomBinary(
                context = context,
                name = spec.name,
                downloadUrl = spec.url,
                installTimeoutMs = installTimeoutMs
            )
            val key = "custom_${spec.name}"
            if (result.isSuccess) {
                val path = result.getOrNull()?.absolutePath.orEmpty()
                results.put(key, "✅ installed ($path)")
            } else {
                results.put(key, "❌ ${result.exceptionOrNull()?.message}")
            }
        }
        results
    }

    /**
     * Return tool installation status without downloading anything.
     */
    fun toolsStatus(context: Context): JSONObject =
        OmniNativeToolsManager.statusJson(context)

    // ─── APK Access ───────────────────────────────────────────────────────────

    /** Resolve an installed package's base APK path. */
    private fun resolveApkPath(context: Context, packageName: String): String? {
        return try {
            val pm   = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(packageName, 0)
            }
            info.applicationInfo?.sourceDir
        } catch (e: Exception) { null }
    }

    /** Get (or create) a per-package working directory inside the tools work dir. */
    private fun workDir(context: Context, packageName: String): File {
        OmniNativeToolsManager.init(context)
        val dir = File(context.filesDir, "omnidev_tools/work/$packageName")
        dir.mkdirs()
        return dir
    }

    // ─── aapt2 Analysis ──────────────────────────────────────────────────────

    /**
     * Comprehensive aapt2 analysis of an APK:
     *  - Binary XML manifest dump (decoded, human-readable)
     *  - All string resources
     *  - File listing inside the APK
     *
     * This is the most reliable way to read the actual compiled AndroidManifest.xml,
     * which is in binary AXML format and unreadable with plain text parsing.
     */
    suspend fun aapt2Analysis(
        context: Context,
        packageName: String
    ): AnalysisResult = withContext(Dispatchers.IO) {
        val apkPath = resolveApkPath(context, packageName)
            ?: return@withContext AnalysisResult.Error("Package '$packageName' not found")

        val installed = OmniNativeToolsManager.ensure(context, OmniNativeToolsManager.Tool.AAPT2)
        if (installed.isFailure) {
            return@withContext AnalysisResult.Error("aapt2 not available: ${installed.exceptionOrNull()?.message}")
        }

        coroutineScope {
            val manifest = async { OmniNativeToolsManager.dumpManifest(context, apkPath) }
            val strings  = async { OmniNativeToolsManager.dumpStrings(context, apkPath) }
            val badging  = async { OmniNativeToolsManager.aapt2(context, "dump", "badging", apkPath) }
            val perms    = async { OmniNativeToolsManager.aapt2(context, "dump", "permissions", apkPath) }

            val output = buildString {
                appendLine("═══ aapt2 Analysis: $packageName ═══")
                appendLine("APK: $apkPath")
                appendLine()

                appendLine("── Badging (version, target SDK, permissions) ──")
                appendLine(badging.await().toToolOutput().take(3000))
                appendLine()

                appendLine("── Declared Permissions ──")
                appendLine(perms.await().toToolOutput().take(1000))
                appendLine()

                appendLine("── Binary Manifest (AXML decoded) ──")
                appendLine(manifest.await().toToolOutput().take(5000))
                appendLine()

                appendLine("── String Resources (first 2000 chars) ──")
                appendLine(strings.await().toToolOutput().take(2000))
            }
            AnalysisResult.Ok(output)
        }
    }

    // ─── Python DEX Scanner ───────────────────────────────────────────────────

    /**
     * Run the built-in Python DEX scanner against an APK.
     * Finds secrets, weak crypto, dangerous APIs, and more via regex pattern matching.
     */
    suspend fun pythonDexScan(
        context: Context,
        packageName: String
    ): AnalysisResult = withContext(Dispatchers.IO) {
        val apkPath = resolveApkPath(context, packageName)
            ?: return@withContext AnalysisResult.Error("Package '$packageName' not found")

        val pythonInstalled = OmniNativeToolsManager.ensure(context, OmniNativeToolsManager.Tool.PYTHON)
        if (pythonInstalled.isFailure) {
            return@withContext AnalysisResult.Error("Python not available: ${pythonInstalled.exceptionOrNull()?.message}")
        }

        // Run both scanner scripts in parallel
        coroutineScope {
            val dexScan   = async {
                OmniNativeToolsManager.runBuiltinScript(
                    context, "dex_scanner.py",
                    args    = listOf(apkPath, "--json"),
                    timeoutMs = 60_000L
                )
            }
            val certScan  = async {
                OmniNativeToolsManager.runBuiltinScript(
                    context, "cert_analyzer.py",
                    args    = listOf(apkPath),
                    timeoutMs = 30_000L
                )
            }

            val dexResult  = dexScan.await()
            val certResult = certScan.await()

            val output = buildString {
                appendLine("═══ Python DEX Scanner: $packageName ═══")
                appendLine()
                appendLine("── DEX String Analysis ──")
                appendLine(dexResult.toToolOutput().take(4000))
                appendLine()
                appendLine("── Certificate Analysis ──")
                appendLine(certResult.toToolOutput().take(1000))
            }
            AnalysisResult.Ok(output)
        }
    }

    // ─── jadx Decompilation ───────────────────────────────────────────────────

    /**
     * Decompile an APK to Java source using jadx.
     * Output is stored in the work directory and can be scanned with secretHunter.
     *
     * @return AnalysisResult.Ok containing the output directory path.
     */
    suspend fun decompileToSource(
        context: Context,
        packageName: String
    ): AnalysisResult = withContext(Dispatchers.IO) {
        val apkPath = resolveApkPath(context, packageName)
            ?: return@withContext AnalysisResult.Error("Package '$packageName' not found")

        val jadxInstalled = OmniNativeToolsManager.ensure(context, OmniNativeToolsManager.Tool.JADX)
        if (jadxInstalled.isFailure) {
            return@withContext AnalysisResult.Error("jadx not available: ${jadxInstalled.exceptionOrNull()?.message}")
        }

        val outDir = File(workDir(context, packageName), "jadx_src").also {
            it.deleteRecursively(); it.mkdirs()
        }

        Log.i(TAG, "Decompiling $packageName → ${outDir.absolutePath}")
        val result = OmniNativeToolsManager.decompileApk(
            context   = context,
            apkPath   = apkPath,
            outputDir = outDir.absolutePath,
            extraArgs = listOf("--threads-count", "4", "--deobf")
        )

        if (!result.isOk && !outDir.exists()) {
            return@withContext AnalysisResult.Error("jadx failed: ${result.errorOrNull()}")
        }

        val javaFiles = outDir.walkTopDown().filter { it.extension == "java" }.count()
        AnalysisResult.Ok(
            "✅ Decompiled $packageName → ${outDir.absolutePath}\n" +
            "Java files: $javaFiles\n" +
            "jadx output: ${result.outputOrNull()?.take(500)}\n\n" +
            "Run 'scan_secrets_in_source' to search for hardcoded secrets.",
            data = mapOf("source_dir" to outDir.absolutePath, "java_files" to javaFiles.toString())
        )
    }

    // ─── apktool Decode ───────────────────────────────────────────────────────

    /**
     * Decode APK with apktool to get smali bytecode + decoded resources.
     */
    suspend fun decodeWithApktool(
        context: Context,
        packageName: String
    ): AnalysisResult = withContext(Dispatchers.IO) {
        val apkPath = resolveApkPath(context, packageName)
            ?: return@withContext AnalysisResult.Error("Package '$packageName' not found")

        val apktoolInstalled = OmniNativeToolsManager.ensure(context, OmniNativeToolsManager.Tool.APKTOOL)
        if (apktoolInstalled.isFailure) {
            return@withContext AnalysisResult.Error("apktool not available: ${apktoolInstalled.exceptionOrNull()?.message}")
        }

        val outDir = File(workDir(context, packageName), "apktool_out").also {
            it.deleteRecursively(); it.mkdirs()
        }

        val result = OmniNativeToolsManager.apktoolDecode(context, apkPath, outDir.absolutePath)

        val smaliFiles = outDir.walkTopDown().filter { it.extension == "smali" }.count()
        val xmlFiles   = outDir.walkTopDown().filter { it.extension == "xml" }.count()

        AnalysisResult.Ok(
            "✅ apktool decoded $packageName → ${outDir.absolutePath}\n" +
            "Smali files: $smaliFiles | XML files: $xmlFiles\n" +
            "Output:\n${result.outputOrNull()?.take(800)}",
            data = mapOf("output_dir" to outDir.absolutePath)
        )
    }

    // ─── Secret Hunter ────────────────────────────────────────────────────────

    /**
     * Run the Python secret hunter across a decompiled source directory.
     * Typically called after decompileToSource().
     */
    suspend fun scanSecretsInSource(
        context: Context,
        packageName: String,
        sourceDir: String? = null
    ): AnalysisResult = withContext(Dispatchers.IO) {
        val pythonInstalled = OmniNativeToolsManager.ensure(context, OmniNativeToolsManager.Tool.PYTHON)
        if (pythonInstalled.isFailure) {
            return@withContext AnalysisResult.Error("Python not available: ${pythonInstalled.exceptionOrNull()?.message}")
        }

        // Use provided sourceDir, or look for jadx output in the work dir
        val scanDir = sourceDir ?: run {
            val jadxDir = File(workDir(context, packageName), "jadx_src")
            if (jadxDir.exists()) jadxDir.absolutePath
            else return@withContext AnalysisResult.Error(
                "No source directory found. Run 'decompile' first, or provide source_dir."
            )
        }

        val result = OmniNativeToolsManager.runBuiltinScript(
            context   = context,
            scriptName = "secret_hunter.py",
            args       = listOf(scanDir, "--json"),
            timeoutMs  = 60_000L
        )

        AnalysisResult.Ok("═══ Secret Hunter: $packageName ═══\n\n${result.toToolOutput().take(8000)}")
    }

    // ─── Custom Python Script ─────────────────────────────────────────────────

    /**
     * Execute a user-supplied Python script in the context of a target package.
     * The script receives the APK path as the first argument.
     */
    suspend fun runCustomPythonAnalysis(
        context: Context,
        packageName: String,
        scriptContent: String,
        extraArgs: List<String> = emptyList()
    ): AnalysisResult = withContext(Dispatchers.IO) {
        val apkPath = resolveApkPath(context, packageName)
            ?: return@withContext AnalysisResult.Error("Package '$packageName' not found")

        val result = OmniNativeToolsManager.python(
            context       = context,
            scriptContent = scriptContent,
            args          = listOf(apkPath) + extraArgs,
            timeoutMs     = 120_000L
        )

        AnalysisResult.Ok(result.toToolOutput().take(10_000))
    }

    // ─── Full Pipeline ────────────────────────────────────────────────────────

    /**
     * Run the complete vulnerability research pipeline in parallel:
     *   1. aapt2 analysis  (manifest, permissions, badging)
     *   2. Python DEX scan (secrets, crypto, patterns)
     *   3. jadx decompile  (Java source extraction)
     *   4. apktool decode  (smali + decoded resources)
     *   5. Secret hunter   (across decompiled source)
     *
     * Tools are auto-installed if missing. Results are consolidated into a single JSON.
     *
     * @param phases subset of phases to run (all by default)
     */
    suspend fun fullPipeline(
        context: Context,
        packageName: String,
        phases: Set<PipelinePhase> = PipelinePhase.values().toSet(),
        onPhaseComplete: (String, String) -> Unit = { _, _ -> }
    ): JSONObject = withContext(Dispatchers.IO) {
        val report     = JSONObject()
        val startMs    = System.currentTimeMillis()
        val apkPath    = resolveApkPath(context, packageName)

        report.put("package", packageName)
        report.put("apk_path", apkPath ?: "not_found")

        if (apkPath == null) {
            report.put("error", "Package not found")
            return@withContext report
        }

        coroutineScope {
            // Phase 1: aapt2 (fast, no decompilation)
            val aapt2Job = if (PipelinePhase.AAPT2 in phases) async {
                val r = aapt2Analysis(context, packageName)
                onPhaseComplete("aapt2", r.summary())
                report.put("aapt2", r.toJson())
            } else null

            // Phase 2: Python DEX scan (fast, no decompilation)
            val dexJob = if (PipelinePhase.DEX_SCAN in phases) async {
                val r = pythonDexScan(context, packageName)
                onPhaseComplete("dex_scan", r.summary())
                report.put("dex_scan", r.toJson())
            } else null

            // Wait for fast phases before starting decompilation
            listOfNotNull(aapt2Job, dexJob).awaitAll()

            // Phase 3: jadx decompilation (slow, do after fast phases)
            if (PipelinePhase.DECOMPILE in phases) {
                val decompResult = decompileToSource(context, packageName)
                onPhaseComplete("decompile", decompResult.summary())
                report.put("decompile", decompResult.toJson())

                // Phase 4: apktool decode (independent from jadx output)
                if (PipelinePhase.DECODE_SMALI in phases) {
                    val decodeResult = decodeWithApktool(context, packageName)
                    onPhaseComplete("decode_smali", decodeResult.summary())
                    report.put("decode_smali", decodeResult.toJson())
                }

                // Phase 5: Secret hunter (depends on Phase 3)
                if (PipelinePhase.SECRET_HUNT in phases && decompResult is AnalysisResult.Ok) {
                    val sourceDir = decompResult.data["source_dir"]
                    val huntResult = scanSecretsInSource(context, packageName, sourceDir)
                    onPhaseComplete("secret_hunt", huntResult.summary())
                    report.put("secret_hunt", huntResult.toJson())
                }
            }
        }

        report.put("duration_ms", System.currentTimeMillis() - startMs)
        report
    }

    enum class PipelinePhase { AAPT2, DEX_SCAN, DECOMPILE, DECODE_SMALI, SECRET_HUNT }

    // ─── Utility: Shell Access ────────────────────────────────────────────────

    /**
     * Run an arbitrary busybox command with Shizuku.
     * Useful for custom analysis scripts or one-off shell operations.
     */
    suspend fun shellCmd(context: Context, command: String): AnalysisResult = withContext(Dispatchers.IO) {
        val bbInstalled = OmniNativeToolsManager.ensure(context, OmniNativeToolsManager.Tool.BUSYBOX)
        val busyboxBin = if (bbInstalled.isSuccess) bbInstalled.getOrNull()?.absolutePath else null

        val finalCmd = if (busyboxBin != null && command.startsWith("busybox ")) {
            "'$busyboxBin' ${command.removePrefix("busybox ")}"
        } else command

        val result = OmniNativeToolsManager.exec(finalCmd)
        if (result.isOk) AnalysisResult.Ok(result.outputOrNull() ?: "(no output)")
        else AnalysisResult.Error(result.errorOrNull() ?: "Unknown error")
    }

    /**
     * Run a one-off Python snippet against the target APK.
     * The snippet receives `APK_PATH` as a pre-set global variable.
     */
    suspend fun quickPython(
        context: Context,
        packageName: String,
        snippet: String
    ): AnalysisResult = withContext(Dispatchers.IO) {
        val apkPath = resolveApkPath(context, packageName)
            ?: return@withContext AnalysisResult.Error("Package '$packageName' not found")

        val script = "APK_PATH = '$apkPath'\n$snippet"
        val result = OmniNativeToolsManager.python(context, script, timeoutMs = 60_000L)
        if (result.isOk) AnalysisResult.Ok(result.outputOrNull() ?: "(no output)")
        else AnalysisResult.Error(result.errorOrNull() ?: "Script failed")
    }

    // ─── Result Type ─────────────────────────────────────────────────────────

    sealed class AnalysisResult {
        data class Ok(val output: String, val data: Map<String, String> = emptyMap()) : AnalysisResult()
        data class Error(val message: String) : AnalysisResult()

        fun summary(): String = when (this) {
            is Ok    -> if (output.length > 80) output.take(77) + "..." else output
            is Error -> "ERROR: $message"
        }

        fun toToolOutput(maxChars: Int = 12_000): String = when (this) {
            is Ok    -> output.take(maxChars)
            is Error -> "❌ $message"
        }

        fun toJson(): JSONObject {
            val obj = JSONObject()
            return when (this) {
                is Ok -> {
                    obj.put("ok", true)
                    obj.put("output", output.take(6000))
                    data.forEach { (k, v) -> obj.put(k, v) }
                    obj
                }
                is Error -> {
                    obj.put("ok", false)
                    obj.put("error", message)
                    obj
                }
            }
        }
    }
}
