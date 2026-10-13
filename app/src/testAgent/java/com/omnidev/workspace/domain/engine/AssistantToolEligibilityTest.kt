package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.*
import com.omnidev.workspace.data.tools.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AssistantToolEligibilityTest {
    private suspend fun rejectedAfterModelRequest(toolName: String) {
        var invoked = false
        var revoked = false
        var requests = 0
        val manager = object : ToolManager {
            override fun getToolDefinitions() = listOf(ToolDefinition(toolName, "Inspect current screen",
                listOf(ToolParameter("action", "string", "Inspection action"))))
            override suspend fun executeTool(name: String, arguments: Map<String, String>, scopePath: String?): ToolExecutionResult {
                invoked = true
                return ToolExecutionResult("Should not execute")
            }
        }
        val pipeline = AgentPipeline(manager, completionProvider = { request ->
            requests++
            if (requests == 1) {
                if (!toolName.startsWith("mcp_")) assertTrue(request.tools.orEmpty().any { it.name == toolName })
                revoked = true
                CompletionResponse("", toolCalls = listOf(ToolCall("call-1", toolName, mapOf("action" to "dump_tree"))))
            } else CompletionResponse("The action is unavailable.")
        }, config = AgentConfig(maxIterations = 3, enableRetry = false, enableSelfReflection = false),
            toolEligibility = { if (revoked) "Capability revoked" else null })
        val events = pipeline.execute("Inspect the current screen", modelId = "gpt-4o-mini", scopePath = "/tmp",
            additionalToolDomains = setOf(IntentClassifier.ToolDomain.DEVICE_CONTROL)).toList()
        assertFalse(invoked)
        assertTrue(events.filterIsInstance<AgentEvent.ToolResult>().any { it.isError && it.output.contains("Capability revoked") })
    }
    @Test fun nativeCallRechecksPolicyAfterSchemaExposure() = runBlocking { rejectedAfterModelRequest("semantic_ui") }
    @Test fun forgedMcpCallCannotBypassExecutionPolicy() = runBlocking { rejectedAfterModelRequest("mcp_device_control") }
}
