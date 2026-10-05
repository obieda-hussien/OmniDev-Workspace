package com.omnidev.workspace.ui.assistant

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.core.policy.TierPolicy
import com.omnidev.workspace.core.policy.TierPolicyHolder
import com.omnidev.workspace.data.assistant.AssistantFlavorPolicy
import com.omnidev.workspace.data.assistant.AssistantScreenState
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.ui.chat.*
import com.omnidev.workspace.ui.motion.LocalOmniMotion
import com.omnidev.workspace.ui.motion.MotionPolicy
import com.omnidev.workspace.ui.theme.OmniDevTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

internal fun assistantTestFlavor(deviceActions: Boolean = true) = AssistantFlavorPolicy(
    object : TierPolicy by TierPolicyHolder.current {
        override val allowAccessibility = deviceActions
    })

class AssistantConversationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun toolsUseTheOriginalWindowAndRouteScreenFilesVoiceAndAccess() {
        var screens = 0; var areas = 0; var files = 0; var voices = 0; var accesses = 0
        compose.setContent {
            CompositionLocalProvider(LocalContext provides InstrumentationRegistry.getInstrumentation().targetContext.applicationContext) {
                OmniDevTheme(dynamicColor = false) {
                    AssistantConversation(AssistantScreenState(visible = true), ChatUiState(), assistantTestFlavor(),
                        onScreen = { if (it) areas++ else screens++ }, onAttach = { files++ },
                        onSystemVoice = { voices++ }, onAccess = { accesses++ })
                }
            }
        }
        fun open() = compose.onNodeWithContentDescription("Assistant tools").performClick()
        open(); compose.onNodeWithText("Current screen").performScrollTo().performClick()
        open(); compose.onNodeWithText("Select an area").performScrollTo().performClick()
        open(); compose.onNodeWithText("Files, photos & videos").performScrollTo().performClick()
        open(); compose.onNodeWithText("System voice input").performScrollTo().performClick()
        open(); compose.onNodeWithText("Device access and permissions").performScrollTo().performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.runOnIdle { assertEquals(listOf(1, 1, 1, 1, 1), listOf(screens, areas, files, voices, accesses)) }
        open(); saveChatPreview(compose, "floating-assistant", "assistant-tools-light.png")
    }

    @Test fun compactRtlAssistantKeepsStopAndTheDraftAvailableWhileRunning() {
        val screen = mutableStateOf(AssistantScreenState(visible = true, input = "السؤال التالي / Next question"))
        val processing = mutableStateOf(true)
        var stops = 0
        compose.setContent {
            val density = LocalDensity.current
            OmniDevTheme(darkTheme = true, dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.width(320.dp).height(340.dp)) {
                        AssistantConversation(screen.value, ChatUiState(isProcessing = processing.value,
                            streamingContent = "Reviewing the page…", agentStatus = "Checking the page"), assistantTestFlavor(),
                            onInputChanged = { screen.value = screen.value.copy(input = it) },
                            onStop = { stops++; processing.value = false })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Open full conversation").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Message Omni").performTextInput(" إضافة")
        compose.onNodeWithContentDescription("Stop request").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Send question").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(1, stops); assertTrue(screen.value.input.contains("إضافة")) }
        saveChatPreview(compose, "floating-assistant", "assistant-compact-dark-rtl.png")
    }

    @Test fun approvalCannotBeHiddenByToolsOrExpandAndDoesNotApproveOnCancel() {
        val pending = mutableStateOf(true)
        var approved = 0; var denied = 0; var expanded = 0
        compose.setContent { OmniDevTheme(dynamicColor = false) {
            AssistantConversation(AssistantScreenState(visible = true), ChatUiState(pendingConfirmation = if (pending.value)
                PendingConfirmation("assistant-review", ConfirmationType.ASSISTANT_ACTION, "Fill Name with Obieda",
                    onApprove = { approved++; pending.value = false }, onDeny = { denied++; pending.value = false }) else null),
                assistantTestFlavor(), onExpand = { expanded++ })
        } }
        compose.onNodeWithContentDescription("Assistant tools").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open full conversation").assertIsNotEnabled().performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, approved); assertEquals(1, denied); assertEquals(0, expanded) }
        compose.onNodeWithContentDescription("Assistant tools").assertExists()
    }

    @Test fun liteDoesNotAdvertiseBubbleOrDeviceAccessAndWelcomeDoesNotSubmit() {
        val input = mutableStateOf("")
        var sends = 0
        compose.setContent { OmniDevTheme(darkTheme = false, dynamicColor = false) {
            AssistantConversation(AssistantScreenState(visible = true, input = input.value), ChatUiState(), assistantTestFlavor(false),
                onInputChanged = { input.value = it }, onSend = { sends++ })
        } }
        compose.onNodeWithContentDescription("Minimize to floating bubble").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send question").assertIsNotEnabled()
        saveChatPreview(compose, "floating-assistant", "assistant-welcome-light.png")
        compose.onNodeWithText("Help with a task").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Help me with this task: ", input.value); assertEquals(0, sends) }
        compose.onNodeWithContentDescription("Assistant tools").performClick()
        compose.onNodeWithText("Device access and permissions").assertDoesNotExist()
    }

    @Test fun savingBlocksSendButListeningCanBeStoppedWithoutOpeningTools() {
        val screen = mutableStateOf(AssistantScreenState(visible = true, input = "Keep this draft", saving = true))
        var microphones = 0; var sends = 0
        compose.setContent { OmniDevTheme(dynamicColor = false) {
            AssistantConversation(screen.value, ChatUiState(), assistantTestFlavor(),
                onMicrophone = { microphones++ }, onSend = { sends++ })
        } }
        compose.onNodeWithContentDescription("Send question").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("Assistant tools").assertIsNotEnabled()
        compose.runOnIdle { screen.value = screen.value.copy(saving = false, listening = true) }
        compose.onNodeWithContentDescription("Stop listening").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, microphones); assertEquals(0, sends); assertEquals("Keep this draft", screen.value.input) }
    }
}
