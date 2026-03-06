package com.omnidev.workspace.data.tools

import kotlinx.serialization.Serializable

/**
 * Defines the contract for all tools available to the ReAct agent loop.
 * Each tool performs a discrete file-system or codebase operation, constrained
 * to the user's active target context scope for safety and token economy.
 */
interface ToolManager {

    /**
     * Returns the list of all tool definitions available to the agent,
     * formatted for inclusion in the system prompt or tool schema.
     */
    fun getToolDefinitions(): List<ToolDefinition>

    /**
     * Executes a tool by [name] with the given [arguments].
     * All file paths in [arguments] are validated against [scopePath].
     *
     * @param name The tool name (e.g., "read_file_lines").
     * @param arguments Key-value arguments for the tool.
     * @param scopePath The user's active target context directory. All file paths
     *                  must resolve within this scope.
     * @return The result of the tool execution.
     */
    suspend fun executeTool(
        name: String,
        arguments: Map<String, String>,
        scopePath: String
    ): ToolExecutionResult
}

/**
 * Metadata describing a tool for the AI model's function-calling schema.
 */
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList()
)

/**
 * A single parameter for a tool definition.
 */
@Serializable
data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

/**
 * Result of executing a single tool.
 */
@Serializable
data class ToolExecutionResult(
    val output: String,
    val isError: Boolean = false,
    val truncated: Boolean = false
)
