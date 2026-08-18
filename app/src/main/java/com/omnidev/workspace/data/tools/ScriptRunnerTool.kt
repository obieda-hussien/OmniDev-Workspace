package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * ScriptRunnerTool — [Localized] [Localized] [Localized] Norm Tier [Localized] [Localized]
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * [Localized] [Localized]:
 *
 * 1. **run_script** — [Localized] [Localized] [Localized] [Localized]:
 *    - `shell` → [Localized] [Localized] [Localized] `run_terminal` ([Localized] ProcessBuilder [Localized] [Localized] [Localized] [Localized])
 *    - `python` → [Localized] [Localized] [Localized] Python [Localized] Termux [Localized] [Localized]
 *    - `js`     → [Localized] [Localized] JavaScript [Localized] [Localized] [Localized] [Localized]
 *
 * 2. **eval_expression** — [Localized] [Localized] [Localized]/[Localized] [Localized] [Localized]:
 *    - [Localized]: +[Localized] -[Localized] *[Localized] /[Localized] %[Localized] >[Localized] <[Localized] ==[Localized] !=[Localized] &&[Localized] ||[Localized] !
 *    - Recursive-descent parser — [Localized] eval() [Localized] reflection
 *    - [Localized] [Localized] [Localized] code injection
 *
 * ## Mobile-First:
 * - [Localized] [Localized] [Localized]
 * - [Localized] [Localized] < 1ms [Localized] [Localized]
 * - Timeout [Localized] [Localized] ([Localized] 5000ms)
 *
 * ## [Localized]:
 * - shell/python [Localized] [Localized] [Localized] — [Localized] [Localized] [Localized]
 * - JS evaluator [Localized] [Localized] [Localized] [Localized]/[Localized]
 * - [Localized] eval()[Localized] [Localized] Reflection[Localized] [Localized] ClassLoader
 */
class ScriptRunnerTool {

    // ──────────────────────────────────────────────────────────────────────────
    // Tool Definitions
    // ──────────────────────────────────────────────────────────────────────────

    fun getDefinitions(): List<ToolDefinition> = listOf(

        ToolDefinition(
            name = "run_script",
            description = """[Localized] [Localized] [Localized] [Localized] [Localized].
[Localized] [Localized]:
- shell  → [Localized] [Localized] ProcessBuilder [Localized] [Localized] [Localized] [Localized] run_terminal
- python → [Localized] [Localized] Termux Python [Localized] [Localized] [Localized]
- js     → [Localized] [Localized] JavaScript [Localized] ([Localized] [Localized] [Localized] [Localized])

[Localized]: shell [Localized]python [Localized] [Localized] terminal_access (NORM+).
[Localized] [Localized] [Localized]/[Localized] [Localized] [Localized] eval_expression.
""",
            parameters = listOf(
                ToolParameter(
                    name = "language",
                    type = "string",
                    description = "[Localized] [Localized]: 'js' | 'python' | 'shell'",
                    required = true
                ),
                ToolParameter(
                    name = "code",
                    type = "string",
                    description = "[Localized] [Localized] [Localized] [Localized]",
                    required = true
                ),
                ToolParameter(
                    name = "timeout_ms",
                    type = "string",
                    description = "[Localized] [Localized] [Localized] [Localized] ([Localized]: 5000)",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "eval_expression",
            description = """[Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
[Localized]:
- [Localized] [Localized]: +[Localized] -[Localized] *[Localized] /[Localized] %[Localized] [Localized] [Localized]
- [Localized]: >[Localized] <[Localized] >=[Localized] <=[Localized] ==[Localized] !=
- [Localized]: &&[Localized] ||[Localized] !
- [Localized] [Localized] [Localized]
- [Localized] [Localized] [Localized]
- [Localized] boolean: true/false

[Localized]:
- "2 + 3 * 4" → 14
- "(2 + 3) * 4" → 20
- "10 > 5 && 3 < 7" → true
- "100 % 7" → 2
- "!false || (3 == 3)" → true

[Localized] [Localized] — [Localized] eval()[Localized] [Localized] reflection[Localized] [Localized] access [Localized].
""",
            parameters = listOf(
                ToolParameter(
                    name = "expression",
                    type = "string",
                    description = "[Localized] [Localized] [Localized]",
                    required = true
                )
            )
        )
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Execution
    // ──────────────────────────────────────────────────────────────────────────

    /** [Localized] null [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] wrapper. */
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
     * [Localized] JavaScript — [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized].
     * [Localized] [Localized] [Localized] [Localized] [Localized] console.log [Localized] [Localized] [Localized] [Localized] [Localized].
     */
    private suspend fun runJavaScript(code: String, timeoutMs: Long): ToolExecutionResult {
        return withTimeoutOrNull(timeoutMs) {
            try {
                // [Localized] [Localized] ScriptEngineManager [Localized] [Localized] [Localized] (JVM [Localized] [Localized] Android)
                tryScriptEngineManager(code)
                    ?: runJsWithBasicInterpreter(code)
            } catch (e: Exception) {
                ToolExecutionResult("JS execution error: ${e.message}", isError = true)
            }
        } ?: ToolExecutionResult("JS execution timed out after ${timeoutMs}ms", isError = true)
    }

    /**
     * [Localized] ScriptEngineManager ([Localized] [Localized] JVM/Roboelectric[Localized] [Localized] [Localized] [Localized] Android runtime).
     * [Localized] null [Localized] [Localized] [Localized] [Localized].
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
            null // ScriptEngineManager [Localized] [Localized] (Android runtime)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * [Localized] JavaScript [Localized] [Localized] [Localized].
     * [Localized]: [Localized] [Localized] [Localized] [Localized] [Localized] String literals.
     */
    private fun runJsWithBasicInterpreter(code: String): ToolExecutionResult {
        val trimmed = code.trim()

        // [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] function/var/let/const/console
        val hasComplexKeywords = listOf("function", "var ", "let ", "const ", "console.", "return ", "if ", "for ", "while ").any {
            trimmed.contains(it)
        }

        if (hasComplexKeywords || trimmed.contains('\n')) {
            // [Localized] [Localized] [Localized]: [Localized] [Localized] [Localized] Termux Node.js
            return runViaTermuxNode(trimmed)
        }

        // [Localized] eval_expression [Localized] [Localized]
        return try {
            val parser = SafeExpressionParser(trimmed)
            val result = parser.parse()
            ToolExecutionResult(formatEvalResult(result))
        } catch (e: Exception) {
            // [Localized] [Localized]: Termux
            runViaTermuxNode(trimmed)
        }
    }

    /**
     * [Localized] [Localized] Termux Node.js [Localized] [Localized] [Localized].
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
     * [Localized] Python [Localized] Termux.
     */
    private suspend fun runPython(code: String, timeoutMs: Long): ToolExecutionResult {
        return withTimeoutOrNull(timeoutMs) {
            try {
                // [Localized] python3 [Localized] [Localized] python
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
     * [Localized] Shell — [Localized] sh/bash [Localized].
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
     * [Localized] [Localized] [Localized] [Localized] Timeout.
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

        /** [Localized] [Localized] [Localized] [Localized] [Localized] [Localized] */
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
// SafeExpressionParser — [Localized] [Localized] [Localized] [Localized]
// Recursive-descent parser — [Localized] eval()[Localized] [Localized] reflection
// ════════════════════════════════════════════════════════════════════════════

/** [Localized] [Localized] [Localized] [Localized] [Localized] */
class ExpressionParseException(message: String) : Exception(message)

/**
 * [Localized] [Localized] [Localized]/[Localized] [Localized].
 *
 * [Localized] [Localized] ([Localized] [Localized] [Localized] [Localized] [Localized]):
 *   expr     → or_expr
 *   or_expr  → and_expr ('||' and_expr)*
 *   and_expr → not_expr ('&&' not_expr)*
 *   not_expr → '!' not_expr | cmp_expr
 *   cmp_expr → add_expr (('>'|'<'|'>='|'<='|'=='|'!=') add_expr)?
 *   add_expr → mul_expr (('+'|'-') mul_expr)*
 *   mul_expr → unary (('*'|'/'|'%') unary)*
 *   unary    → '-' unary | primary
 *   primary  → NUMBER | 'true' | 'false' | '(' expr ')'
 */
internal class SafeExpressionParser(private val input: String) {

    private var pos = 0

    /** [Localized] [Localized] [Localized] */
    fun parse(): Any {
        skipWhitespace()
        val result = parseOr()
        skipWhitespace()
        if (pos < input.length) {
            throw ExpressionParseException(
                "Unexpected character at position $pos: '${input[pos]}'"
            )
        }
        return result
    }

    // ── [Localized] [Localized] ──────────────────────────────────────────────────

    private fun parseOr(): Any {
        var left = parseAnd()
        while (peek("||")) {
            consume("||")
            val right = parseAnd()
            left = toBoolean(left) || toBoolean(right)
        }
        return left
    }

    private fun parseAnd(): Any {
        var left = parseNot()
        while (peek("&&")) {
            consume("&&")
            val right = parseNot()
            left = toBoolean(left) && toBoolean(right)
        }
        return left
    }

    private fun parseNot(): Any {
        skipWhitespace()
        if (pos < input.length && input[pos] == '!') {
            pos++ // consume '!'
            // [Localized] [Localized] [Localized] [Localized] !=
            if (pos < input.length && input[pos] == '=') {
                pos-- // [Localized] [Localized] [Localized] cmp_expr
                return parseComparison()
            }
            val operand = parseNot()
            return !toBoolean(operand)
        }
        return parseComparison()
    }

    private fun parseComparison(): Any {
        val left = parseAddition()
        skipWhitespace()
        val op = when {
            peek(">=") -> { consume(">="); ">=" }
            peek("<=") -> { consume("<="); "<=" }
            peek("==") -> { consume("=="); "==" }
            peek("!=") -> { consume("!="); "!=" }
            peek(">")  -> { consume(">");  ">" }
            peek("<")  -> { consume("<");  "<" }
            else       -> return left
        }
        val right = parseAddition()
        return when (op) {
            ">"  -> toDouble(left) > toDouble(right)
            "<"  -> toDouble(left) < toDouble(right)
            ">=" -> toDouble(left) >= toDouble(right)
            "<=" -> toDouble(left) <= toDouble(right)
            "==" -> toDouble(left) == toDouble(right)
            "!=" -> toDouble(left) != toDouble(right)
            else -> throw ExpressionParseException("Unknown operator: $op")
        }
    }

    private fun parseAddition(): Any {
        var left = parseMultiplication()
        while (true) {
            skipWhitespace()
            val op = when {
                pos < input.length && input[pos] == '+' -> { pos++; "+" }
                pos < input.length && input[pos] == '-' -> { pos++; "-" }
                else -> break
            }
            val right = parseMultiplication()
            left = when (op) {
                "+" -> toDouble(left) + toDouble(right)
                "-" -> toDouble(left) - toDouble(right)
                else -> left
            }
        }
        return left
    }

    private fun parseMultiplication(): Any {
        var left = parseUnary()
        while (true) {
            skipWhitespace()
            val op = when {
                pos < input.length && input[pos] == '*' -> { pos++; "*" }
                pos < input.length && input[pos] == '/' -> { pos++; "/" }
                pos < input.length && input[pos] == '%' -> { pos++; "%" }
                else -> break
            }
            val right = parseUnary()
            left = when (op) {
                "*" -> toDouble(left) * toDouble(right)
                "/" -> {
                    val r = toDouble(right)
                    if (r == 0.0) throw ArithmeticException("Division by zero")
                    toDouble(left) / r
                }
                "%" -> {
                    val r = toDouble(right)
                    if (r == 0.0) throw ArithmeticException("Modulo by zero")
                    toDouble(left) % r
                }
                else -> left
            }
        }
        return left
    }

    private fun parseUnary(): Any {
        skipWhitespace()
        if (pos < input.length && input[pos] == '-') {
            pos++
            val operand = parseUnary()
            return -toDouble(operand)
        }
        if (pos < input.length && input[pos] == '+') {
            pos++
            return parseUnary()
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Any {
        skipWhitespace()
        if (pos >= input.length) throw ExpressionParseException("Unexpected end of expression")

        // [Localized]
        if (input[pos] == '(') {
            pos++ // consume '('
            val result = parseOr()
            skipWhitespace()
            if (pos >= input.length || input[pos] != ')') {
                throw ExpressionParseException("Expected ')' at position $pos")
            }
            pos++ // consume ')'
            return result
        }

        // boolean literals
        if (input.startsWith("true", pos)) {
            pos += 4
            return true
        }
        if (input.startsWith("false", pos)) {
            pos += 5
            return false
        }

        // [Localized] ([Localized] [Localized] [Localized])
        if (input[pos].isDigit() || (input[pos] == '.' && pos + 1 < input.length && input[pos + 1].isDigit())) {
            return parseNumber()
        }

        throw ExpressionParseException(
            "Unexpected token at position $pos: '${input.substring(pos, minOf(pos + 10, input.length))}'"
        )
    }

    private fun parseNumber(): Double {
        val start = pos
        while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) {
            pos++
        }
        val numStr = input.substring(start, pos)
        return numStr.toDoubleOrNull()
            ?: throw ExpressionParseException("Invalid number: $numStr")
    }

    // ── [Localized] [Localized] ──────────────────────────────────────────────────────

    private fun skipWhitespace() {
        while (pos < input.length && input[pos].isWhitespace()) pos++
    }

    private fun peek(s: String): Boolean {
        skipWhitespace()
        return input.startsWith(s, pos)
    }

    private fun consume(s: String) {
        skipWhitespace()
        if (!input.startsWith(s, pos)) {
            throw ExpressionParseException("Expected '$s' at position $pos")
        }
        pos += s.length
    }

    private fun toDouble(v: Any): Double = when (v) {
        is Double  -> v
        is Boolean -> if (v) 1.0 else 0.0
        else -> v.toString().toDoubleOrNull()
            ?: throw ExpressionParseException("Cannot convert $v to number")
    }

    private fun toBoolean(v: Any): Boolean = when (v) {
        is Boolean -> v
        is Double  -> v != 0.0
        else -> v.toString().lowercase() == "true"
    }
}

/**
 * [Localized] [Localized] [Localized] — wrapper [Localized] [Localized] ScriptRunnerTool.
 * [Localized] [Localized].
 */
internal fun Any.formatResult(): String = ScriptRunnerTool.formatEvalResult(this)
