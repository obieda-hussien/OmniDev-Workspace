package com.omnidev.workspace.ui.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.domain.engine.OmniMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatSurfaceTest {
    @get:Rule val compose = createComposeRule()

    private fun modeOption(title: String) = compose.onNode(hasText(title) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

    @Test fun modePickerPreservesTheChosenModeAndOffersAllThreeManualModes() {
        val mode = mutableStateOf(OmniMode.CHAT)
        compose.setContent { MaterialTheme { ModeSelector(mode.value, { mode.value = it }) } }
        compose.onNodeWithContentDescription("Choose conversation mode").performClick()
        modeOption("Agent").performClick()
        compose.runOnIdle { assertEquals(OmniMode.AGENT, mode.value) }
        compose.onNodeWithContentDescription("Choose conversation mode").performClick()
        modeOption("Agent").assertIsSelected()
        modeOption("Multi-agent").performClick()
        compose.runOnIdle { assertEquals(OmniMode.SWARM, mode.value) }
        compose.onNodeWithContentDescription("Choose conversation mode").performClick()
        modeOption("Chat").performClick()
        compose.runOnIdle { assertEquals(OmniMode.CHAT, mode.value) }
    }

    @Test fun collapsedMessageCopiesItsFullContentAndRepliesToItsStableIdentity() {
        val message = ChatMessage(role = MessageRole.USER, content = "رسالة عربية English " + "a".repeat(520), messageId = "original")
        var reply: ChatMessage? = null
        compose.setContent { MaterialTheme { MessageBubble(message, onReply = { reply = it }) } }
        compose.onNodeWithText("Read full message").assertExists()
        compose.onNodeWithContentDescription("Copy message").performClick()
        compose.onNodeWithContentDescription("Reply to message").performClick()
        compose.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(message.content, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            assertEquals(message.messageId, reply?.messageId)
        }
        compose.onNodeWithText("Read full message").performClick()
        compose.onNodeWithText(message.content).assertExists()
    }

    @Test fun composerPreventsEmptySendAndKeepsStopAvailableDuringProcessing() {
        val text = mutableStateOf("")
        val processing = mutableStateOf(false)
        var sends = 0
        var stops = 0
        compose.setContent { MaterialTheme {
            ChatInputBar(text.value, { text.value = it }, { sends++ }, { stops++ }, processing.value)
        } }
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("اختبار")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle { assertEquals(1, sends); processing.value = true }
        compose.onNodeWithText("اختبار").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Stop agent").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops); assertEquals(1, sends) }
    }

    @Test fun bulkDeletionRequiresConfirmationAndPassesOnlySelectedSessionIds() {
        val sessions = listOf(ChatSessionEntity(id = 11, title = "Pinned project", isPinned = true),
            ChatSessionEntity(id = 22, title = "Another project"))
        var deleted: Set<Long>? = null
        compose.setContent { MaterialTheme {
            ChatHistoryDrawer(sessions, 11, {}, {}, {}, { _, _ -> }, {}, {}, { deleted = it }, {})
        } }
        compose.onNodeWithText("Pinned project").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Delete selected conversations").performClick()
        compose.runOnIdle { assertEquals(null, deleted) }
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { assertEquals(setOf(11L), deleted) }
        compose.onNodeWithText("Another project").assertExists()
    }

    @Test fun historySourceFiltersAndSearchDoNotMixUnrelatedConversations() {
        val sessions = listOf(ChatSessionEntity(id = 11, title = "App project", isPinned = true),
            ChatSessionEntity(id = 22, title = "Telegram task", source = ChatSessionEntity.SOURCE_TELEGRAM))
        compose.setContent { MaterialTheme {
            ChatHistoryDrawer(sessions, null, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Sources").performClick()
        compose.onNodeWithText("Telegram").performClick()
        compose.onNodeWithText("Telegram task").assertExists()
        compose.onNodeWithText("App project").assertDoesNotExist()
        compose.onNodeWithText("All").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("project")
        compose.onNodeWithText("App project").assertExists()
        compose.onNodeWithText("Telegram task").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("Telegram task").assertExists()
    }
}
