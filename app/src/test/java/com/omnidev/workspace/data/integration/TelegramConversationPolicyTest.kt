package com.omnidev.workspace.data.integration

import org.junit.Assert.*
import org.junit.Test

class TelegramConversationPolicyTest {
    @Test fun recognizesModeAndSessionAliases() {
        val parse = TelegramConversationPolicy::parse
        assertEquals("mode", parse("/mode")?.name)
        assertEquals("mode_chat", parse("/mode chat")?.name)
        assertEquals("mode_agent", parse("/mode@OmniBot\tagent")?.name)
        assertEquals("OmniBot", parse("/mode@OmniBot\tagent")?.target)
        assertEquals("mode_swarm", parse("/mode_swarm")?.name)
        assertEquals("new_session", parse("/new session Telegram")?.name)
        assertEquals("Telegram", parse("/new session Telegram")?.argument)
        assertEquals("unknown", parse("/unknown")?.name)
        assertNull(parse("open /tmp/project"))
        assertNull(parse("/tmp/project"))
    }

    @Test fun keepsPairingArgumentOutOfOrdinaryTextRouting() {
        assertEquals(TelegramConversationPolicy.Command("pair", "synthetic", "OmniBot"),
            TelegramConversationPolicy.parse("/pair@OmniBot\tsynthetic"))
    }

    @Test fun removesRawEscapedAndUnclosedReasoning() {
        val clean = TelegramConversationPolicy::visibleReply
        assertEquals("أهلاً", clean("<thinking>private</thinking>أهلاً", true))
        assertEquals("Answer", clean("&lt;think&gt;private&lt;/think&gt;Answer", true))
        assertEquals("Answer", clean("Answer <thinking>private", true))
    }

    @Test fun dropsFabricatedActionClaimsAndKeepsOrdinaryReplies() {
        assertTrue(TelegramConversationPolicy.visibleReply(
            "Selected capability: device.list_apps\nTool Action: device.list_apps()\nApps: invented",
            chatHasNoTools = true
        ).contains("did not run any tools"))
        assertEquals("أهلاً", TelegramConversationPolicy.visibleReply(
            "Objective: Reply\nTool Action: None\nBlockers: None\nأهلاً", chatHasNoTools = true
        ))
    }

    @Test fun actualDeviceQuestionsNeedEvidence() {
        assertTrue(TelegramConversationPolicy.needsDeviceEvidence("طب انا فاتح اي علي تلفوني دلوقتي"))
        assertTrue(TelegramConversationPolicy.needsDeviceEvidence("طب عندي تطبيقات اي دلوقتي و اعلي تطبيق مساحة"))
        assertTrue(TelegramConversationPolicy.needsDeviceEvidence("اعلي تطبيق مساحة عندي اي هو؟"))
        assertFalse(TelegramConversationPolicy.needsDeviceEvidence("فاكر اسمي؟"))
    }

    @Test fun openSourceQuestionsStayInNormalChat() {
        assertFalse(TelegramConversationPolicy.needsDeviceEvidence("Which app is open source, Signal or WhatsApp?"))
        assertFalse(TelegramConversationPolicy.needsDeviceEvidence("What app is open-source and supports offline maps?"))
        assertTrue(TelegramConversationPolicy.needsDeviceEvidence("Which app is open right now?"))
        assertTrue(TelegramConversationPolicy.needsDeviceEvidence("Which app is open?"))
    }
}
