package com.omnidev.workspace.ui.chat

import com.omnidev.workspace.data.model.*
import org.junit.Assert.*
import org.junit.Test

class LastChatTurnTest {
    private fun message(role: MessageRole, id: String, text: String = id) = ChatMessage(role, text, messageId = id)

    @Test fun latestTurnIncludesMediaErrorsAndModeHandoffsWithoutTouchingOlderTurns() {
        val prefix = listOf(message(MessageRole.USER, "u1"), message(MessageRole.ASSISTANT, "a1"))
        val user = message(MessageRole.USER, "u2").copy(userInput = "raw", userMode = "SWARM")
        val outputs = listOf(message(MessageRole.TOOL, "tool"), message(MessageRole.ASSISTANT, "media"),
            message(MessageRole.ASSISTANT, "error"), message(MessageRole.ASSISTANT, "handoff"))
        val turn = LastChatTurn.from(prefix + user + outputs)!!
        assertEquals(prefix, turn.prefix); assertEquals(user, turn.user); assertEquals(outputs, turn.outputs)
        assertEquals("handoff", turn.lastAssistantId); assertEquals("raw", turn.editableText)
        assertEquals("SWARM", turn.user.userMode)
    }

    @Test fun noUserMeansNoEditableOrRegeneratableTurn() {
        assertNull(LastChatTurn.from(emptyList()))
        assertNull(LastChatTurn.from(listOf(message(MessageRole.ASSISTANT, "orphan"))))
    }

    @Test fun lastUserWithoutResponseCanBeEditedButEarlierAnswerCannotBeRegenerated() {
        val turn = LastChatTurn.from(listOf(message(MessageRole.USER, "u1"), message(MessageRole.ASSISTANT, "a1"),
            message(MessageRole.USER, "u2")))!!
        assertNull(turn.lastAssistantId); assertTrue(turn.outputs.isEmpty())
    }

    @Test fun exactInputWinsEvenWhenItContainsContextLookingText() {
        val input = "[Replying to you: \"literal\"]\n\nkeep this\n\n[Attached files: literal]"
        val turn = LastChatTurn.from(listOf(message(MessageRole.USER, "u", "decorated").copy(userInput = input)))!!
        assertEquals(input, turn.editableText)
    }

    @Test fun legacyDecorationsAreRemovedOnceAndPlainMultilineTextIsPreserved() {
        val input = "أعد شرح Kotlin\nبالتفصيل"
        val old = "[Replying to OmniDev: \"quote\"]\n\n$input\n\n[Attached files: screen.png]"
        assertEquals(input, LastChatTurn.from(listOf(message(MessageRole.USER, "u", old)))!!.editableText)
        assertEquals(input, LastChatTurn.from(listOf(message(MessageRole.USER, "u", input)))!!.editableText)
    }

    @Test fun delayedMediaFromAnEarlierRequestIsPreservedAndCannotChooseTheWrongTurnToRegenerate() {
        val older = message(MessageRole.USER, "u1")
        val latest = message(MessageRole.USER, "u2")
        val answer = message(MessageRole.ASSISTANT, "a2")
        val lateMedia = message(MessageRole.ASSISTANT, "media-result:earlier").copy(replyToMessageId = older.messageId)
        val turn = LastChatTurn.from(listOf(older, latest, answer, lateMedia))!!
        assertEquals(listOf(older, lateMedia), turn.prefix)
        assertEquals(listOf(answer), turn.outputs); assertEquals("a2", turn.lastAssistantId)
    }

    @Test fun repeatedReplacementKeepsOneUserTurnAndOnlyTheNewestOutput() {
        val original = LastChatTurn.from(listOf(message(MessageRole.USER, "u"), message(MessageRole.ASSISTANT, "a")))!!
        val replacement = original.prefix + original.user.copy(messageId = "u2", userInput = "edited") + message(MessageRole.ASSISTANT, "a2")
        val next = LastChatTurn.from(replacement)!!
        assertTrue(next.prefix.isEmpty()); assertEquals("u2", next.user.messageId)
        assertEquals(listOf("a2"), next.outputs.map { it.messageId })
    }
}
