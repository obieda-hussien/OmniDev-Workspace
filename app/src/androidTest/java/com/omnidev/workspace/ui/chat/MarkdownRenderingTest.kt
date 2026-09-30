package com.omnidev.workspace.ui.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MarkdownRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cachedMarkdownPreservesArabicEnglishCodeAndUpdatesWithoutStaleText() {
        val text = mutableStateOf("# عنوان عربي\nEnglish **bold**\n```kotlin\nval answer = 42\n```")
        compose.setContent { MaterialTheme { MarkdownText(text.value) } }
        compose.onNodeWithText("عنوان عربي").assertExists()
        compose.onNodeWithText("English bold").assertExists()
        compose.onNodeWithText("val answer = 42").assertExists()
        compose.onNodeWithContentDescription("Copy code").performClick()
        compose.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("val answer = 42", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            text.value = "الرد اتحدث\nNew content"
        }
        compose.onNodeWithText("الرد اتحدث").assertExists()
        compose.onNodeWithText("New content").assertExists()
        compose.onNodeWithText("عنوان عربي").assertDoesNotExist()
        compose.onNodeWithText("val answer = 42").assertDoesNotExist()
    }
}
