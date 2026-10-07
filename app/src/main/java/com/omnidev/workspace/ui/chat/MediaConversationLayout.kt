package com.omnidev.workspace.ui.chat

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole

/** Presentation only: preserve stored history and keep each output below its own run console. */
internal data class MediaConversationLayout(val transcript: List<ChatMessage>, val liveOutputs: List<ChatMessage>) {
    companion object {
        fun from(messages: List<ChatMessage>, consoleTimestamps: Set<Long>, running: Boolean): MediaConversationLayout {
            val transcript = mutableListOf<ChatMessage>(); val live = mutableListOf<ChatMessage>()
            val turns = mutableListOf<MutableList<ChatMessage>>()
            messages.forEach { message ->
                if (turns.isEmpty() || message.role == MessageRole.USER) turns += mutableListOf<ChatMessage>()
                turns.last() += message
            }
            turns.forEachIndexed { index, turn ->
                val outputs = turn.filter { it.role == MessageRole.ASSISTANT && (it.attachments.isNotEmpty() || it.messageId.startsWith("media-result:")) }
                if (outputs.isEmpty()) transcript += turn
                else if (running && index == turns.lastIndex) { transcript += turn.filterNot { it in outputs }; live += outputs }
                else if (turn.any { it.timestamp in consoleTimestamps }) { transcript += turn.filterNot { it in outputs }; transcript += outputs }
                else transcript += turn
            }
            return MediaConversationLayout(transcript, live)
        }
    }
}
