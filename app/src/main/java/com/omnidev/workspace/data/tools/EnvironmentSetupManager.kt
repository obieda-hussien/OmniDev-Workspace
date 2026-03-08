package com.omnidev.workspace.data.tools

import android.content.Context
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
 * Downloads and installs JDK 17 and Android SDK 35 (aarch64) into the app's private
 * storage, then provides an [advanced_terminal] tool that injects JAVA_HOME,
 * ANDROID_HOME, and PATH so Gradle builds, Java compilation, and other toolchain
 * commands work out of the box.
 *
 * Tools implemented:
 * - `advanced_terminal`: Shell execution with full build environment injected.
 * - `setup_build_environment`: One-time download and installation of JDK + SDK.
 */
class EnvironmentSetupManager(private val context: Context) {

    companion object {
        private const val JDK_DIR = "openjdk-17"
        private const val SDK_DIR = "android-sdk"
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

    /** Absolute path to the installed JDK root directory. */
    val javaHome: String get() = File(context.filesDir, JDK_DIR).absolutePath

    /** Absolute path to the installed Android SDK root directory. */
    val androidHome: String get() = File(context.filesDir, SDK_DIR).absolutePath

    /** True when the JDK `java` binary exists on disk. */
    val isJdkInstalled: Boolean get() = File(javaHome, "bin/java").exists()

    /** True when the Android SDK `build-tools` directory exists on disk. */
    val isSdkInstalled: Boolean get() = File(androidHome, "build-tools").exists()

    /** True when both JDK and SDK are installed and ready to use. */
    val isReady: Boolean get() = isJdkInstalled && isSdkInstalled

    // ──────────────────────────────────────────────
    //  Tool Definitions (for AI function-calling schema)
    // ──────────────────────────────────────────────

    /**
     * Returns the list of tool definitions for the build-environment tools,
     * formatted for inclusion in the system prompt or tool schema.
     */
    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "advanced_terminal",
            description = "Execute a shell command with full Android build environment " +
                "(JDK 17, Android SDK 35). Injects JAVA_HOME, ANDROID_HOME, and PATH " +
                "automatically. Use for Gradle builds, Java compilation, or any command " +
                "needing a build toolchain.",
            parameters = listOf(
                ToolParameter(
                    name = "command",
                    type = "string",
                    description = "Shell command to execute (e.g., './gradlew assembleDebug').",
                    required = true
                ),
                ToolParameter(
                    name = "workingDirectory",
                    type = "string",
                    description = "Absolute path to use as the working directory. " +
                        "Defaults to Target Context.",
                    required = false
                )
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

    /**
     * Dispatches a tool call by [name] with the given [arguments].
     *
     * @param name The tool name (e.g., "advanced_terminal").
     * @param arguments Key-value arguments for the tool.
     * @param scopePath The user's active target context directory.
     * @return The result of the tool execution.
     */
    suspend fun executeTool(
        name: String,
        arguments: Map<String, String>,
        scopePath: String
    ): ToolExecutionResult {
        return when (name) {
            "advanced_terminal" -> {
                val command = arguments["command"]
                    ?: return ToolExecutionResult(
                        output = "Missing required argument: command",
                        isError = true
                    )
                val workDir = arguments["workingDirectory"] ?: scopePath
                executeWithEnv(command, workDir)
            }
            "setup_build_environment" -> setupEnvironment { /* progress unused in tool mode */ }
            else -> ToolExecutionResult(
                output = "Unknown tool: $name",
                isError = true
            )
        }
    }

    // ──────────────────────────────────────────────
    //  setup_build_environment
    // ──────────────────────────────────────────────

    /**
     * Downloads and installs JDK 17 and Android SDK 35 for aarch64.
     *
     * Each archive is downloaded via [HttpURLConnection], saved to a temp file, extracted,
     * and then deleted. The operation is idempotent — components that are already installed
     * are skipped.
     *
     * @param onProgress Callback invoked with human-readable progress messages.
     * @return A [ToolExecutionResult] summarising what was installed.
     */
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
                        return@withContext ToolExecutionResult(
                            output = "Failed to download JDK from $JDK_URL",
                            isError = true
                        )
                    }

                    onProgress("Extracting JDK 17…")
                    jdkDest.mkdirs()
                    val jdkExtract = ProcessBuilder(
                        "tar", "xf", jdkTmp.absolutePath,
                        "--strip-components=1", "-C", jdkDest.absolutePath
                    ).redirectErrorStream(true).start()
                    // API-24-compatible timeout for extraction (5 min max)
                    val jdkWaitThread = Thread {
                        try { jdkExtract.waitFor() } catch (_: InterruptedException) {}
                    }
                    jdkWaitThread.start()
                    jdkWaitThread.join(TERMINAL_TIMEOUT_SECONDS * 1000L)
                    if (jdkWaitThread.isAlive) {
                        jdkExtract.destroy()
                        jdkTmp.delete()
                        return@withContext ToolExecutionResult(
                            output = "JDK extraction timed out after ${TERMINAL_TIMEOUT_SECONDS}s.",
                            isError = true
                        )
                    }
                    jdkTmp.delete()

                    if (jdkExtract.exitValue() != 0) {
                        return@withContext ToolExecutionResult(
                            output = "Failed to extract JDK archive (exit ${jdkExtract.exitValue()})",
                            isError = true
                        )
                    }

                    // Make binaries executable
                    try {
                        ProcessBuilder("chmod", "-R", "+x", File(jdkDest, "bin").absolutePath)
                            .redirectErrorStream(true).start().waitFor()
                    } catch (_: Exception) {
                        // chmod failure is non-fatal — binaries may still work
                    }

                    summary.appendLine("✅ JDK 17 installed at $jdkDest")
                    onProgress("JDK 17 installed.")
                } else {
                    summary.appendLine("✅ JDK 17 already installed.")
                    onProgress("JDK 17 already installed.")
                }

                // ── Android SDK ──
                if (!isSdkInstalled) {
                    onProgress("Downloading Android SDK…")
                    val sdkTmp = File(context.cacheDir, "android-sdk.zip")
                    val sdkDest = File(context.filesDir, SDK_DIR)

                    if (!downloadFile(SDK_URL, sdkTmp, onProgress)) {
                        return@withContext ToolExecutionResult(
                            output = "Failed to download Android SDK from $SDK_URL",
                            isError = true
                        )
                    }

                    onProgress("Extracting Android SDK…")
                    sdkDest.mkdirs()
                    val sdkExtract = ProcessBuilder(
                        "unzip", "-o", "-q", sdkTmp.absolutePath,
                        "-d", sdkDest.absolutePath
                    ).redirectErrorStream(true).start()
                    // API-24-compatible timeout for extraction (5 min max)
                    val sdkWaitThread = Thread {
                        try { sdkExtract.waitFor() } catch (_: InterruptedException) {}
                    }
                    sdkWaitThread.start()
                    sdkWaitThread.join(TERMINAL_TIMEOUT_SECONDS * 1000L)
                    if (sdkWaitThread.isAlive) {
                        sdkExtract.destroy()
                        sdkTmp.delete()
                        return@withContext ToolExecutionResult(
                            output = "SDK extraction timed out after ${TERMINAL_TIMEOUT_SECONDS}s.",
                            isError = true
                        )
                    }
                    sdkTmp.delete()

                    if (sdkExtract.exitValue() != 0) {
                        return@withContext ToolExecutionResult(
                            output = "Failed to extract SDK archive (exit ${sdkExtract.exitValue()})",
                            isError = true
                        )
                    }

                    // Make platform-tools and build-tools binaries executable
                    val platformTools = File(sdkDest, "platform-tools")
                    if (platformTools.exists()) {
                        try {
                            ProcessBuilder("chmod", "-R", "+x", platformTools.absolutePath)
                                .redirectErrorStream(true).start().waitFor()
                        } catch (_: Exception) { /* non-fatal */ }
                    }
                    val buildTools = File(sdkDest, "build-tools")
                    if (buildTools.exists()) {
                        buildTools.listFiles()?.forEach { versionDir ->
                            try {
                                ProcessBuilder("chmod", "-R", "+x", versionDir.absolutePath)
                                    .redirectErrorStream(true).start().waitFor()
                            } catch (_: Exception) { /* non-fatal */ }
                        }
                    }

                    summary.appendLine("✅ Android SDK installed at $sdkDest")
                    onProgress("Android SDK installed.")
                } else {
                    summary.appendLine("✅ Android SDK already installed.")
                    onProgress("Android SDK already installed.")
                }

                ToolExecutionResult(
                    output = summary.toString().trimEnd(),
                    isError = false
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult(
                output = "Environment setup failed: ${e.message}",
                isError = true
            )
        }
    }

    /**
     * Downloads a file from [urlStr] and saves it to [dest] using [HttpURLConnection].
     *
     * Follows HTTP redirects and reports download progress via [onProgress].
     *
     * @return `true` if the download succeeded, `false` otherwise.
     */
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
            false // too many redirects
        } catch (e: Exception) {
            onProgress("Download error: ${e.message}")
            false
        }
    }

    // ──────────────────────────────────────────────
    //  advanced_terminal
    // ──────────────────────────────────────────────

    /**
     * Executes a shell command with the full Android build environment injected.
     *
     * Uses [ProcessBuilder] with JAVA_HOME, ANDROID_HOME, and an augmented PATH so
     * Gradle, javac, aapt2, and other build tools are available. The process is killed
     * if it exceeds [TERMINAL_TIMEOUT_SECONDS].
     *
     * Uses the API-24-compatible timeout pattern: [Thread.join] with millis and
     * [Process.destroy] instead of `waitFor(long, TimeUnit)` / `destroyForcibly()`
     * which require API 26.
     *
     * @param command Shell command to execute.
     * @param workingDirectory Absolute path to use as the working directory.
     * @return The combined stdout/stderr output with exit code.
     */
    suspend fun executeWithEnv(command: String, workingDirectory: String): ToolExecutionResult {
        val workDir = File(workingDirectory)

        if (!workDir.exists() || !workDir.isDirectory) {
            return ToolExecutionResult(
                output = "Working directory not found: $workingDirectory",
                isError = true
            )
        }

        return try {
            withContext(Dispatchers.IO) {
                val process = ProcessBuilder("/bin/sh", "-c", command)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["ANDROID_HOME"] = androidHome
                        environment()["JAVA_HOME"] = javaHome
                        environment()["PATH"] = buildPathVariable()
                    }
                    .start()

                // Read output on a dedicated thread to prevent pipe-buffer deadlock.
                // StringBuffer (vs StringBuilder) provides thread safety in case readerThread
                // is still draining after join() times out.
                val outputBuffer = StringBuffer()
                val readerThread = Thread {
                    try {
                        process.inputStream.bufferedReader().use { reader ->
                            reader.lineSequence().forEach { line ->
                                if (outputBuffer.length < MAX_OUTPUT_CHARS) {
                                    outputBuffer.appendLine(line)
                                }
                            }
                        }
                    } catch (_: Exception) { /* process killed — exit gracefully */ }
                }
                readerThread.start()

                // API-24-compatible timeout: run process.waitFor() on a wait thread,
                // then join() with a timeout (Thread.join(millis) has been API 1 since day 1).
                val waitThread = Thread {
                    try { process.waitFor() } catch (_: InterruptedException) { /* interrupted during timeout handling */ }
                }
                waitThread.start()
                waitThread.join(TERMINAL_TIMEOUT_SECONDS * 1000L)

                val completed = !waitThread.isAlive
                if (!completed) {
                    process.destroy() // SIGTERM — process.destroyForcibly() requires API 26
                    readerThread.interrupt()
                    return@withContext ToolExecutionResult(
                        output = "⏱ Command timed out after ${TERMINAL_TIMEOUT_SECONDS}s: $command",
                        isError = true
                    )
                }

                readerThread.join(2_000L) // wait for reader to drain (max 2s)

                val exitCode = process.exitValue()
                val output = outputBuffer.toString().trimEnd()
                val truncationNote =
                    if (outputBuffer.length >= MAX_OUTPUT_CHARS) "\n[OUTPUT TRUNCATED]" else ""

                val resultText = buildString {
                    appendLine("$ $command")
                    if (output.isNotEmpty()) appendLine(output)
                    append("[exit: $exitCode]$truncationNote")
                }

                ToolExecutionResult(output = resultText, isError = exitCode != 0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult(
                output = "Failed to execute command '$command': ${e.message}",
                isError = true
            )
        }
    }

    /**
     * Builds the PATH variable with JDK and SDK tool directories prepended
     * to the system PATH.
     */
    private fun buildPathVariable(): String {
        val systemPath = System.getenv("PATH") ?: "/usr/bin:/bin"
        val sdkDir = File(androidHome)

        // Find the latest build-tools version directory
        val buildToolsDir = File(sdkDir, "build-tools")
        val latestBuildTools = if (buildToolsDir.exists()) {
            buildToolsDir.listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.name }
                ?.absolutePath
        } else null

        return buildString {
            append("$javaHome/bin")
            if (latestBuildTools != null) {
                append(":$latestBuildTools")
            }
            val platformTools = File(sdkDir, "platform-tools")
            if (platformTools.exists()) {
                append(":${platformTools.absolutePath}")
            }
            append(":$systemPath")
        }
    }
}
