package com.omnidev.workspace.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import com.omnidev.workspace.data.db.entities.ChatHistoryMetadataEntity
import org.junit.Rule
import org.junit.Test

class ChatHistoryMetadataUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun topicAndMentionFiltersCombineWithActualSource() {
        val sessions = listOf(
            ChatSessionEntity(id = 1, title = "Configure WhatsApp in Omni", source = "app"),
            ChatSessionEntity(id = 2, title = "Build failure", source = "telegram"),
            ChatSessionEntity(id = 3, title = "Connected app help", source = "external_app", sourceAppName = "AndroidIDE")
        )
        val metadata = mapOf(
            1L to ChatHistoryMetadataEntity(1, topic = "omni_help", serviceTags = "|whatsapp|"),
            2L to ChatHistoryMetadataEntity(2, topic = "development", serviceTags = "|telegram|"),
            3L to ChatHistoryMetadataEntity(3, topic = "connected_apps", serviceTags = "|connected_apps|androidide|")
        )
        compose.setContent { MaterialTheme {
            ChatHistoryDrawer(sessions, null, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, metadata = metadata)
        } }
        compose.onNodeWithText("Apps mentioned").performClick()
        compose.onNodeWithText("WhatsApp", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Configure WhatsApp in Omni").assertIsDisplayed()
        compose.onNodeWithText("Build failure").assertDoesNotExist()
        compose.onNodeWithText("Sources").performClick()
        compose.onNodeWithText("Telegram", useUnmergedTree = true).performClick()
        compose.onNodeWithText("No matching chats").assertIsDisplayed()
        compose.onNodeWithText("Reset filters").performClick()
        compose.onNodeWithText("Topics").performClick()
        compose.onNodeWithText("Development", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Build failure").assertIsDisplayed()
        compose.onNodeWithText("Configure WhatsApp in Omni").assertDoesNotExist()
    }
}
