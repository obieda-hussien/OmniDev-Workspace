package com.omnidev.workspace.data.tools

import android.content.Context
import java.io.File
import com.omnidev.workspace.data.tools.ToolExecutionResult

@Suppress("UNUSED_PARAMETER")
object ToolDownloaderEngine {
    const val INSTALL_DIR = ""
    val DALVIKVM_PATHS: List<String> = emptyList()
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

    val KNOWN_TOOLS: Map<String, KnownTool> = emptyMap()
    fun init(context: Context) = Unit
    suspend fun downloadFile(
        url: String,
        destFile: File,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ): Result<File> = Result.failure(IllegalStateException("Execution backend is not included in Lite."))
    suspend fun installTool(
        context: Context,
        toolName: String,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = Result.failure(IllegalStateException("Execution backend is not included in Lite."))
    suspend fun installCustomTool(
        context: Context,
        name: String,
        downloadUrl: String,
        mainClass: String = "",
        jvmArgs: String = "-Xmx512m",
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = Result.failure(IllegalStateException("Execution backend is not included in Lite."))
    suspend fun isInstalled(toolName: String): Boolean = false
    suspend fun getToolPath(toolName: String): String? = null
    suspend fun listInstalled(): List<String> = emptyList()
    fun findDalvikvm(): String? = null
    suspend fun statusReport(context: Context): String = "Execution backend is not included in Lite."
    suspend fun reinstallLauncher(
        context: Context,
        toolName: String,
        onProgress: ((String) -> Unit)? = null
    ): Result<String> = Result.failure(IllegalStateException("Execution backend is not included in Lite."))
    fun getToolDefinitions(): List<ToolDefinition> = emptyList()
    suspend fun executeTool(
        context: Context,
        action: String,
        args: Map<String, String>
    ): ToolExecutionResult = ToolExecutionResult("Execution backend is not included in Lite.", true)
    suspend fun installTool(args: Map<String, String>): ToolExecutionResult = ToolExecutionResult("Execution backend is not included in Lite.", true)
    suspend fun toolsStatus(): ToolExecutionResult = ToolExecutionResult("Execution backend is not included in Lite.", true)
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult = ToolExecutionResult("Execution backend is not included in Lite.", true)
    suspend fun downloadFile(url: String, destPath: String?): ToolExecutionResult = ToolExecutionResult("Execution backend is not included in Lite.", true)
    suspend fun downloadExec(url: String, extraArgs: String, cwd: String?): ToolExecutionResult = ToolExecutionResult("Execution backend is not included in Lite.", true)
    suspend fun downloadVerify(url: String, checksum: String, dest: String?): ToolExecutionResult = ToolExecutionResult("Execution backend is not included in Lite.", true)
}
