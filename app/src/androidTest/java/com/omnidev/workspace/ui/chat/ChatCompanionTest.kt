package com.omnidev.workspace.ui.chat

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.layout.onGloballyPositioned
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.ui.companion.*
import com.omnidev.workspace.ui.motion.LocalOmniMotion
import com.omnidev.workspace.ui.motion.MotionPolicy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Real shared composer + overlay: geometry, preferences and touch do not depend on an Activity launcher. */
class ChatCompanionTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var original: CompanionPreferences
    @Before fun setup() {
        original = CompanionPreferenceStore.read(CompanionPreferenceStore.preferences(context))
        CompanionPreferenceStore.write(context, CompanionPreferences(enabled = true, roaming = false))
    }
    @After fun restore() { CompanionPreferenceStore.write(context, original) }

    @Test fun companionStaysAboveEditorAfterWindowResizeAndDoesNotBlockSend() {
        val height = mutableStateOf(520.dp)
        val text = mutableStateOf("")
        var sends = 0
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalOmniMotion provides MotionPolicy(reduced = true)) {
                ChatCompanionHost("chat", false, modifier = Modifier.width(340.dp).height(height.value)) {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        ChatComposerSurface(text.value, { text.value = it }, { sends++ }, {}, false, {}, text.value.isNotBlank())
                    }
                }
            } }
        }
        fun checkAboveEditor() {
            val pet = compose.onNodeWithTag("omni-companion").fetchSemanticsNode().boundsInRoot
            val editor = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
            assertTrue(pet.bottom <= editor.top)
        }
        checkAboveEditor()
        saveChatPreview(compose, "omni-companion", "companion-idle.png")
        compose.onNodeWithTag("omni-companion").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("hello")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle { assertEquals(1, sends); height.value = 290.dp }
        checkAboveEditor()
        compose.onNodeWithTag("omni-companion").performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(-220f, -150f)) }
        checkAboveEditor()
    }

    @Test fun disablingPreferenceRemovesPetAndWorkingStateUpdatesWithoutRestartingChat() {
        val working = mutableStateOf(false)
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalOmniMotion provides MotionPolicy(reduced = true)) {
                ChatCompanionHost("chat", working.value, modifier = Modifier.width(340.dp).height(450.dp)) {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        ChatComposerSurface("", {}, {}, {}, working.value, {}, false)
                    }
                }
            } }
        }
        compose.onNodeWithTag("omni-companion").assertExists()
        compose.runOnIdle { working.value = true }
        compose.onNodeWithTag("omni-companion").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Working alongside you"))
        compose.onNodeWithContentDescription("Stop agent").assertIsEnabled()
        compose.runOnIdle { CompanionPreferenceStore.write(context, CompanionPreferences(enabled = false)) }
        compose.waitUntil { compose.onAllNodesWithTag("omni-companion").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Stop agent").assertIsEnabled()
    }

    @Test fun hiddenOrTinyHostKeepsConversationAvailable() {
        val visible = mutableStateOf(false)
        compose.setContent { MaterialTheme {
            ChatCompanionHost("chat", false, visible.value, Modifier.width(340.dp).height(50.dp)) {
                Column { ChatComposerSurface("", {}, {}, {}, false, {}, false) }
            }
        } }
        compose.onNodeWithTag("omni-companion").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertExists()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("omni-companion").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertExists()
    }

    @Test fun userBubblesAreRealPerchesAndScrollingAwayFindsAnotherVisibleSurface() {
        val messages = (0..7).map { ChatMessage(MessageRole.USER, "User message $it", messageId = "perch-$it") }
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalOmniMotion provides MotionPolicy(reduced = true)) {
                ChatCompanionHost("messages", false, modifier = Modifier.width(340.dp).height(520.dp)) {
                    Column(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().companionViewport().testTag("pet-transcript"),
                            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            items(messages, key = { it.messageId }) { MessageBubble(it) }
                        }
                        ChatComposerSurface("", {}, {}, {}, false, {}, false)
                    }
                }
            } }
        }
        val bubble = compose.onNodeWithTag("message-surface-perch-1").fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithTag("omni-companion").fetchSemanticsNode().boundsInRoot
        val delta = androidx.compose.ui.geometry.Offset(bubble.center.x - start.center.x, bubble.top - start.bottom)
        compose.onNodeWithTag("omni-companion").performTouchInput {
            down(center); moveBy(delta); advanceEventTime(200); up()
        }
        val perched = compose.onNodeWithTag("omni-companion").fetchSemanticsNode().boundsInRoot
        assertTrue("Feet must land on the user bubble, not inside its text", kotlin.math.abs(perched.bottom - bubble.top) <= 2f)
        compose.onNodeWithTag("pet-transcript").performScrollToIndex(7)
        compose.onNodeWithTag("omni-companion").assertIsDisplayed()
        val escaped = compose.onNodeWithTag("omni-companion").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("pet-transcript").fetchSemanticsNode().boundsInRoot
        val editor = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        assertTrue(escaped.top >= viewport.top)
        assertTrue(escaped.bottom <= editor.top)
        compose.onNodeWithTag("message-surface-perch-1").assertDoesNotExist()
    }

    @Test fun conversationEventsDriveTheCompanionWithoutASeparateAgent() {
        val state = mutableStateOf(ChatUiState(isProcessing = true,
            consoleEntries = listOf(AgentConsoleEntry.ToolEntry("test_tool", "{}", iteration = 1))))
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalOmniMotion provides MotionPolicy(reduced = true)) {
            ChatConversation(state.value)
        } } }
        compose.onNodeWithTag("omni-companion").assertExists()
        compose.runOnIdle {
            state.value = state.value.copy(isProcessing = false, errorMessage = "Needs attention")
        }
        compose.onNodeWithTag("omni-companion").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Something needs attention"))
        compose.onNodeWithContentDescription("Send").assertExists()
    }

    @Test fun physicalDraggingFollowsTheFingerInRtl() = checkPhysicalDragging(LayoutDirection.Rtl)
    @Test fun physicalDraggingFollowsTheFingerInLtr() = checkPhysicalDragging(LayoutDirection.Ltr)

    private fun checkPhysicalDragging(direction: LayoutDirection) {
        compose.setContent { MaterialTheme { CompositionLocalProvider(
            LocalLayoutDirection provides direction, LocalOmniMotion provides MotionPolicy(reduced = true)) {
            ChatCompanionHost("direction", false, modifier = Modifier.width(380.dp).height(450.dp).testTag("pet-host")) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.weight(1f))
                    ChatComposerSurface("", {}, {}, {}, false, {}, false)
                }
            }
        } } }
        fun bounds() = compose.onNodeWithTag("omni-companion").fetchSemanticsNode().boundsInRoot
        val start = bounds()
        val host = compose.onNodeWithTag("pet-host").fetchSemanticsNode().boundsInRoot
        assertTrue("Physical initial position must not mirror", start.left > host.center.x)
        assertTrue(start.right <= host.right)
        compose.onNodeWithTag("omni-companion").performTouchInput { down(center); moveBy(Offset(-70f, -70f)) }
        val left = bounds()
        assertEquals(start.left - 70f, left.left, 2f)
        assertEquals(start.top - 70f, left.top, 2f)
        compose.onNodeWithTag("omni-companion").performTouchInput { moveBy(Offset(100f, 30f)) }
        val right = bounds()
        assertEquals(left.left + 100f, right.left, 2f)
        assertEquals(left.top + 30f, right.top, 2f)
        compose.onNodeWithTag("omni-companion").performTouchInput { up() }
        compose.onNode(hasSetTextAction()).assertExists()
    }

    @Test fun shapedCaretMovesRightForEnglishLeftForArabicAndDownForNewlines() {
        val value = mutableStateOf(TextFieldValue("hello", TextRange.Zero))
        var point: Offset? = null
        val gaze = CompanionEditorGaze { next, _ -> if (next != null) point = next }
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            SideEffect { gaze.value = value.value; gaze.publish() }
            Box(Modifier.padding(24.dp)) {
                BasicTextField(value.value, { value.value = it },
                    Modifier.width(280.dp).onGloballyPositioned { gaze.coordinates = it; gaze.publish() },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    onTextLayout = { gaze.layout = it; gaze.publish() })
            }
        } } }
        val englishStart = compose.runOnIdle { requireNotNull(point) }
        compose.runOnIdle { value.value = value.value.copy(selection = TextRange(5)) }
        val englishEnd = compose.runOnIdle { requireNotNull(point) }
        assertTrue(englishEnd.x > englishStart.x + 10f)
        compose.runOnIdle { value.value = TextFieldValue("مرحبا", TextRange.Zero) }
        val arabicStart = compose.runOnIdle { requireNotNull(point) }
        compose.runOnIdle { value.value = value.value.copy(selection = TextRange(5)) }
        val arabicEnd = compose.runOnIdle { requireNotNull(point) }
        assertTrue(arabicEnd.x < arabicStart.x - 10f)
        compose.runOnIdle { value.value = TextFieldValue("Omni مرحبا\nsecond سطر", TextRange.Zero) }
        val firstLine = compose.runOnIdle { requireNotNull(point) }
        compose.runOnIdle { value.value = value.value.copy(selection = TextRange(value.value.text.length)) }
        val secondLine = compose.runOnIdle { requireNotNull(point) }
        assertTrue(secondLine.y > firstLine.y + 10f)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun sharedEditorPreservesBilingualSelectionAndAcceptsExternalDraftReplacement() {
        val draft = mutableStateOf("")
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            ChatCompanionHost("typing", false, modifier = Modifier.width(340.dp).height(520.dp)) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.weight(1f))
                    ChatComposerSurface(draft.value, { draft.value = it }, {}, {}, false, {}, true)
                }
            }
        } } }
        val editor = compose.onNode(hasSetTextAction())
        editor.performTextInput("Omni عربي")
        editor.performTextInputSelection(TextRange(0))
        editor.performTextInput("Hi ")
        compose.runOnIdle { assertEquals("Hi Omni عربي", draft.value) }
        editor.performTextInput("\n" + "سطر English\n".repeat(8))
        compose.onNodeWithTag("omni-companion").assertIsDisplayed()
        compose.runOnIdle { draft.value = "External تعديل" }
        editor.assertTextEquals("External تعديل")
        editor.performTextInput("!")
        compose.runOnIdle { assertEquals("External تعديل!", draft.value) }
    }
}
