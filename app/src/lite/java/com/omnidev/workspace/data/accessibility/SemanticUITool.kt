package com.omnidev.workspace.data.accessibility

object SemanticUITool {
    fun clearSnapshot() = Unit
    fun recordedSelector(nodeId: String?): com.omnidev.workspace.data.routines.UiSelector? = null
    fun getToolDefinitions(): List<com.omnidev.workspace.data.tools.ToolDefinition> = emptyList()
    suspend fun execute(action: String, params: Map<String, String>) = com.omnidev.workspace.data.tools.ToolExecutionResult("Screen automation is not included in Lite.", true)
}
