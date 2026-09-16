package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Compatibility Python runtime facade.
 *
 * Python installed by Termux MUST execute inside the Termux process. Older versions
 * of this class tried to execute `/data/data/com.termux/.../python` from Shizuku/root
 * with PATH/LD_PRELOAD injection, which crosses Android app sandboxes and is not a
 * supported Termux execution model.
 *
 * All actions now delegate to [EnvironmentSetupManager], whose canonical transport is
 * Termux RunCommandService. The tool surface is retained so stored workflows do not
 * break while new agent work should prefer `agent_runtime`.
 */
object PythonRuntimeManager {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "python_runtime",
            description = """
Legacy-compatible Python manager backed by the real Termux RunCommandService.
Prefer `agent_runtime` for new work.

Actions:
• health_check / find_interpreter / version / install_python / self_heal
• run_code — code, optional args, venv_name
• run_file — file_path, optional args, venv_name
• run_module — module, optional args, venv_name
• pip_install — packages, optional upgrade, venv_name
• pip_uninstall — packages, optional venv_name
• pip_list / pip_show / pip_search / which_pip
• venv_create / venv_list / venv_delete / venv_run

All Termux Python/package operations execute inside Termux. This tool never runs a
Termux private binary through Shizuku/root and never injects LD_PRELOAD.
""".trimIndent(),
            parameters = listOf(
                ToolParameter("action", "string", "Python runtime action.", required = true),
                ToolParameter("code", "string", "Python source for run_code/venv_run.", required = false),
                ToolParameter("file_path", "string", "Python file path for run_file.", required = false),
                ToolParameter("module", "string", "Python module for run_module.", required = false),
                ToolParameter("packages", "string", "Package names.", required = false),
                ToolParameter("package_name", "string", "Single package name.", required = false),
                ToolParameter("venv_name", "string", "Managed virtual environment name.", required = false),
                ToolParameter("args", "string", "Extra command arguments.", required = false),
                ToolParameter("upgrade", "string", "true to upgrade packages.", required = false),
                ToolParameter("query", "string", "Package search query.", required = false)
            )
        )
    )

    suspend fun execute(action: String, args: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            when (action.lowercase().trim()) {
                "health_check" -> healthCheck()
                "find_interpreter" -> findInterpreter()
                "install_python" -> EnvironmentSetupManager.pkgInstall("python")
                "version" -> runPythonShell("pythonVersion")
                "run_code" -> {
                    val code = args["code"] ?: return@withContext err("run_code requires 'code'")
                    EnvironmentSetupManager.runPython(
                        code = code,
                        extraArgs = args["args"],
                        venvPath = managedVenvPath(args["venv_name"])
                    )
                }
                "run_file" -> runFile(args)
                "run_module" -> runModule(args)
                "pip_install" -> {
                    val packages = args["packages"] ?: return@withContext err("pip_install requires 'packages'")
                    EnvironmentSetupManager.pipInstall(
                        packages = packages,
                        upgrade = args["upgrade"].equals("true", ignoreCase = true),
                        venvPath = managedVenvPath(args["venv_name"])
                    )
                }
                "pip_uninstall" -> pipUninstall(args)
                "pip_list" -> pipCommand("list --format=columns", args["venv_name"])
                "pip_show" -> {
                    val pkg = sanitizePackage(args["package_name"])
                        ?: return@withContext err("pip_show requires a valid package_name")
                    pipCommand("show ${EnvironmentSetupManager.shellQuote(pkg)}", args["venv_name"])
                }
                "pip_search" -> pipSearch(args)
                "which_pip" -> pipCommand("--version", args["venv_name"])
                "venv_create" -> venvCreate(args)
                "venv_list" -> venvList()
                "venv_delete" -> venvDelete(args)
                "venv_run" -> {
                    val code = args["code"] ?: return@withContext err("venv_run requires 'code'")
                    val venv = managedVenvPath(args["venv_name"])
                        ?: return@withContext err("venv_run requires a valid venv_name")
                    EnvironmentSetupManager.runPython(code = code, venvPath = venv)
                }
                "self_heal" -> selfHeal()
                else -> err("Unknown python_runtime action '$action'. Prefer agent_runtime for new workflows.")
            }
        }

    private suspend fun healthCheck(): ToolExecutionResult {
        val state = EnvironmentSetupManager.probe(force = true)
        val python = state.runtime("python3").takeIf { it.available }
            ?: state.runtime("python").takeIf { it.available }
        val transport = EnvironmentSetupManager.executeShell(
            "printf 'prefix=%s\\n' \"\$PREFIX\"; id; command -v python3 || command -v python || true"
        )
        val smoke = if (python != null && !transport.isError) {
            EnvironmentSetupManager.runPython("import sys; print(sys.version); print(sys.executable)")
        } else null

        return ToolExecutionResult(
            buildString {
                appendLine("Python runtime (Termux RunCommandService)")
                appendLine("Environment phase: ${state.phase}")
                appendLine("Termux transport: ${if (!transport.isError) "✅" else "❌"}")
                appendLine("Python: ${python?.path ?: "❌ not installed"}")
                if (smoke != null) {
                    appendLine("Smoke test: ${if (!smoke.isError) "✅" else "❌"}")
                    append(smoke.output.take(2_000))
                }
            }.trimEnd(),
            isError = transport.isError || python == null || smoke?.isError == true
        )
    }

    private suspend fun findInterpreter(): ToolExecutionResult {
        val path = EnvironmentSetupManager.resolveBinary("python3")
            ?: EnvironmentSetupManager.resolveBinary("python")
        return if (path != null) ToolExecutionResult("✅ Python in Termux: $path")
        else err("Python is not installed in Termux. Use agent_runtime action=pkg_install packages=python.")
    }

    private suspend fun runPythonShell(mode: String): ToolExecutionResult = when (mode) {
        "pythonVersion" -> EnvironmentSetupManager.executeShell(
            "py=\$(command -v python3 || command -v python || true); " +
                "[ -n \"\$py\" ] || { echo 'Python missing' >&2; exit 127; }; \"\$py\" --version"
        )
        else -> err("Unsupported Python shell mode")
    }

    private suspend fun runFile(args: Map<String, String>): ToolExecutionResult {
        val filePath = args["file_path"]?.takeIf(String::isNotBlank)
            ?: return err("run_file requires 'file_path'")
        val venv = managedVenvPath(args["venv_name"])
        val selector = if (venv != null) {
            "${EnvironmentSetupManager.shellQuote(venv)}/bin/python"
        } else {
            "\$(command -v python3 || command -v python || true)"
        }
        val extra = args["args"]?.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
        return EnvironmentSetupManager.executeShell(
            "py=$selector; [ -x \"\$py\" ] || { echo 'Python missing' >&2; exit 127; }; " +
                "\"\$py\" ${EnvironmentSetupManager.shellQuote(filePath)}$extra"
        )
    }

    private suspend fun runModule(args: Map<String, String>): ToolExecutionResult {
        val module = args["module"]?.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*")) }
            ?: return err("run_module requires a valid module")
        val venv = managedVenvPath(args["venv_name"])
        val selector = if (venv != null) {
            "${EnvironmentSetupManager.shellQuote(venv)}/bin/python"
        } else {
            "\$(command -v python3 || command -v python || true)"
        }
        val extra = args["args"]?.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()
        return EnvironmentSetupManager.executeShell(
            "py=$selector; [ -x \"\$py\" ] || { echo 'Python missing' >&2; exit 127; }; " +
                "\"\$py\" -m ${EnvironmentSetupManager.shellQuote(module)}$extra"
        )
    }

    private suspend fun pipUninstall(args: Map<String, String>): ToolExecutionResult {
        val packages = args["packages"]?.split(Regex("\\s+"))
            ?.mapNotNull(::sanitizePackage)
            ?.takeIf { it.isNotEmpty() }
            ?: return err("pip_uninstall requires valid package names")
        return pipCommand(
            "uninstall -y ${packages.joinToString(" ") { EnvironmentSetupManager.shellQuote(it) }}",
            args["venv_name"]
        )
    }

    private suspend fun pipSearch(args: Map<String, String>): ToolExecutionResult {
        val query = sanitizePackage(args["query"] ?: args["package_name"])
            ?: return err("pip_search requires a valid query")
        return pipCommand("index versions ${EnvironmentSetupManager.shellQuote(query)}", args["venv_name"])
    }

    private suspend fun pipCommand(command: String, venvName: String?): ToolExecutionResult {
        val venv = managedVenvPath(venvName)
        val selector = if (venv != null) {
            "${EnvironmentSetupManager.shellQuote(venv)}/bin/python"
        } else {
            "\$(command -v python3 || command -v python || true)"
        }
        return EnvironmentSetupManager.executeShell(
            "py=$selector; [ -x \"\$py\" ] || { echo 'Python missing' >&2; exit 127; }; " +
                "\"\$py\" -m pip $command"
        )
    }

    private suspend fun venvCreate(args: Map<String, String>): ToolExecutionResult {
        val path = managedVenvPath(args["venv_name"])
            ?: return err("venv_create requires a safe venv_name")
        return EnvironmentSetupManager.executeShell(
            "py=\$(command -v python3 || command -v python || true); " +
                "[ -n \"\$py\" ] || { echo 'Python missing' >&2; exit 127; }; " +
                "mkdir -p \"\$HOME/.omnidev/venvs\"; \"\$py\" -m venv ${EnvironmentSetupManager.shellQuote(path)}; " +
                "${EnvironmentSetupManager.shellQuote(path)}/bin/python -V"
        )
    }

    private suspend fun venvList(): ToolExecutionResult = EnvironmentSetupManager.executeShell(
        "base=\"\$HOME/.omnidev/venvs\"; [ -d \"\$base\" ] || { echo 'No managed virtual environments.'; exit 0; }; " +
            "find \"\$base\" -mindepth 1 -maxdepth 1 -type d -printf '%f\\n' | sort"
    )

    private suspend fun venvDelete(args: Map<String, String>): ToolExecutionResult {
        val path = managedVenvPath(args["venv_name"])
            ?: return err("venv_delete requires a safe venv_name")
        return EnvironmentSetupManager.executeShell(
            "target=${EnvironmentSetupManager.shellQuote(path)}; " +
                "case \"\$target\" in \"\$HOME/.omnidev/venvs/\"*) rm -rf -- \"\$target\" ;; " +
                "*) echo 'Refusing unsafe venv path' >&2; exit 2 ;; esac"
        )
    }

    private suspend fun selfHeal(): ToolExecutionResult {
        val probe = EnvironmentSetupManager.runPython("print('OMNIDEV_PYTHON_OK')")
        if (!probe.isError && probe.output.contains("OMNIDEV_PYTHON_OK")) {
            return ToolExecutionResult("✅ Python is healthy; no repair needed.\n${probe.output}")
        }
        val reinstall = EnvironmentSetupManager.executeShell(
            "DEBIAN_FRONTEND=noninteractive pkg install -y python"
        )
        if (reinstall.isError) return reinstall
        return EnvironmentSetupManager.runPython("import sys; print('OMNIDEV_PYTHON_OK'); print(sys.executable)")
    }

    /**
     * Managed venvs live inside Termux HOME and therefore are represented with a
     * shell-expanded `$HOME` path. They are never created in `/data/local/tmp`.
     */
    private fun managedVenvPath(name: String?): String? {
        val clean = name?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,64}")) } ?: return null
        return "\$HOME/.omnidev/venvs/$clean"
    }

    private fun sanitizePackage(value: String?): String? = value?.trim()
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,128}")) }

    private fun err(message: String) = ToolExecutionResult(message, isError = true)
}
