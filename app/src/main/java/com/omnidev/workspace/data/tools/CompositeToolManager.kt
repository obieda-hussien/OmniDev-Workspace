package com.omnidev.workspace.data.tools

/**
 * Delegates tool execution to [FileToolManager] and [MemoryManager],
 * presenting their combined tool set as a single [ToolManager] to [AgentPipeline].
 */
class CompositeToolManager(
    private val fileToolManager: FileToolManager,
    val memoryManager: MemoryManager
) : ToolManager {

    override fun getToolDefinitions(): List<ToolDefinition> =
        fileToolManager.getToolDefinitions() + memoryManager.getToolDefinitions()

    override suspend fun executeTool(
        name: String,
        arguments: Map<String, String>,
        scopePath: String
    ): ToolExecutionResult {
        return if (name == "remember_fact" || name == "search_knowledge") {
            memoryManager.executeTool(name, arguments)
        } else {
            fileToolManager.executeTool(name, arguments, scopePath)
        }
    }
}
