package com.omnidev.workspace

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolCallMetadataTest {
    @Test
    fun metadataSurvivesConversationSerialization() {
        val metadata = Json.parseToJsonElement(
            """{"google":{"thought_signature":"opaque-signature"},"future":{"value":42}}"""
        ).jsonObject
        val message = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCalls = listOf(ToolCall("call-1", "web_search", mapOf("query" to "test"), metadata))
        )
        val restored = Json.decodeFromString(
            ChatMessage.serializer(),
            Json.encodeToString(ChatMessage.serializer(), message)
        )
        assertEquals(metadata, restored.toolCalls.single().extraContent)
        assertEquals(message.toolCalls, restored.copy(content = "summary").toolCalls)
    }

    @Test
    fun oldToolCallsWithoutMetadataRemainReadable() {
        val restored = Json.decodeFromString(
            ToolCall.serializer(),
            """{"id":"call-1","name":"web_search","arguments":{}}"""
        )
        assertNull(restored.extraContent)
    }
}
