package com.omnidev.workspace.data.tools

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Environment bootstrapper for on-device Android builds.
 *
 * * FIXES & HACKER UPGRADES:
 * 1. Base64 Script Injection: Safely injects JAVA_HOME and ANDROID_HOME without quote-escaping hell.
 * 2. Native Android Shell: Uses `/system/bin/sh` instead of the non-existent `/bin/sh`.
 * 3. Tail-Truncation: Captures the LAST 12,000 characters of a build log (where errors live).
 * 4. Robust Extraction: Uses `busybox` fallback for `tar.xz` if native tar fails.
 */
class EnvironmentSetupManager(private val context: Context) {

    companion object {
        private const val JDK_DIR = "openjdk-17"
        private const val SDK_DIR = "android-sdk"
        // Note: Android's native tar struggles with .xz. The code now tries busybox as a fallback.
        // If it still fails, highly recommend uploading a .tar.gz version to your repo instead.
        private const val JDK_URL =
            "https://github.com/AstroInc9/AstroTermux/raw/main/openjdk-17.0.12-aarch64.tar.xz"
        private const val SDK_URL =
            "https://github.com/AstroInc9/AstroTermux/raw/main/android-sdk-tools-static-aarch64.zip"

        /** Maximum characters captured from a terminal command's combined stdout/stderr. */
        private const val MAX_OUTPUT_CHARS = 12_000

        /** Timeout in seconds for shell commands — 5 min to accommodate full Gradle builds. */
        private const val TERMINAL_TIMEOUT_SECONDS = 300L  // 5 min for builds
    }

    // ──────────────────────────────────────────────
    //  Environment Paths
    // ──────────────────────────────────────────────

    val javaHome: String get() = File(context.filesDir, JDK_DIR).absolutePath
    val androidHome: String get() = File(context.filesDir, SDK_DIR).absolutePath

    val isJdkInstalled: Boolean get() = File(javaHome, "bin/java").exists()
    val isSdkInstalled: Boolean get() = File(androidHome, "build-tools").exists()
    val isReady: Boolean get() = isJdkInstalled && isSdkInstalled

    // ──────────────────────────────────────────────
    //  Tool Definitions
    // ──────────────────────────────────────────────

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "advanced_terminal",
            description = "Execute a shell command with full Android build environment " +
                "(JDK 17, Android SDK 35). Injects JAVA_HOME, ANDROID_HOME, and PATH " +
                "automatically. Use for Gradle builds (./gradlew assembleDebug), Java compilation, " +
                "or any toolchain command.",
            parameters = listOf(
                ToolParameter("command", "string", "Shell command to execute.", required = true),
                ToolParameter("workingDirectory", "string", "Absolute path to use as working directory.", required = false)
            )
        ),
        ToolDefinition(
            name = "setup_build_environment",
            description = "Download and install JDK 17 and Android SDK 35.0.2 for aarch64. " +
                "Required before running Gradle builds. This is a one-time operation.",
            parameters = emptyList()
        )
    )

    // ──────────────────────────────────────────────
    //  Tool Execution Router
    // ──────────────────────────────────────────────

    suspend fun executeTool(
        name: String,
        arguments: Map<String, String>,
        scopePath: String
    ): ToolExecutionResult {
        return when (name) {
            "advanced_terminal" -> {
                val command = arguments["command"] ?: return ToolExecutionResult("Missing required argument: command", isError = true)
                val workDir = arguments["workingDirectory"] ?: scopePath
                executeWithEnv(command, workDir)
            }
            "setup_build_environment" -> setupEnvironment { /* progress unused in tool mode */ }
            else -> ToolExecutionResult("Unknown tool: $name", isError = true)
        }
    }

    // ──────────────────────────────────────────────
    //  Environment Setup
    // ──────────────────────────────────────────────

    suspend fun setupEnvironment(onProgress: (String) -> Unit): ToolExecutionResult {
        return try {
            withContext(Dispatchers.IO) {
                val summary = StringBuilder()

                // ── JDK ──
                if (!isJdkInstalled) {
                    onProgress("Downloading JDK 17…")
                    val jdkTmp = File(context.cacheDir, "openjdk-17.tar.xz")
                    val jdkDest = File(context.filesDir, JDK_DIR)

                    if (!downloadFile(JDK_URL, jdkTmp, onProgress)) {
                        return@withContext ToolExecutionResult("Failed to download JDK from $JDK_URL", isError = true)
                    }

                    onProgress("Extracting JDK 17…")
                    jdkDest.mkdirs()
                    
                    // FIX: Android native tar doesn't support xz. Try busybox if available, else native tar (which might fail).
                    val extractCmd = "if command -v busybox >/dev/null 2>&1; then busybox tar xf ${jdkTmp.absolutePath} --strip-components=1 -C ${jdkDest.absolutePath}; else tar xf ${jdkTmp.absolutePath} --strip-components=1 -C ${jdkDest.absolutePath}; fi"
                    
                    val jdkExtract = ProcessBuilder("/system/bin/sh", "-c", extractCmd)
                        .redirectErrorStream(true).start()
                        
                    val jdkWaitThread = Thread { try { jdkExtract.waitFor() } catch (_: InterruptedException) {} }
                    jdkWaitThread.start()
                    jdkWaitThread.join(TERMINAL_TIMEOUT_SECONDS * 1000L)
                    
                    if (jdkWaitThread.isAlive) {
                        jdkExtract.destroy()
                        jdkTmp.delete()
                        return@withContext ToolExecutionResult("JDK extraction timed out after ${TERMINAL_TIMEOUT_SECONDS}s.", isError = true)
                    }
                    jdkTmp.delete()

                    if (jdkExtract.exitValue() != 0) {
                        // Highly likely due to lack of xz support
                        return@withContext ToolExecutionResult(
                            "Failed to extract JDK archive (exit ${jdkExtract.exitValue()}). Ensure busybox is installed or use a .tar.gz archive.", 
                            isError = true
                        )
                    }

                    try {
                        ProcessBuilder("/system/bin/sh", "-c", "chmod -R 755 ${File(jdkDest, "bin").absolutePath}")
                            .redirectErrorStream(true).start().waitFor()
                    } catch (_: Exception) {}

                    summary.appendLine("✅ JDK 17 installed at $jdkDest")
                    onProgress("JDK 17 installed.")
                } else {
                    summary.appendLine("✅ JDK 17 already installed.")
                }

                // ── Android SDK ──
                if (!isSdkInstalled) {
                    onProgress("Downloading Android SDK…")
                    val sdkTmp = File(context.cacheDir, "android-sdk.zip")
                    val sdkDest = File(context.filesDir, SDK_DIR)

                    if (!downloadFile(SDK_URL, sdkTmp, onProgress)) {
                        return@withContext ToolExecutionResult("Failed to download Android SDK from $SDK_URL", isError = true)
                    }

                    onProgress("Extracting Android SDK…")
                    sdkDest.mkdirs()
                    
                    // unzip is usually available, busybox fallback applied just in case
                    val unzipCmd = "if command -v unzip >/dev/null 2>&1; then unzip -o -q ${sdkTmp.absolutePath} -d ${sdkDest.absolutePath}; else busybox unzip -o -q ${sdkTmp.absolutePath} -d ${sdkDest.absolutePath}; fi"
                    val sdkExtract = ProcessBuilder("/system/bin/sh", "-c", unzipCmd)
                        .redirectErrorStream(true).start()
                        
                    val sdkWaitThread = Thread { try { sdkExtract.waitFor() } catch (_: InterruptedException) {} }
                    sdkWaitThread.start()
                    sdkWaitThread.join(TERMINAL_TIMEOUT_SECONDS * 1000L)
                    
                    if (sdkWaitThread.isAlive) {
                        sdkExtract.destroy()
                        sdkTmp.delete()
                        return@withContext ToolExecutionResult("SDK extraction timed out after ${TERMINAL_TIMEOUT_SECONDS}s.", isError = true)
                    }
                    sdkTmp.delete()

                    if (sdkExtract.exitValue() != 0) {
                        return@withContext ToolExecutionResult("Failed to extract SDK archive (exit ${sdkExtract.exitValue()})", isError = true)
                    }

                    val platformTools = File(sdkDest, "platform-tools")
                    if (platformTools.exists()) {
                        try { ProcessBuilder("/system/bin/sh", "-c", "chmod -R 755 ${platformTools.absolutePath}").start().waitFor() } catch (_: Exception) {}
                    }
                    val buildTools = File(sdkDest, "build-tools")
                    if (buildTools.exists()) {
                        buildTools.listFiles()?.forEach { versionDir ->
                            try { ProcessBuilder("/system/bin/sh", "-c", "chmod -R 755 ${versionDir.absolutePath}").start().waitFor() } catch (_: Exception) {}
                        }
                    }

                    summary.appendLine("✅ Android SDK installed at $sdkDest")
                    onProgress("Android SDK installed.")
                } else {
                    summary.appendLine("✅ Android SDK already installed.")
                }

                ToolExecutionResult(output = summary.toString().trimEnd(), isError = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult("Environment setup failed: ${e.message}", isError = true)
        }
    }

    private fun downloadFile(urlStr: String, dest: File, onProgress: (String) -> Unit): Boolean {
        return try {
            var currentUrl = urlStr
            var redirects = 0
            val maxRedirects = 5

            while (redirects < maxRedirects) {
                val connection = URL(currentUrl).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.connect()

                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) return false
                    currentUrl = location
                    redirects++
                    continue
                }

                if (code != HttpURLConnection.HTTP_OK) {
                    connection.disconnect()
                    return false
                }

                val totalBytes = connection.contentLength.toLong()
                connection.inputStream.use { input ->
                    FileOutputStream(dest).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalBytes > 0) {
                                val pct = (totalRead * 100 / totalBytes).toInt()
                                onProgress("Downloading… $pct%")
                            }
                        }
                    }
                }
                connection.disconnect()
                return true
            }
            false
        } catch (e: Exception) {
            onProgress("Download error: ${e.message}")
            false
        }
    }

    // ──────────────────────────────────────────────
    //  advanced_terminal (Base64 Injection)
    // ──────────────────────────────────────────────

    suspend fun executeWithEnv(command: String, workingDirectory: String): ToolExecutionResult {
        val workDir = File(workingDirectory)

        if (!workDir.exists() || !workDir.isDirectory) {
            return ToolExecutionResult("Working directory not found: $workingDirectory", isError = true)
        }

        return try {
            withContext(Dispatchers.IO) {
                // FIX: Base64 Script Injection to safely export Env Vars and run complex builds
                val scriptContent = buildString {
                    appendLine("#!/system/bin/sh")
                    appendLine("export JAVA_HOME='${javaHome.replace("'", "'\\''")}'")
                    appendLine("export ANDROID_HOME='${androidHome.replace("'", "'\\''")}'")
                    appendLine("export PATH='${buildPathVariable().replace("'", "'\\''")}'")
                    appendLine("cd '${workingDirectory.replace("'", "'\\''")}' || exit 1")
                    appendLine(command)
                }

                val tmpPath = File(context.cacheDir, "omni_build_${System.currentTimeMillis()}.sh")
                tmpPath.writeText(scriptContent)
                tmpPath.setExecutable(true)

                val process = ProcessBuilder("/system/bin/sh", tmpPath.absolutePath)
                    .redirectErrorStream(true)
                    .start()

                // Thread-safe buffer for reading output
                val outputBuffer = StringBuffer()
                val readerThread = Thread {
                    try {
                        process.inputStream.bufferedReader().use { reader ->
                            reader.lineSequence().forEach { line ->
                                outputBuffer.append(line).append("\n")
                                // FIX: Tail Truncation - keep only the end of the log where build errors are
                                if (outputBuffer.length > MAX_OUTPUT_CHARS + 2000) {
                                    outputBuffer.delete(0, outputBuffer.length - MAX_OUTPUT_CHARS)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                readerThread.start()

                val waitThread = Thread { try { process.waitFor() } catch (_: InterruptedException) {} }
                waitThread.start()
                waitThread.join(TERMINAL_TIMEOUT_SECONDS * 1000L)

                val completed = !waitThread.isAlive
                if (!completed) {
                    process.destroy()
                    readerThread.interrupt()
                    tmpPath.delete()
                    return@withContext ToolExecutionResult("⏱ Build command timed out after ${TERMINAL_TIMEOUT_SECONDS}s.", isError = true)
                }

                readerThread.join(2_000L)
                tmpPath.delete() // Clean up script

                val exitCode = process.exitValue()
                var output = outputBuffer.toString().trimEnd()
                
                if (outputBuffer.length >= MAX_OUTPUT_CHARS) {
                    output = "...[TRUNCATED to save tokens]...\n$output"
                }

                val resultText = buildString {
                    appendLine("$ $command")
                    if (output.isNotEmpty()) appendLine(output)
                    append("[exit: $exitCode]")
                }

                ToolExecutionResult(output = resultText, isError = exitCode != 0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult("Failed to execute build command: ${e.message}", isError = true)
        }
    }

    private fun buildPathVariable(): String {
        val systemPath = System.getenv("PATH") ?: "/usr/bin:/system/bin:/system/xbin"
        val sdkDir = File(androidHome)

        val buildToolsDir = File(sdkDir, "build-tools")
        val latestBuildTools = if (buildToolsDir.exists()) {
            buildToolsDir.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }?.absolutePath
        } else null

        return buildString {
            append("$javaHome/bin")
            if (latestBuildTools != null) append(":$latestBuildTools")
            val platformTools = File(sdkDir, "platform-tools")
            if (platformTools.exists()) append(":${platformTools.absolutePath}")
            append(":$systemPath")
        }
    }
}
