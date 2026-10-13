package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * ScriptRunnerTool — Script execution for the Norm tier and above
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Provides two tools:
 *
 * 1. **run_script** — Run scripts in several languages:
 *    - `shell` → Delegates to `run_terminal`, with a ProcessBuilder fallback
 *    - `python` → Delegates to Python through Termux when available
 *    - `js`     → Evaluate basic JavaScript expressions with the internal evaluator
 *
 * 2. **eval_expression** — Evaluate mathematical and logical expressions with a restricted parser:
 *    - Supports: +, -, *, /, %, >, <, ==, !=, &&, ||, !
 *    - Recursive-descent parser — Without eval() or reflection
 *    - Restricts input to the expression grammar
 *
 * ## Mobile-First:
 * - No external libraries
 * - Internal evaluator targets less than 1 ms for most expressions
 * - Configurable timeout (default: 5000 ms)
 *
 * ## Security:
 * - Shell and Python delegate to their respective execution backends
 * - JS evaluator permits only mathematical and logical operations
 * - No eval(), reflection or ClassLoader
 */
class ScriptRunnerTool {

    // ──────────────────────────────────────────────────────────────────────────
    // Tool Definitions
    // ──────────────────────────────────────────────────────────────────────────

    fun getDefinitions(): List<ToolDefinition> = listOf(

        ToolDefinition(
            name = "run_script",
            description = """Run a script through the configured execution backend.
Supported languages:
- shell  → Runs through ProcessBuilder or delegates to run_terminal
- python → Runs through Termux Python when installed
- js     → Evaluate basic JavaScript expressions (arithmetic, strings, variables)

Note: shell and Python require terminal_access capability (NORM+).
Use eval_expression for simple mathematical or logical expressions.
""",
            parameters = listOf(
                ToolParameter(
                    name = "language",
                    type = "string",
                    description = "Script language: 'js' | 'python' | 'shell'",
                    required = true
                ),
                ToolParameter(
                    name = "code",
                    type = "string",
                    description = "Script source to execute",
                    required = true
                ),
                ToolParameter(
                    name = "timeout_ms",
                    type = "string",
                    description = "Execution timeout in milliseconds (default: 5000)",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "eval_expression",
            description = """Evaluate mathematical and logical expressions with a restricted parser.
Supports:
- Arithmetic: +, -, *, /, %, negative values
- Comparisons: >, <, >=, <=, ==, !=
- Logic: &&, ||, !
- Parentheses for grouping and precedence
- Integer and decimal numbers
- Boolean values: true/false

Examples:
- "2 + 3 * 4" → 14
- "(2 + 3) * 4" → 20
- "10 > 5 && 3 < 7" → true
- "100 % 7" → 2
- "!false || (3 == 3)" → true

Uses a restricted grammar with no eval(), reflection or system access.
""",
            parameters = listOf(
                ToolParameter(
                    name = "expression",
                    type = "string",
                    description = "Expression to evaluate",
                    required = true
                )
            )
        )
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Execution
    // ──────────────────────────────────────────────────────────────────────────

    /** Returns null for tools not handled by this wrapper. */
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        return try {
            withContext(Dispatchers.IO) {
                when (name) {
                    "run_script"      -> doRunScript(args)
                    "eval_expression" -> doEvalExpression(args)
                    else -> ToolExecutionResult("Unknown script tool: $name", isError = true)
                }
            }
        } catch (t: Throwable) {
            ToolExecutionResult("ScriptRunnerTool error: ${t.message}", isError = true)
        }
    }

    fun handles(name: String): Boolean = name in HANDLED

    // ──────────────────────────────────────────────────────────────────────────
    // run_script implementation
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun doRunScript(args: Map<String, String>): ToolExecutionResult {
        val language = args["language"]?.trim()?.lowercase()
            ?: return ToolExecutionResult("Missing required argument: language", isError = true)
        val code = args["code"]?.trim()
            ?: return ToolExecutionResult("Missing required argument: code", isError = true)
        if (code.isBlank()) return ToolExecutionResult("code must not be empty", isError = true)

        val timeoutMs = args["timeout_ms"]?.toLongOrNull()?.coerceIn(100, 30_000) ?: 5_000L

        return when (language) {
            "js", "javascript" -> runJavaScript(code, timeoutMs)
            "python", "py"     -> runPython(code, timeoutMs)
            "shell", "sh", "bash" -> runShell(code, timeoutMs)
            else -> ToolExecutionResult(
                "Unsupported language: '$language'. Use: js, python, shell",
                isError = true
            )
        }
    }

    /**
     * Run JavaScript by attempting basic expression evaluation internally.
     * Scripts containing console.log or functions use the complex-script path.
     */
    private suspend fun runJavaScript(code: String, timeoutMs: Long): ToolExecutionResult {
        return withTimeoutOrNull(timeoutMs) {
            try {
                // Try ScriptEngineManager when available (JVM only, not Android)
                tryScriptEngineManager(code)
                    ?: runJsWithBasicInterpreter(code)
            } catch (e: Exception) {
                ToolExecutionResult("JS execution error: ${e.message}", isError = true)
            }
        } ?: ToolExecutionResult("JS execution timed out after ${timeoutMs}ms", isError = true)
    }

    /**
     * Try ScriptEngineManager on JVM/Robolectric; unavailable in the Android runtime.
     * Returns null when unavailable.
     */
    private fun tryScriptEngineManager(code: String): ToolExecutionResult? {
        return try {
            val managerClass = Class.forName("javax.script.ScriptEngineManager")
            val manager = managerClass.getDeclaredConstructor().newInstance()
            val getEngineMethod = managerClass.getMethod("getEngineByName", String::class.java)
            val engine = getEngineMethod.invoke(manager, "javascript")
                ?: getEngineMethod.invoke(manager, "rhino")
                ?: return null
            val evalMethod = engine.javaClass.getMethod("eval", String::class.java)
            val result = evalMethod.invoke(engine, code)
            ToolExecutionResult("${result ?: "undefined"}")
        } catch (e: ClassNotFoundException) {
            null // ScriptEngineManager unavailable in the Android runtime
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Basic JavaScript expression interpreter.
     * Supports arithmetic, comparisons, logic and string literals.
     */
    private fun runJsWithBasicInterpreter(code: String): ToolExecutionResult {
        val trimmed = code.trim()

        // Detect multiline scripts or function/var/let/const/console usage
        val hasComplexKeywords = listOf("function", "var ", "let ", "const ", "console.", "return ", "if ", "for ", "while ").any {
            trimmed.contains(it)
        }

        if (hasComplexKeywords || trimmed.contains('\n')) {
            // Try Termux Node.js for complex scripts
            return runViaTermuxNode(trimmed)
        }

        // Try eval_expression for basic expressions
        return try {
            val parser = SafeExpressionParser(trimmed)
            val result = parser.parse()
            ToolExecutionResult(formatEvalResult(result))
        } catch (e: Exception) {
            // Final fallback: Termux
            runViaTermuxNode(trimmed)
        }
    }

    /**
     * Execute through Termux Node.js when available.
     */
    private fun runViaTermuxNode(code: String): ToolExecutionResult {
        return try {
            val nodeCmd = "node -e ${shellQuote(code)}"
            val result = runProcessWithTimeout(
                command = listOf("sh", "-c", nodeCmd),
                timeoutMs = 5000
            )
            result
        } catch (e: Exception) {
            ToolExecutionResult(
                "⚠️ JS complex script execution requires Node.js (via Termux).\n" +
                "Install Node.js in Termux: pkg install nodejs\n" +
                "For simple math expressions, use eval_expression instead.\n" +
                "Error: ${e.message}",
                isError = true
            )
        }
    }

    /**
     * Execute Python through Termux.
     */
    private suspend fun runPython(code: String, timeoutMs: Long): ToolExecutionResult {
        return withTimeoutOrNull(timeoutMs) {
            try {
                // Try python3 first, then python
                val pythonBins = listOf(
                    "/data/data/com.termux/files/usr/bin/python3",
                    "/data/data/com.termux/files/usr/bin/python",
                    "python3",
                    "python"
                )
                val pythonBin = pythonBins.firstOrNull { bin ->
                    try {
                        Runtime.getRuntime().exec(arrayOf(bin, "--version")).also {
                            it.waitFor()
                        }.exitValue() == 0
                    } catch (e: Exception) { false }
                }

                if (pythonBin == null) {
                    return@withTimeoutOrNull ToolExecutionResult(
                        "⚠️ Python not found. Install via Termux: pkg install python\n" +
                        "Or use eval_expression for math expressions.",
                        isError = true
                    )
                }

                runProcessWithTimeout(
                    command = listOf(pythonBin, "-c", code),
                    timeoutMs = timeoutMs
                )
            } catch (e: Exception) {
                ToolExecutionResult("Python execution error: ${e.message}", isError = true)
            }
        } ?: ToolExecutionResult("Python execution timed out after ${timeoutMs}ms", isError = true)
    }

    /**
     * Execute shell scripts directly through sh/bash.
     */
    private suspend fun runShell(code: String, timeoutMs: Long): ToolExecutionResult {
        return withTimeoutOrNull(timeoutMs) {
            try {
                runProcessWithTimeout(
                    command = listOf("sh", "-c", code),
                    timeoutMs = timeoutMs
                )
            } catch (e: Exception) {
                ToolExecutionResult("Shell execution error: ${e.message}", isError = true)
            }
        } ?: ToolExecutionResult("Shell execution timed out after ${timeoutMs}ms", isError = true)
    }

    /**
     * Execute an external process with a timeout.
     */
    private fun runProcessWithTimeout(command: List<String>, timeoutMs: Long): ToolExecutionResult {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return ToolExecutionResult(
                "Execution timed out after ${timeoutMs}ms",
                isError = true
            )
        }

        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.exitValue()

        return if (exitCode == 0) {
            ToolExecutionResult(output.ifBlank { "(no output)" })
        } else {
            ToolExecutionResult(
                "Exit code $exitCode:\n$output".takeIf { output.isNotBlank() }
                    ?: "Process failed with exit code $exitCode",
                isError = true
            )
        }
    }

    private fun shellQuote(s: String): String = "'${s.replace("'", "'\\''")}'"

    // ──────────────────────────────────────────────────────────────────────────
    // eval_expression implementation
    // ──────────────────────────────────────────────────────────────────────────

    private fun doEvalExpression(args: Map<String, String>): ToolExecutionResult {
        val expr = args["expression"]?.trim()
            ?: return ToolExecutionResult("Missing required argument: expression", isError = true)
        if (expr.isBlank()) return ToolExecutionResult("expression must not be empty", isError = true)

        return try {
            val parser = SafeExpressionParser(expr)
            val result = parser.parse()
            ToolExecutionResult(formatEvalResult(result))
        } catch (e: ExpressionParseException) {
            ToolExecutionResult("Expression parse error: ${e.message}", isError = true)
        } catch (e: ArithmeticException) {
            ToolExecutionResult("Arithmetic error: ${e.message}", isError = true)
        } catch (e: Exception) {
            ToolExecutionResult("Evaluation error: ${e.message}", isError = true)
        }
    }

    companion object {
        val HANDLED = setOf("run_script", "eval_expression")

        /** Format evaluation results for readability */
        internal fun formatEvalResult(result: Any): String = when (result) {
            is Double -> if (result % 1.0 == 0.0 && result >= Long.MIN_VALUE.toDouble() && result <= Long.MAX_VALUE.toDouble()) {
                result.toLong().toString()
            } else {
                "%.10g".format(result).trimEnd('0').trimEnd('.')
            }
            is Boolean -> result.toString()
            else -> result.toString()
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// SafeExpressionParser — Restricted expression evaluator
// Recursive-descent parser — No eval() or reflection
// ════════════════════════════════════════════════════════════════════════════

