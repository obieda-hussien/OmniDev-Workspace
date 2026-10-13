package com.omnidev.workspace.data.tools

/** Exception for expression parsing errors */
class ExpressionParseException(message: String) : Exception(message)

/**
 * Restricted mathematical and logical expression evaluator.
 *
 * Supported grammar, ordered from lowest to highest precedence:
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

    /** Main entry point */
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

    // ── Precedence levels ──────────────────────────────────────────────────

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
            // Ensure this is not !=
            if (pos < input.length && input[pos] == '=') {
                pos-- // Rewind; cmp_expr handles this operator
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

        // Parentheses
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

        // Integer or decimal numbers
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

    // ── Helpers ──────────────────────────────────────────────────────

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
 * Result formatting wrapper for callers outside ScriptRunnerTool.
 * Exposed for tests.
 */
internal fun Any.formatResult(): String = ScriptRunnerTool.formatEvalResult(this)
