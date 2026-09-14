package com.omnidev.workspace.data.network

import com.omnidev.workspace.data.model.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class CompletionServiceTest {
    @Test fun customEndpointReceivesModelKeyAndStructuredToolHistory() = runTest {
        var body = ""
        var authorization: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            body = exchange.requestBody.bufferedReader().readText()
            val response = """{"choices":[{"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val response = CompletionService().invoke(CompletionRequest(
                modelId = "CUSTOM_OPENAI::demo", apiKey = "test-key",
                customBaseUrl = "http://127.0.0.1:${server.address.port}/v1",
                messages = listOf(ChatMessage(MessageRole.USER, "read"),
                    ChatMessage(MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("call-1", "read_file", mapOf("path" to "001")))),
                    ChatMessage(MessageRole.TOOL, "", toolResults = listOf(ToolCallResult("call-1", "read_file", "file content"))))))
            assertEquals("done", response.content)
            assertEquals("Bearer test-key", authorization)
            val payload = Json.parseToJsonElement(body).jsonObject
            assertEquals("demo", payload["model"]!!.jsonPrimitive.content)
            val messages = payload["messages"]!!.jsonArray
            assertEquals("tool", messages.last().jsonObject["role"]!!.jsonPrimitive.content)
            assertEquals("call-1", messages.last().jsonObject["tool_call_id"]!!.jsonPrimitive.content)
            val args = messages[1].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
            assertEquals("001", Json.parseToJsonElement(args).jsonObject["path"]!!.jsonPrimitive.content)
        } finally { server.stop(0) }
    }

    @Test fun streamingSeparatesReasoningAndRecordsUsage() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.close()
            val data = listOf(
                """{"choices":[{"delta":{"reasoning_content":"checking"}}]}""",
                """{"choices":[{"delta":{"content":"answer"},"finish_reason":"stop"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}""",
                "[DONE]"
            ).joinToString("") { "data: $it\n\n" }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, data.size.toLong())
            exchange.responseBody.use { it.write(data) }
        }
        server.start()
        try {
            val reasoning = StringBuilder()
            val answer = StringBuilder()
            val response = CompletionService().stream(CompletionRequest(
                "CUSTOM_OPENAI::demo", listOf(ChatMessage(MessageRole.USER, "hi")), apiKey = "test-key",
                customBaseUrl = "http://127.0.0.1:${server.address.port}/v1", onReasoning = { reasoning.append(it) }
            )) { answer.append(it) }
            assertEquals("checking", reasoning.toString())
            assertEquals("answer", answer.toString())
            assertEquals("checking", response.thinkingContent)
            assertEquals(8, response.tokensUsed?.totalTokens)
        } finally { server.stop(0) }
    }
}
