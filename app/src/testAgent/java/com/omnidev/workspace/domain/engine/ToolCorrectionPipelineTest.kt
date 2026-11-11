package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ToolCorrectionPipelineTest {
    @Test fun `invalid action and keys need an explicit corrected proposal before actual dispatch`() = runTest {
        val calls = mutableListOf<Map<String, String>>()
        val manager = object : ToolManager {
            override fun getToolDefinitions() = listOf(ToolDefinition("read_file", "Read source", listOf(
                ToolParameter("action", "string", "Operation", allowedValues = listOf("read")),
                ToolParameter("path", "string", "Source path"))))
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
                calls += arguments
                return ToolExecutionResult("class Source: inspected")
            }
        }
        var round = 0
        val pipeline = AgentPipeline(manager, completionProvider = { request ->
            when (++round) {
                1 -> CompletionResponse("", listOf(ToolCall("bad", "read_file", mapOf("action" to "raed", "paht" to "/tmp/Source.kt"))))
                2 -> {
                    assertTrue(calls.isEmpty())
                    val feedback = request.messages.flatMap { it.toolResults }.joinToString { it.output }
                    assertTrue(feedback, feedback.contains("possible exact value=read"))
                    assertTrue(feedback, feedback.contains("path"))
                    CompletionResponse("", listOf(ToolCall("corrected", "read_file", mapOf("action" to "read", "path" to "/tmp/Source.kt"))))
                }
                else -> CompletionResponse("Read and verified source")
            }
        }, config = AgentConfig(maxIterations = 6, enableRetry = false, enableSelfReflection = false))
        val events = pipeline.execute("Read source", modelId = "gpt-4o-mini", scopePath = "/tmp").toList()
        assertEquals(listOf(mapOf("action" to "read", "path" to "/tmp/Source.kt")), calls)
        assertTrue(events.any { it is AgentEvent.ToolResult && it.classification == "INVALID_TOOL_ARGUMENTS" })
        assertTrue(events.any { it is AgentEvent.FinalAnswer })
    }
}
