package com.omnidev.workspace.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.omnidev.workspace.domain.engine.MentionCandidate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatMentionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun userCanSelectMultipleCapabilitiesAndRemoveOneWithoutLosingTheDraft() {
        val draft = mutableStateOf("")
        val candidates = listOf(MentionCandidate("tool", "read_file", "Read a project file"),
            MentionCandidate("tool", "web_search", "Search the web"),
            MentionCandidate("skill", "quality-gate", "Review changes"))
        compose.setContent {
            MaterialTheme {
                ChatComposerSurface(draft.value, { draft.value = it }, {}, {}, false, {}, true,
                    mentionLoader = { candidates })
            }
        }
        compose.onNodeWithContentDescription("Message Omni").performClick().performTextInput("Check @read")
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Mention @tool:read_file").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Mention @tool:read_file").performClick()
        compose.onNodeWithContentDescription("Message Omni").performTextInput("@skill:quality")
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Mention @skill:quality-gate").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Mention @skill:quality-gate").performClick()
        compose.runOnIdle {
            assertTrue(draft.value.startsWith("Check "))
            assertTrue(draft.value.contains("@tool:read_file"))
            assertTrue(draft.value.contains("@skill:quality-gate"))
        }
        compose.onNodeWithContentDescription("Remove @tool:read_file").performClick()
        compose.runOnIdle {
            assertFalse(draft.value.contains("@tool:read_file"))
            assertTrue(draft.value.contains("@skill:quality-gate"))
            assertTrue(draft.value.startsWith("Check "))
        }
    }
}
