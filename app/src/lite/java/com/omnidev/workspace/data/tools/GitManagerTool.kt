package com.omnidev.workspace.data.tools

@Suppress("UNUSED_PARAMETER")
object GitManagerTool {
    fun getToolDefinitions(): List<ToolDefinition> = emptyList()
    suspend fun execute(
        action: String,
        scopePath: String,
        commitMessage: String? = null,
        branch: String? = null,
        files: String? = null,
        remote: String? = null,
        authorName: String? = null,
        authorEmail: String? = null
    ): ToolExecutionResult = ToolExecutionResult("Local Git execution is not included in Lite.", true)
    suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String) = ToolExecutionResult("Local Git execution is not included in Lite.", true)
}
