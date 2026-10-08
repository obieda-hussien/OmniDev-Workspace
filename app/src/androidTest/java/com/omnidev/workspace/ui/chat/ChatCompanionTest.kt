package com.omnidev.workspace.ui.chat

import android.content.Context
import androidx.compose.foundation.layout.*
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
}
