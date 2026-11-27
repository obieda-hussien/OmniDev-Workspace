package com.omnidev.workspace.data.network

import com.omnidev.workspace.data.tools.ToolDefinition
import com.omnidev.workspace.data.tools.ToolParameter
import com.omnidev.workspace.domain.engine.ToolCallPreflight
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException

class AnthropicStreamContentBlockTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun initialToolInputIsRetained() {
        val block = json.decodeFromString<AnthropicStreamContentBlock>(
            """{"type":"tool_use","id":"tool_1","name":"search","input":{"query":"hello"}}"""
        )
        assertEquals("search", block.name)
        assertEquals("hello", decodeAnthropicToolInput(block.name.orEmpty(), block.input).arguments["query"])
    }

    @Test fun emptyInitialInputRemainsAnObject() {
        val block = json.decodeFromString<AnthropicStreamContentBlock>(
            """{"type":"tool_use","input":{}}"""
        )
        assertEquals("{}", block.input.toString())
    }

    @Test fun streamedToolOmissionIsAcceptedOnlyForDiscovery() {
        val block = json.decodeFromString<AnthropicStreamContentBlock>(
            """{"type":"tool_use","id":"tool_1","name":"lookup_status"}"""
        )
        assertNotNull(decodeAnthropicToolInput(block.name.orEmpty(), block.input).error)
        assertNull(decodeAnthropicToolInput("discover_tools", block.input).error)
        assertNull(decodeAnthropicToolInput(block.name.orEmpty(), JsonObject(emptyMap())).error)
    }

    @Test fun malformedStreamDeltasCannotFallBackToInitialInput() {
        val initial = JsonObject(emptyMap())
        assertEquals(mapOf("query" to "hello"),
            decodeAnthropicToolInput("search", initial, """{"query":"hello"}""").arguments)
        assertNotNull(decodeAnthropicToolInput("search", initial, "{").error)
        assertNotNull(decodeAnthropicToolInput("discover_tools", JsonNull).error)
        assertNotNull(decodeAnthropicToolInput("discover_tools", initial, "[]").error)
    }

    @Test fun completeStreamPreservesProviderOutputLimit() = runBlocking {
        val stream = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":30,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Partial answer"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"}}

            event: message_stop
            data: {"type":"message_stop"}
        """.trimIndent()
        val chunks = mutableListOf<String>()
        val response = stream.reader().buffered().use { readAnthropicStream(it, onChunk = { text -> chunks.add(text) }) }

        assertEquals(listOf("Partial answer"), chunks)
        assertEquals("Partial answer", response.content)
        assertEquals("max_tokens", response.finishReason)
    }

    @Test fun fullToolStreamKeepsMalformedCallForCorrection() = runBlocking {
        val stream = """
            data: {"type":"message_start"}
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_0","name":"search","input":{}}}
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{"}}
            data: {"type":"content_block_stop","index":0}
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call_1","name":"search","input":{}}}
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"query\":"}}
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"Kotlin\"}"}}
            data: {"type":"content_block_stop","index":1}
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}
            data: {"type":"message_stop"}
        """.trimIndent()
        val response = stream.reader().buffered().use { readAnthropicStream(it) }
        val calls = response.toolCalls

        assertEquals("tool_use", response.finishReason)
        assertEquals(listOf("call_0", "call_1"), calls.map { it.id })
        assertNotNull(calls[0].argumentError)
        assertTrue(calls[0].arguments.isEmpty())
        assertNull(calls[1].argumentError)
        assertEquals(mapOf("query" to "Kotlin"), calls[1].arguments)
        val preflight = ToolCallPreflight(listOf(
            ToolDefinition("search", "Search records", listOf(ToolParameter("query", "string", "Query")))
        ))
        assertEquals(listOf("INVALID_TOOL_ARGUMENTS", "BATCH_PREFLIGHT_BLOCKED"),
            preflight.checkBatch(calls).map { it?.classification })
    }

    @Test fun streamWithoutMessageStopReportsPrematureEof() = runBlocking {
        val stream = """
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_0","name":"search","input":{}}}
            data: {"type":"content_block_stop","index":0}
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}
        """.trimIndent()
        val failure = runCatching { stream.reader().buffered().use { readAnthropicStream(it) } }.exceptionOrNull()
        assertEquals(EOFException::class.java, failure?.javaClass)
    }

    @Test fun nonToolBlocksNeedNoInput() {
        val block = json.decodeFromString<AnthropicStreamContentBlock>(
            """{"type":"text","text":"hello"}"""
        )
        assertNull(block.input)
    }
}
