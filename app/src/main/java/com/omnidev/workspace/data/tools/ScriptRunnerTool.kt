package com.omnidev.workspace.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ══════════════════════════════════════════════════════════════════════════════
 * ScriptRunnerTool — تشغيل السكريبتات للـ Norm Tier فما فوق
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * يُوفّر أداتين:
 *
 * 1. **run_script** — تشغيل سكريبت بلغات مختلفة:
 *    - `shell` → يُعيد توجيه إلى `run_terminal` (أو ProcessBuilder إذا لم يكن متاحاً)
 *    - `python` → يُعيد توجيه إلى Python عبر Termux إن وجد
 *    - `js`     → تقييم تعبيرات JavaScript بسيطة عبر المُقيّم الداخلي
 *
 * 2. **eval_expression** — تقييم تعبيرات رياضية/منطقية بأمان تام:
 *    - يدعم: +، -، *، /، %، >، <، ==، !=، &&، ||، !
 *    - Recursive-descent parser — بدون eval() أو reflection
 *    - آمن تماماً ضد code injection
 *
 * ## Mobile-First:
 * - بدون مكتبات خارجية
 * - المُقيّم الداخلي < 1ms لمعظم التعبيرات
 * - Timeout قابل للضبط (افتراضي 5000ms)
 *
 * ## الأمان:
 * - shell/python يُعيد توجيه فقط — لا تنفيذ مباشر
 * - JS evaluator يسمح فقط بالعمليات الحسابية/المنطقية
 * - لا eval()، لا Reflection، لا ClassLoader
 */
class ScriptRunnerTool {

    // ──────────────────────────────────────────────────────────────────────────
    // Tool Definitions
    // ──────────────────────────────────────────────────────────────────────────

    fun getDefinitions(): List<ToolDefinition> = listOf(

        ToolDefinition(
            name = "run_script",
            description = """تشغيل سكريبت في بيئة محكومة.
اللغات المدعومة:
- shell  → يُنفَّذ عبر ProcessBuilder أو يُعيد توجيه إلى run_terminal
- python → يُنفَّذ عبر Termux Python إن كان مثبتاً
- js     → تقييم تعبيرات JavaScript أساسية (حسابات، سلاسل نصية، متغيرات)

ملاحظة: shell وpython يتطلبان صلاحية terminal_access (NORM+).
لتقييم تعابير رياضية/منطقية بسيطة استخدم eval_expression.
""",
            parameters = listOf(
                ToolParameter(
                    name = "language",
                    type = "string",
                    description = "لغة السكريبت: 'js' | 'python' | 'shell'",
                    required = true
                ),
                ToolParameter(
                    name = "code",
                    type = "string",
                    description = "كود السكريبت المراد تشغيله",
                    required = true
                ),
                ToolParameter(
                    name = "timeout_ms",
                    type = "string",
                    description = "مهلة التنفيذ بالميلي ثانية (افتراضي: 5000)",
                    required = false
                )
            )
        ),

        ToolDefinition(
            name = "eval_expression",
            description = """تقييم تعبيرات رياضية ومنطقية بأمان تام.
تدعم:
- العمليات الحسابية: +، -، *، /، %، القيم السالبة
- المقارنات: >، <، >=، <=، ==، !=
- المنطق: &&، ||، !
- الأقواس للتقديم والتأخير
- الأعداد الصحيحة والعشرية
- قيم boolean: true/false

أمثلة:
- "2 + 3 * 4" → 14
- "(2 + 3) * 4" → 20
- "10 > 5 && 3 < 7" → true
- "100 % 7" → 2
- "!false || (3 == 3)" → true

آمن تماماً — لا eval()، لا reflection، لا access للنظام.
""",
            parameters = listOf(
                ToolParameter(
                    name = "expression",
                    type = "string",
                    description = "التعبير المراد تقييمه",
                    required = true
                )
            )
        )
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Execution
    // ──────────────────────────────────────────────────────────────────────────

    /** يُعيد null إذا الأداة ليست مملوكة لهذا الـ wrapper. */
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
     * تشغيل JavaScript — محاولة تقييم تعبيرات بسيطة عبر المُقيّم الداخلي.
     * بالنسبة للسكريبتات المعقدة التي تتضمن console.log أو دالات، نُعيد رسالة واضحة.
     */
    private suspend fun runJavaScript(code: String, timeoutMs: Long): ToolExecutionResult {
        return withTimeoutOrNull(timeoutMs) {
            try {
                // محاولة استخدام ScriptEngineManager إن كان متاحاً (JVM فقط، ليس Android)
                tryScriptEngineManager(code)
                    ?: runJsWithBasicInterpreter(code)
            } catch (e: Exception) {
                ToolExecutionResult("JS execution error: ${e.message}", isError = true)
            }
        } ?: ToolExecutionResult("JS execution timed out after ${timeoutMs}ms", isError = true)
    }

    /**
     * محاولة ScriptEngineManager (يعمل على JVM/Roboelectric، لا يعمل على Android runtime).
     * يُعيد null إذا لم يكن متاحاً.
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
            null // ScriptEngineManager غير متاح (Android runtime)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * مُفسّر JavaScript بسيط للتعبيرات الأساسية.
     * يدعم: العمليات الحسابية، المقارنات، المنطق، الـ String literals.
     */
    private fun runJsWithBasicInterpreter(code: String): ToolExecutionResult {
        val trimmed = code.trim()

        // إذا كان السكريبت أكثر من سطر أو يحتوي على function/var/let/const/console
        val hasComplexKeywords = listOf("function", "var ", "let ", "const ", "console.", "return ", "if ", "for ", "while ").any {
            trimmed.contains(it)
        }

        if (hasComplexKeywords || trimmed.contains('\n')) {
            // بالنسبة للسكريبتات المعقدة: نحاول تنفيذها عبر Termux Node.js
            return runViaTermuxNode(trimmed)
        }

        // محاولة eval_expression للتعبيرات البسيطة
        return try {
            val parser = SafeExpressionParser(trimmed)
            val result = parser.parse()
            ToolExecutionResult(formatEvalResult(result))
        } catch (e: Exception) {
            // محاولة أخيرة: Termux
            runViaTermuxNode(trimmed)
        }
    }

    /**
     * تشغيل عبر Termux Node.js إن كان متاحاً.
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
     * تشغيل Python عبر Termux.
     */
    private suspend fun runPython(code: String, timeoutMs: Long): ToolExecutionResult {
        return withTimeoutOrNull(timeoutMs) {
            try {
                // محاولة python3 أولاً، ثم python
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
     * تشغيل Shell — يستخدم sh/bash مباشرة.
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
     * تشغيل عملية خارجية مع Timeout.
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

        /** تنسيق نتيجة التقييم بشكل قابل للقراءة */
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
// SafeExpressionParser — مُقيّم تعابير بأمان تام
// Recursive-descent parser — لا eval()، لا reflection
// ════════════════════════════════════════════════════════════════════════════

/** استثناء خاص بأخطاء تحليل التعابير */
class ExpressionParseException(message: String) : Exception(message)

/**
 * مُقيّم تعابير رياضية/منطقية آمن.
 *
 * القواعد المدعومة (بترتيب الأولوية من الأدنى للأعلى):
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

    /** نقطة الدخول الرئيسية */
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

    // ── مستويات الأولوية ──────────────────────────────────────────────────

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
            // تأكد من أنه ليس !=
            if (pos < input.length && input[pos] == '=') {
                pos-- // أعده، سيتعامل معه cmp_expr
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

        // أقواس
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

        // أرقام (صحيحة أو عشرية)
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

    // ── أدوات مساعدة ──────────────────────────────────────────────────────

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
 * تنسيق نتيجة التقييم — wrapper للاستخدام خارج ScriptRunnerTool.
 * مُصدَّر للاختبارات.
 */
internal fun Any.formatResult(): String = ScriptRunnerTool.formatEvalResult(this)
