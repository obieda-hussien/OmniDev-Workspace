package com.omnidev.workspace.data.network

import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.AttachmentMeta
import com.omnidev.workspace.data.model.AttachmentMediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CompletionServiceTest {
    @Test fun customRequestRetainsExplicitEndpointModelAndKey() {
        val request = CompletionRequest(
            modelId = "CUSTOM_OPENAI::demo", customModelId = "demo-v2", apiKey = "test-key",
            customBaseUrl = "https://example.test/v1", messages = listOf(ChatMessage(MessageRole.USER, "hi")))
        assertEquals("demo-v2", request.customModelId)
        assertEquals("test-key", request.apiKey)
        assertEquals("https://example.test/v1", ProviderEndpoint.normalize(request.customBaseUrl!!))
    }

    @Test fun structuredToolHistoryIsRepresentableWithoutParsingStringArguments() {
        val message = ChatMessage(MessageRole.ASSISTANT, "", toolCalls = listOf(
            com.omnidev.workspace.data.model.ToolCall("c1", "read", mapOf("id" to "001"))))
        assertEquals("001", message.toolCalls.single().arguments["id"])
        assertEquals("CUSTOM_OPENAI::demo", CompletionRequest("CUSTOM_OPENAI::demo", emptyList()).modelId)
    }

    @Test fun localCompletionRejectsImageBeforeInference() {
        val error = assertThrows(IOException::class.java) {
            runBlocking { CompletionService()(localImageRequest()) }
        }
        assertTrue(error.message.orEmpty().contains("cannot inspect image attachments"))
    }

    @Test fun localStreamRejectsImageBeforeEmittingText() {
        val chunks = mutableListOf<String>()
        val error = assertThrows(IOException::class.java) {
            runBlocking { CompletionService().stream(localImageRequest()) { chunks += it } }
        }
        assertTrue(error.message.orEmpty().contains("cannot inspect image attachments"))
        assertTrue(chunks.isEmpty())
    }

    private fun localImageRequest() = CompletionRequest(
        modelId = "local-edge-model",
        messages = listOf(ChatMessage(MessageRole.USER, "Inspect this frame", attachments = listOf(
            AttachmentMeta("routine-video:sample", "image/jpeg", "video-sample.jpg", 4, AttachmentMediaType.IMAGE, "/9j/2Q==")
        )))
    )
}
