package com.omnidev.workspace.data.network

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.AttachmentMediaType
import com.omnidev.workspace.data.model.AttachmentMeta
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.model.TokenUsage
import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolParameter
import com.omnidev.workspace.domain.engine.TokenAccounting
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OpenAiTokenUsageTest {
    private val usage = """{
        "prompt_tokens": 180, "completion_tokens": 64, "total_tokens": 244,
        "prompt_tokens_details": {"cached_tokens": 128, "audio_tokens": 0},
        "completion_tokens_details": {"reasoning_tokens": 48}
    }""".trimIndent().replace("\n", " ")

    @Test
    fun completionPreservesReportedBreakdownWithoutDoubleCounting() = runBlocking {
        withResponse("""{"choices":[{"message":{"role":"assistant","content":"Done"}}],"usage":$usage}""") { request ->
            val response = CompletionService()(request)
            val counted = TokenAccounting.usage(request, response)

            assertEquals("Done", response.content)
            assertEquals(TokenUsage(180, 64, 244, 128, 48), response.tokensUsed)
            assertEquals(TokenAccounting.Usage(180, 64, 244, false, 128, 48), counted)
        }
    }

    @Test
    fun streamPreservesFinalUsageOnlyEvent() = runBlocking {
        val stream = """
            data: {"choices":[{"delta":{"content":"Done"},"finish_reason":"stop"}],"usage":null}

            data: {"choices":[],"usage":$usage}

            data: [DONE]

        """.trimIndent()
        withResponse(stream, "text/event-stream") { request ->
            val chunks = mutableListOf<String>()
            val response = CompletionService().stream(request) { chunks += it }

            assertEquals(listOf("Done"), chunks)
            assertEquals("stop", response.finishReason)
            assertEquals(TokenUsage(180, 64, 244, 128, 48), response.tokensUsed)
        }
    }

    @Test
    fun invalidOptionalDetailsDoNotDiscardCompletionOrTotals() = runBlocking {
        val payload = """{"choices":[{"message":{"role":"assistant","content":"Done"}}],
            "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12,
            "prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":[1]}}"""
        withResponse(payload) { request ->
            val response = CompletionService()(request)
            val counted = TokenAccounting.usage(request, response)

            assertEquals("Done", response.content)
            assertEquals(TokenUsage(10, 2, 12, cachedInputTokens = 0), response.tokensUsed)
            assertEquals(12, counted.totalTokens)
            assertNull(counted.reasoningTokens)
            assertFalse(counted.estimated)
        }
    }

    @Test fun imageEvidenceIsSerializedInTheProviderRequest() = runBlocking {
        val jpeg = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4n" +
            "ICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAAAP/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AP//Z"
        withResponse("""{"choices":[{"message":{"role":"assistant","content":"Done"}}]}""", checkRequest = { body ->
            val messages = Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
            val blocks = messages.single().jsonObject.getValue("content").jsonArray
            val image = blocks.single { it.jsonObject.getValue("type").jsonPrimitive.content == "image_url" }
            assertEquals("data:image/jpeg;base64,$jpeg", image.jsonObject.getValue("image_url")
                .jsonObject.getValue("url").jsonPrimitive.content)
        }) { request ->
            val sample = AttachmentMeta("routine-video:sample", "image/jpeg", "video-sample.jpg", 159, AttachmentMediaType.IMAGE, jpeg)
            val response = CompletionService()(request.copy(
                messages = listOf(ChatMessage(MessageRole.USER, "Inspect this frame", attachments = listOf(sample)))
            ))
            assertEquals("Done", response.content)
        }
    }

    @Test
    fun nativeSchemaEstimateMatchesEscapedToolArray() = runBlocking {
        val escapedValue = "\\".repeat(750)
        val tool = ToolDefinition("inspect_config", "Inspect a configuration", listOf(
            ToolParameter("variant", "string", "Variant", allowedValues = listOf(escapedValue))
        ))
        var budgetedTokens = 0
        val reply = """{"choices":[{"message":{"role":"assistant","content":"Done"}}]}"""
        withResponse(reply, checkRequest = { body ->
            val tools = Json.parseToJsonElement(body).jsonObject.getValue("tools").jsonArray
            assertEquals(escapedValue, tools.single().jsonObject.getValue("function").jsonObject
                .getValue("parameters").jsonObject.getValue("properties").jsonObject
                .getValue("variant").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content)
            assertEquals((tools.toString().length + 2) / 3, budgetedTokens)
        }) { request ->
            val withTools = request.copy(tools = listOf(tool))
            budgetedTokens = TokenAccounting.estimateInputTokens(withTools) - TokenAccounting.estimateInputTokens(request)
            assertEquals("Done", CompletionService()(withTools).content)
        }
    }

    @Test
    fun nativeAnthropicBudgetUsesInputSchemaAndOmitsEmptyRequiredList() {
        val tool = ToolDefinition("inspect_config", "Inspect", listOf(
            ToolParameter("variant", "string", "Variant", required = false)
        ))
        val expected = """[{"name":"inspect_config","description":"Inspect","input_schema":{"type":"object","properties":{"variant":{"type":"string","description":"Variant"}}}}]"""
        assertEquals(expected.length, nativeToolSchemaChars(listOf(tool), ModelProvider.ANTHROPIC))
    }

    private suspend fun withResponse(
        response: String,
        contentType: String = "application/json",
        checkRequest: (String) -> Unit = {},
        test: suspend (CompletionRequest) -> Unit
    ) {
        // Android unit-test compilation does not expose the JDK's jdk.httpserver module.
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
            server.soTimeout = 5_000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val served = executor.submit<String> {
                    server.use { it.accept().use { socket -> respond(socket, response, contentType) } }
                }
                test(CompletionRequest(
                    modelId = "CUSTOM_OPENAI::usage-fixture",
                    messages = listOf(ChatMessage(MessageRole.USER, "Report status")),
                    apiKey = "fixture-key",
                    customBaseUrl = baseUrl,
                    retryManagedByCaller = true
                ))
                checkRequest(served.get(5, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun respond(socket: Socket, response: String, contentType: String): String {
        socket.soTimeout = 5_000
        val request = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        check(request.readLine() == "POST /v1/chat/completions HTTP/1.1")
        var contentLength = 0
        while (true) {
            val header = checkNotNull(request.readLine()) { "Incomplete HTTP request headers" }
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toInt()
            }
        }
        check(contentLength in 1..65_536)
        val body = buildString(contentLength) {
            repeat(contentLength) {
                val char = request.read()
                check(char >= 0) { "Incomplete HTTP request body" }
                append(char.toChar())
            }
        }

        val bytes = response.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
        return body
    }
}
