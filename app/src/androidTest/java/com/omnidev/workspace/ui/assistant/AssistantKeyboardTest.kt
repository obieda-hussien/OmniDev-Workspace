package com.omnidev.workspace.ui.assistant

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.assistant.AssistantScreenState
import com.omnidev.workspace.ui.chat.ChatUiState
import com.omnidev.workspace.ui.theme.OmniDevTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AssistantKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun floatingPanelMeetsTheRealKeyboardAndKeepsSendAndDraftAcrossTwoCycles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.activity.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val screen = mutableStateOf(AssistantScreenState(visible = true))
        val processing = mutableStateOf(true)
        var sends = 0
        lateinit var root: View
        compose.setContent { OmniDevTheme(darkTheme = false, dynamicColor = false) {
            root = LocalView.current
            AssistantConversation(screen.value, ChatUiState(isProcessing = processing.value), assistantTestFlavor(),
                onInputChanged = { screen.value = screen.value.copy(input = it) }, onSend = { sends++ },
                onStop = { processing.value = false })
        } }
        val keyboard = WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
        repeat(2) { cycle ->
            compose.onNodeWithContentDescription("Message Omni").performClick()
            compose.activity.runOnUiThread { keyboard.show(WindowInsetsCompat.Type.ime()) }
            compose.waitUntil(10_000) { ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            compose.onNodeWithContentDescription("Message Omni").performTextInput(if (cycle == 0) "سؤال عربي English" else "\nالمتابعة")
            compose.waitForIdle()
            val composer = compose.onNodeWithTag("assistant-composer").getUnclippedBoundsInRoot()
            val location = IntArray(2); val visible = Rect()
            compose.runOnIdle { root.getLocationOnScreen(location); root.getWindowVisibleDisplayFrame(visible) }
            val density = context.resources.displayMetrics.density
            val gap = visible.bottom - (location[1] + composer.bottom.value * density)
            assertTrue("Assistant composer must sit above the real IME, without clipping or a second keyboard gap: $gap px", gap >= -2 && gap <= 16 * density)
            if (cycle == 0) compose.onNodeWithContentDescription("Stop request").assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("Send question").assertIsDisplayed().assertIsEnabled()
            if (cycle == 0) {
                val directory = File(context.filesDir, "chat-previews"); directory.mkdirs()
                val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                File(directory, "assistant-keyboard-light.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
            compose.activity.runOnUiThread { keyboard.hide(WindowInsetsCompat.Type.ime()) }
            compose.waitUntil(10_000) { ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) != true }
            compose.runOnIdle { assertTrue(screen.value.input.contains("سؤال عربي")); assertEquals(0, sends) }
        }
    }
}
