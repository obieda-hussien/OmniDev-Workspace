package com.omnidev.workspace.ui.chat

import android.content.ComponentName
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
import com.omnidev.workspace.MainActivity
import com.omnidev.workspace.domain.engine.OmniMode
import com.omnidev.workspace.ui.theme.OmniDevTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun editorStaysAdjacentToTheRealKeyboardAcrossOpenCloseAndDraftChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            info.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        compose.activity.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val state = mutableStateOf(ChatUiState(activeMode = OmniMode.AGENT, isProcessing = true))
        var stops = 0
        lateinit var root: View
        compose.setContent { OmniDevTheme(darkTheme = false, dynamicColor = false) {
            root = LocalView.current
            ChatConversation(state.value, onInputChanged = { state.value = state.value.copy(inputText = it) }, onStop = { stops++ })
        } }
        val controller = WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
        val density = context.resources.displayMetrics.density
        fun awaitKeyboardPlacement() {
            var lastSample: Triple<Int, Int, Int>? = null
            var stableSamples = 0
            var gap = Float.NaN
            // IME visibility changes before the platform animation and Compose's inset layout finish.
            // Sample in pixels, then require several consistent frames; do not widen the overlap limit.
            compose.waitUntil(10_000) {
                val insets = ViewCompat.getRootWindowInsets(root)
                if (insets?.isVisible(WindowInsetsCompat.Type.ime()) != true) return@waitUntil false
                val bounds = compose.onNodeWithTag("conversation-composer").fetchSemanticsNode().boundsInRoot
                val location = IntArray(2)
                val visible = Rect()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    root.getLocationOnScreen(location)
                    root.getWindowVisibleDisplayFrame(visible)
                }
                gap = visible.bottom - (location[1] + bounds.bottom)
                val sample = Triple(visible.bottom, (location[1] + bounds.bottom).toInt(), insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                val adjacent = gap >= -2f && gap <= 12 * density
                stableSamples = if (adjacent && sample == lastSample) stableSamples + 1 else 0
                lastSample = sample
                stableSamples >= 3
            }
            assertTrue("Composer should meet the keyboard, without overlap or a blank IME-height gap: $gap px", gap >= -2f && gap <= 12 * density)
        }
        repeat(2) { cycle ->
            compose.onNodeWithContentDescription("Message Omni").performClick()
            compose.activity.runOnUiThread { controller.show(WindowInsetsCompat.Type.ime()) }
            compose.waitUntil(10_000) { ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            compose.onNodeWithContentDescription("Message Omni").performTextInput(if (cycle == 0) "رسالة عربية English" else "\nالمهمة التالية")
            compose.waitForIdle()
            awaitKeyboardPlacement()
            compose.onNodeWithContentDescription("Stop agent").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(cycle + 1, stops); assertTrue(state.value.inputText.contains("رسالة عربية")) }
            if (cycle == 0) {
                val directory = File(context.filesDir, "chat-previews"); directory.mkdirs()
                val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                File(directory, "chat-keyboard-light.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
            compose.activity.runOnUiThread { controller.hide(WindowInsetsCompat.Type.ime()) }
            compose.waitUntil(10_000) { ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) != true }
            compose.onNodeWithContentDescription("Message Omni").assertIsDisplayed()
        }
    }
}
