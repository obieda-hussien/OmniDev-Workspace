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
 *
 * [isError] remains the backwards-compatible source of truth consumed by the
 * ReAct loop. The additional fields let the runtime distinguish semantic
 * failures from transport failures instead of treating "process exit 0" as
 * proof that the requested operation succeeded.
 */
@Serializable
data class ToolExecutionResult(
    /** Compact observation returned to the model / existing UI. */
    val output: String,
    /** True when the requested operation did not complete successfully. */
    val isError: Boolean = false,
    /** True when [output] was compacted to protect the LLM context window. */
    val truncated: Boolean = false,
    /** Stable machine-readable failure/success class (e.g. PERMISSION_DENIED). */
    val classification: String? = null,
    /** Process exit code when one exists. */
    val exitCode: Int? = null,
    /** Execution domain/backend (termux, shizuku-user-service, rish, root, etc.). */
    val backend: String? = null,
    /** Optional verification evidence, such as a settings read-back value. */
    val verification: String? = null,
    /** Whether retrying the exact same operation may reasonably succeed. */
    val retryable: Boolean = false,
    /** Infrastructure/configuration failure that should trip an agent circuit breaker. */
    val persistentFailure: Boolean = false
) {
    val isSuccess: Boolean get() = !isError
}
