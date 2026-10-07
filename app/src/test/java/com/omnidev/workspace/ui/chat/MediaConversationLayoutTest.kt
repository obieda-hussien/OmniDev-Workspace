package com.omnidev.workspace.ui.chat

import com.omnidev.workspace.data.model.*
import org.junit.Assert.*
import org.junit.Test

class MediaConversationLayoutTest {
    private val user = ChatMessage(MessageRole.USER, "Make an image", messageId = "user")
    private val card = ChatMessage(MessageRole.ASSISTANT, "Media generation", messageId = "card", attachments = listOf(AttachmentMeta("omni-media-job:one", "image/png", "Image", 0, AttachmentMediaType.IMAGE)))
    private val ready = ChatMessage(MessageRole.ASSISTANT, "Ready", messageId = "media-result:one")
    private val answer = ChatMessage(MessageRole.ASSISTANT, "Working", messageId = "answer", timestamp = 42)
    @Test fun `live output cards sit after the live console without changing stored message order`() {
        val original = listOf(user, card, ready)
        val layout = MediaConversationLayout.from(original, emptySet(), true)
        assertEquals(listOf(user), layout.transcript); assertEquals(listOf(card, ready), layout.liveOutputs)
        assertEquals(listOf(user, card, ready), original)
    }
    @Test fun `saved run console appears before its media outputs`() {
        val layout = MediaConversationLayout.from(listOf(user, card, answer, ready), setOf(42), false)
        assertEquals(listOf(user, answer, card, ready), layout.transcript); assertTrue(layout.liveOutputs.isEmpty())
    }
    @Test fun `a new request does not move old media into the current run`() {
        val next = user.copy(messageId = "next")
        val layout = MediaConversationLayout.from(listOf(user, card, answer, ready, next), setOf(42), true)
        assertEquals(listOf(user, answer, card, ready, next), layout.transcript); assertTrue(layout.liveOutputs.isEmpty())
    }
    @Test fun `user uploads and conversations without a console retain their order`() {
        val upload = card.copy(role = MessageRole.USER)
        val original = listOf(upload, answer, ready)
        assertEquals(original, MediaConversationLayout.from(original, emptySet(), false).transcript)
    }
}
