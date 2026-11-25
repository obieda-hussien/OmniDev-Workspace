package com.omnidev.workspace.data.network

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.TokenUsage
import com.omnidev.workspace.domain.engine.TokenAccounting
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress

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

    private suspend fun withResponse(
        response: String,
        contentType: String = "application/json",
        test: suspend (CompletionRequest) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", contentType)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            test(CompletionRequest(
                modelId = "CUSTOM_OPENAI::usage-fixture",
                messages = listOf(ChatMessage(MessageRole.USER, "Report status")),
                apiKey = "fixture-key",
                customBaseUrl = "http://127.0.0.1:${server.address.port}/v1",
                retryManagedByCaller = true
            ))
        } finally {
            server.stop(0)
        }
    }
}
