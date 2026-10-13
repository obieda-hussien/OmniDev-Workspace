package com.omnidev.workspace.data.tools

import android.content.Context
import java.io.File
import com.omnidev.workspace.data.tools.ToolExecutionResult

@Suppress("UNUSED_PARAMETER")
object EnvironmentSetupManager {
    const val TERMUX_ROOT = "/data/data/com.termux/files"
    const val TERMUX_PREFIX = "$TERMUX_ROOT/usr"
    const val TERMUX_HOME = "$TERMUX_ROOT/home"
    const val TERMUX_BIN = "$TERMUX_PREFIX/bin"
    const val TERMUX_BASH = "$TERMUX_BIN/bash"
    const val TERMUX_SH = "$TERMUX_BIN/sh"
    const val TERMUX_PYTHON3 = "$TERMUX_BIN/python3"
    const val TERMUX_PYTHON = "$TERMUX_BIN/python"
    const val TERMUX_NODE = "$TERMUX_BIN/node"
    const val TERMUX_GIT = "$TERMUX_BIN/git"
    const val TERMUX_PKG = "$TERMUX_BIN/pkg"
    const val TERMUX_APT = "$TERMUX_BIN/apt"
    const val TERMUX_PIP3 = "$TERMUX_BIN/pip3"
    const val TERMUX_NPM = "$TERMUX_BIN/npm"
    val currentState = SetupState(phase = SetupPhase.DEGRADED, error = "Backend not included")
    val stateFlow: kotlinx.coroutines.flow.StateFlow<SetupState> = kotlinx.coroutines.flow.MutableStateFlow(currentState)
    fun init(context: Context) = Unit
    fun isTermuxUsable(): Boolean = false
    fun buildEnvPrefix(): String = "Execution backend is not included in Lite."
    fun buildInlineEnvPrefix(): String = "Execution backend is not included in Lite."
    suspend fun probe(force: Boolean = false): SetupState = currentState
    suspend fun resolveBinary(binary: String): String? = null
    suspend fun executeShell(
        script: String,
        cwd: String? = null,
        @Suppress("UNUSED_PARAMETER") useBase64: Boolean? = null
    ): ToolExecutionResult = ToolExecutionResult("Execution backend is not included in Lite.", true)
    suspend fun runPython(
        code: String,
        extraArgs: String? = null,
        cwd: String? = null,
        venvPath: String? = null
    ): ToolExecutionResult = ToolExecutionResult("Execution backend is not included in Lite.", true)
    suspend fun pkgInstall(packages: String): ToolExecutionResult = ToolExecutionResult("Execution backend is not included in Lite.", true)
    suspend fun pipInstall(
        packages: String,
        upgrade: Boolean = false,
        venvPath: String? = null
    ): ToolExecutionResult = ToolExecutionResult("Execution backend is not included in Lite.", true)
    suspend fun npmCommand(subcommand: String, cwd: String? = null): ToolExecutionResult = ToolExecutionResult("Execution backend is not included in Lite.", true)
    suspend fun findBinary(name: String): String? = null
    suspend fun runBootstrap(
        plan: List<BootstrapStep>,
        onProgress: ((stepId: String, result: BootstrapResult) -> Unit)? = null
    ): List<Pair<String, BootstrapResult>> = emptyList()
    fun buildStandardBootstrapPlan(): List<BootstrapStep> = emptyList()
    suspend fun statusReport(): ToolExecutionResult = ToolExecutionResult("Execution backend is not included in Lite.", true)
    fun getToolDefinitions(): List<ToolDefinition> = emptyList()
    suspend fun executeTool(
        @Suppress("UNUSED_PARAMETER") name: String,
        arguments: Map<String, String>,
        scopePath: String? = null
    ): ToolExecutionResult = ToolExecutionResult("Execution backend is not included in Lite.", true)
    fun shellQuote(value: String): String = "Execution backend is not included in Lite."
}
