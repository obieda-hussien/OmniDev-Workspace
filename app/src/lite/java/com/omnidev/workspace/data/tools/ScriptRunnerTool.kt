package com.omnidev.workspace.data.tools

class ScriptRunnerTool {
    fun getDefinitions(): List<ToolDefinition> = emptyList()
    suspend fun execute(name: String, args: Map<String, String>): ToolExecutionResult? = if (name in HANDLED) ToolExecutionResult("Script execution is not included in Lite.", true) else null
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

