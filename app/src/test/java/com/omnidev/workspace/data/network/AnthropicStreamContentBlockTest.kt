package com.omnidev.workspace.data.network

import com.omnidev.workspace.data.model.ToolArgumentCodec
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AnthropicStreamContentBlockTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun initialToolInputIsRetained() {
        val block = json.decodeFromString<AnthropicStreamContentBlock>(
            """{"type":"tool_use","id":"tool_1","name":"search","input":{"query":"hello"}}"""
        )
        assertEquals("search", block.name)
        assertEquals("hello", ToolArgumentCodec.decode(block.input.toString()).arguments["query"])
    }

    @Test fun emptyInitialInputRemainsAnObject() {
        val block = json.decodeFromString<AnthropicStreamContentBlock>(
            """{"type":"tool_use","input":{}}"""
        )
        assertEquals("{}", block.input.toString())
    }

    @Test fun nonToolBlocksNeedNoInput() {
        val block = json.decodeFromString<AnthropicStreamContentBlock>(
            """{"type":"text","text":"hello"}"""
        )
        assertNull(block.input)
    }
}
