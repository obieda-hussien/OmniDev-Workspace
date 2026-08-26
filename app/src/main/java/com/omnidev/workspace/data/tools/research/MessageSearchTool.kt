package com.omnidev.workspace.data.tools.research

import com.omnidev.workspace.data.tools.ToolExecutionResult
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolParameter
import com.omnidev.workspace.data.repository.ChatRepository

object MessageSearchTool {

    fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "search_messages",
            description = "Search the user's historical chat messages and conversation history.",
            parameters = listOf(
                ToolParameter(
                    name = "query",
                    type = "string",
                    description = "The keyword or phrase to search for in past messages.",
                    required = true
                ),
                ToolParameter(
                    name = "limit",
                    type = "number",
                    description = "Maximum number of results to return (default 10).",
                    required = false
                ),
                ToolParameter(
                    name = "sessionId",
                    type = "string",
                    description = "Optional specific chat session ID to restrict search to.",
                    required = false
                )
            )
        )
    )

    suspend fun execute(query: String, limit: Int?, sessionIdStr: String?, chatRepository: ChatRepository?): ToolExecutionResult {
        if (chatRepository == null) return ToolExecutionResult("ChatRepository is not available.", isError = true)

        return try {
            val maxResults = limit ?: 10

            val messages = if (sessionIdStr.isNullOrBlank()) {
                chatRepository.searchAllMessages(query, maxResults)
            } else {
                val sessionId = sessionIdStr.toLongOrNull()
                if (sessionId != null) {
                    chatRepository.searchMessages(sessionId, query).take(maxResults)
                } else {
                    chatRepository.searchAllMessages(query, maxResults)
                }
            }

            if (messages.isEmpty()) {
                return ToolExecutionResult("No historical messages found matching query: $query", isError = false)
            }

            val formatted = buildString {
                appendLine("Found ${messages.size} message(s) for query '$query':")
                appendLine()
                messages.forEachIndexed { index, msg ->
                    appendLine("--- Result ${index + 1} ---")
                    appendLine("Role: ${msg.role.name}")
                    appendLine("Content: ${msg.content.take(500)}${if (msg.content.length > 500) "..." else ""}")
                    appendLine()
                }
            }

            ToolExecutionResult(formatted, isError = false)
        } catch (e: Exception) {
            ToolExecutionResult("Failed to search messages: ${e.message}", isError = true)
        }
    }
}
