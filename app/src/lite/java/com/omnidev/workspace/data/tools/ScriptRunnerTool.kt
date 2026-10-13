package com.omnidev.workspace.data.tools

class ScriptRunnerTool {
    fun getDefinitions(): List<ToolDefinition> = listOf(ToolDefinition("eval_expression",
        "Evaluate arithmetic, comparisons and boolean expressions locally with a restricted parser. No scripts or system access.",
        listOf(ToolParameter("expression", "string", "Expression, up to 512 characters"))))
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? {
        if (name !in HANDLED) return null
        if (name != "eval_expression") return ToolExecutionResult("Script execution is not included in Lite.", true)
        val expression = args["expression"].orEmpty()
        if (expression.isBlank() || expression.length > 512) return ToolExecutionResult("Expression must contain 1–512 characters.", true)
        return try { ToolExecutionResult(formatEvalResult(SafeExpressionParser(expression).parse())) }
        catch (error: Exception) { ToolExecutionResult("Expression error: ${error.message}", true) }
    }
    fun handles(name: String) = name in HANDLED
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

