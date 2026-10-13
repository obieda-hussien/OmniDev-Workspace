package com.omnidev.workspace.data.tools

object OemSystemTools {
    fun definitions(): List<ToolDefinition> = emptyList()
    suspend fun execute(context: android.content.Context, args: Map<String, String>, gate: com.omnidev.workspace.core.policy.ConfirmationGate?) = ToolExecutionResult("OEM service is not included in this build.", true)
}
