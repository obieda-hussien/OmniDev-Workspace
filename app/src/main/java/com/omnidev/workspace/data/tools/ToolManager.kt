package com.omnidev.workspace.data.tools

import kotlinx.serialization.Serializable

/**
 * Defines the contract for all tools available to the ReAct agent loop.
 * Each tool performs a discrete operation (e.g., file-system manipulation, 
 * device administration, web scraping, or Shizuku shell commands).
 */
interface ToolManager {

    /**
     * Returns the list of all tool definitions available to the agent,
     * formatted for inclusion in the system prompt or JSON schema.
     */
    fun getToolDefinitions(): List<ToolDefinition>

    /**
     * Executes a tool by [name] with the given [arguments].
     *
     * @param name The tool name (e.g., "read_file_lines" or "device_admin").
     * @param arguments Key-value arguments provided by the LLM for the tool.
     * @param scopePath The user's active target context directory. File-based tools 
     * must validate paths against this scope. Non-file tools 
     * (e.g., system toggles, web search) can safely ignore it.
     * @return The [ToolExecutionResult] containing the outcome or error message.
     */
    suspend fun executeTool(
        name: String,
        arguments: Map<String, String>,
        scopePath: String? = null
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
 * Consumed by the LLM in the next ReAct iteration to observe the environment.
 */
@Serializable
data class ToolExecutionResult(
    /** The raw text output, JSON, or Markdown returned by the tool. */
    val output: String,
    /** Flag indicating if the tool execution failed. The LLM should read the output to understand why. */
    val isError: Boolean = false,
    /** Flag indicating if the output was too long and had to be truncated to save context window tokens. */
    val truncated: Boolean = false
)
