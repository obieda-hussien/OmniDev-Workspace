package com.omnidev.workspace.ui.chat

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.assistant.AssistantScreenState
import com.omnidev.workspace.data.chatmedia.ChatMediaStore
import com.omnidev.workspace.data.model.AttachmentMeta
import com.omnidev.workspace.data.model.AttachmentMediaType
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.ui.assistant.AssistantConversation
import com.omnidev.workspace.ui.assistant.assistantTestFlavor
import com.omnidev.workspace.ui.motion.LocalOmniMotion
import com.omnidev.workspace.ui.motion.MotionPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Model the native voice window: application Context and no Activity result owner. */
class ChatMediaHostTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private lateinit var image: File
    private lateinit var meta: AttachmentMeta

    @Before fun createScreenshot() {
        image = File.createTempFile("host-screenshot-", ".png", ChatMediaStore.directory(context))
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try { image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        meta = AttachmentMeta(Uri.fromFile(image).toString(), "image/png", "screenshot.png", image.length(), AttachmentMediaType.IMAGE)
    }
    @After fun deleteScreenshot() { image.delete() }

    @Test fun nativeAssistantDisplaysSentScreenshotAndRoutesSaveAsThroughItsHost() {
        var requested: AttachmentMeta? = null
        val external = AtomicReference<Intent?>()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context,
                LocalChatMediaSaveAs provides { requested = it },
                LocalChatMediaExternalActivity provides { external.set(it) },
                LocalOmniMotion provides MotionPolicy(reduced = true)) {
                MaterialTheme { Box(Modifier.width(400.dp).height(760.dp)) {
                    check(LocalActivityResultRegistryOwner.current == null)
                    AssistantConversation(AssistantScreenState(visible = true), ChatUiState(messages = listOf(
                        ChatMessage(MessageRole.USER, "Check this screenshot", attachments = listOf(meta)),
                        ChatMessage(MessageRole.ASSISTANT, "Screen checked."))), assistantTestFlavor())
                } }
            }
        }
        compose.onNodeWithText("screenshot.png").assertExists()
        compose.onNodeWithContentDescription("Media actions").performScrollTo().performClick()
        compose.onNodeWithText("Save as…").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(meta, requested) }
        for (label in listOf("Share", "Open with…")) {
            external.set(null)
            compose.onNodeWithContentDescription("Media actions").performScrollTo().performClick()
            compose.onNodeWithText(label).performClick()
            compose.waitUntil(5_000) { external.get() != null }
            val launched = checkNotNull(external.get())
            @Suppress("DEPRECATION")
            val fileIntent = if (label == "Share") launched.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!! else launched
            assertEquals(if (label == "Share") Intent.ACTION_SEND else Intent.ACTION_VIEW, fileIntent.action)
            assertEquals("image/png", fileIntent.type)
            assertTrue(fileIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertNotNull(fileIntent.clipData)
        }
    }

    @Test fun missingRegistryAndBridgeDoNotCrashAndDisableOnlySaveAs() {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context,
                LocalOmniMotion provides MotionPolicy(reduced = true)) {
                MaterialTheme { check(LocalActivityResultRegistryOwner.current == null); ChatMediaCard(meta) }
            }
        }
        compose.onNodeWithText("screenshot.png").assertExists()
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithContentDescription("Media actions").performClick()
        compose.onNodeWithText("Save as…").assertIsNotEnabled()
        compose.onNodeWithText("Share").assertIsEnabled()
        compose.onNodeWithText("Open with…").assertIsEnabled()
    }

    @Test fun ordinaryChatKeepsItsActivitySaveAsLauncher() {
        compose.setContent { MaterialTheme { ChatMediaCard(meta) } }
        compose.onNodeWithContentDescription("Media actions").performClick()
        compose.onNodeWithText("Save as…").assertIsEnabled()
    }

    @Test fun externalActionsAddNewTaskOnlyWhenTheHostIsNotAnActivity() {
        var activityContext: Context? = null
        compose.setContent { activityContext = LocalContext.current }
        compose.runOnIdle {
            for (base in listOf(context, checkNotNull(activityContext))) {
                var started: Intent? = null
                val recording = object : ContextWrapper(ContextWrapper(base)) {
                    override fun startActivity(intent: Intent) { started = intent }
                }
                for (intent in listOf(Intent(Intent.ACTION_VIEW), Intent.createChooser(Intent(Intent.ACTION_SEND), "Share"))) {
                    startChatMediaActivity(recording, intent)
                    assertSame(intent, started)
                    assertEquals(base === context, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                }
            }
        }
    }
}
