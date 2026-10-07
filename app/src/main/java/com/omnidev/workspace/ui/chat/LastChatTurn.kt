package com.omnidev.workspace.ui.chat

import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole

/** One user request owns every following response/tool/media/handoff until the next user. */
internal data class LastChatTurn(val prefix: List<ChatMessage>, val user: ChatMessage, val outputs: List<ChatMessage>) {
    val lastAssistantId: String? get() = outputs.lastOrNull { it.role == MessageRole.ASSISTANT }?.messageId
    val editableText: String get() = user.userInput ?: legacyInput(user.content)

    companion object {
        fun from(messages: List<ChatMessage>): LastChatTurn? {
            val index = messages.indexOfLast { it.role == MessageRole.USER }
            if (index < 0) return null
            val user = messages[index]
            val (unrelated, outputs) = messages.drop(index + 1).partition {
                it.messageId.startsWith("media-result:") && it.replyToMessageId != null && it.replyToMessageId != user.messageId
            }
            return LastChatTurn(messages.take(index) + unrelated, user, outputs)
        }

        private fun legacyInput(content: String): String {
            var text = content
            if (text.startsWith("[Replying to ")) {
                val end = text.indexOf("\"]\n\n")
                if (end >= 0) text = text.substring(end + 4)
            }
            val files = text.lastIndexOf("\n\n[Attached files: ")
            if (files >= 0 && text.endsWith("]")) text = text.substring(0, files)
            return text
        }
    }
}
