package com.omnidev.workspace.ui.chat

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.domain.engine.OmniMode
import com.omnidev.workspace.ui.motion.LocalOmniMotion
import com.omnidev.workspace.ui.motion.MotionPolicy
import com.omnidev.workspace.ui.theme.OmniDevTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatExperienceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oneLineComposerLeavesRoomForTheConversationAndAllowsAttachmentOnlySend() {
        val attachments = mutableStateOf(emptyList<PendingAttachment>())
        var sends = 0
        compose.setContent { MaterialTheme {
            ChatInputBar("", {}, { sends++ }, isProcessing = false, pendingAttachments = attachments.value)
        } }
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        val bounds = compose.onNodeWithTag("conversation-composer").getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("Empty composer should occupy one toolbar row", height <= 80.dp)
        compose.runOnIdle { attachments.value = listOf(PendingAttachment(Uri.parse("content://test/file"), "report.pdf")) }
        compose.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, sends) }
    }

    @Test fun compactRtlWorkspaceKeepsStopVisibleWithLargeTextReplyFilesAndLongErrors() {
        val reply = ChatMessage(MessageRole.ASSISTANT, "نتيجة المراجعة / Review result", messageId = "reply")
        val state = ChatUiState(inputText = "المهمة التالية\nNext task", activeMode = OmniMode.AGENT,
            isProcessing = true, messages = listOf(reply), replyingTo = reply,
            targetContextDisplayName = "My project", errorMessage = "Long diagnostic details. ".repeat(80),
            pendingAttachments = listOf(PendingAttachment(Uri.parse("content://test/report"), "report.pdf")),
            consoleEntries = listOf(AgentConsoleEntry.ThinkingEntry(1)), agentStatus = "Reviewing the project")
        var stops = 0
        compose.setContent {
            val density = LocalDensity.current
            OmniDevTheme(darkTheme = true, dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.width(320.dp).height(340.dp).testTag("compact-chat")) {
                        ChatConversation(state, onStop = { stops++ })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Stop agent").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Cancel reply").assertIsDisplayed()
        val header = compose.onNodeWithTag("conversation-header").getUnclippedBoundsInRoot()
        val mode = compose.onNodeWithContentDescription("Choose conversation mode").getUnclippedBoundsInRoot()
        assertTrue("Larger text must fit inside the header", mode.bottom <= header.bottom)
        savePreview("compact-chat", "compact-dark-rtl.png")
        compose.onNodeWithContentDescription("Stop agent").performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun unifiedToolsStillRoutesToFilesScopeAndCapabilitySettings() {
        var attachments = 0
        var scopes = 0
        compose.setContent { MaterialTheme {
            ChatInputBar("", {}, {}, isProcessing = false, onAttachClick = { attachments++ }, onChooseScope = { scopes++ })
        } }
        compose.onNodeWithContentDescription("Conversation tools").performClick()
        compose.onNodeWithText("Attach files").performClick()
        compose.runOnIdle { assertEquals(1, attachments) }
        compose.onNodeWithContentDescription("Conversation tools").performClick()
        compose.onNodeWithText("Project scope").performClick()
        compose.runOnIdle { assertEquals(1, scopes) }
        compose.onNodeWithContentDescription("Conversation tools").performClick()
        compose.onNodeWithText("Chat tools and skills").performClick()
        compose.onNodeWithText("Add to chat").assertExists()
    }

    @Test fun streamingBurstsEventuallyRenderTheFullLatestMarkdownAndArabic() {
        val text = mutableStateOf("Starting")
        compose.setContent { MaterialTheme { StreamingMessageBubble(text.value) } }
        for (i in 1..6) {
            compose.runOnIdle { text.value = "Chunk $i" }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.runOnIdle { text.value = "# أحدث رد\nEnglish **complete**\n```kotlin\nval answer = 42\n```" }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("val answer = 42").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("أحدث رد").assertExists()
        compose.onNodeWithText("English complete").assertExists()
        compose.onNodeWithText("Starting").assertDoesNotExist()
    }

    @Test fun reducedMotionControlSwitchDoesNotRetainOutgoingContent() {
        val busy = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalOmniMotion provides MotionPolicy(reduced = true)) {
            ChatControlTransition(busy.value, "test control") { Text(if (it) "Working" else "Ready") }
        } }
        compose.runOnIdle { busy.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Working").assertExists()
        compose.onNodeWithText("Ready").assertDoesNotExist()
    }

    @Test fun welcomeSuggestionsPrepareADraftWithoutAutomaticallySubmittingIt() {
        val state = mutableStateOf(ChatUiState())
        var sends = 0
        compose.setContent { OmniDevTheme(darkTheme = false, dynamicColor = false) {
            ChatConversation(state.value, onInputChanged = { state.value = state.value.copy(inputText = it) }, onSend = { sends++ })
        } }
        savePreview("chat-conversation", "welcome-light.png")
        compose.onNodeWithText("Explain something").performClick()
        compose.runOnIdle { assertTrue(state.value.inputText.startsWith("Explain this")); assertEquals(0, sends) }
        compose.onNodeWithContentDescription("Message Omni").assertIsFocused()
    }

    private fun savePreview(tag: String, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = checkNotNull(context.getExternalFilesDir("chat-previews"))
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(directory, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
