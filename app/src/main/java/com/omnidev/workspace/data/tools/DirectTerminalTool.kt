package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.tools.ToolDefinition
import org.json.JSONObject

object DirectTerminalTool {
    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "direct_terminal",
            description = "Executes commands directly in terminal without interpretation."
        )
    )

    fun execute(params: JSONObject): String {
        val command = params.optString("command", params.optString("code", ""))

        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            "Error: \${e.message}"
        }
    }
}
