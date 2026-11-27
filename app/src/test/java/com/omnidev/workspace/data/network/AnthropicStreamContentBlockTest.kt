package com.omnidev.workspace.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

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

    @Test fun nonToolBlocksNeedNoInput() {
        val block = json.decodeFromString<AnthropicStreamContentBlock>(
            """{"type":"text","text":"hello"}"""
        )
        assertNull(block.input)
    }
}
