package com.omnidev.workspace.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.omnidev.workspace.data.chatmedia.*
import com.omnidev.workspace.data.model.ModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MediaModelSelectionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun selectingConnectedImageModelEnablesOnlyItsOwnSettingsAndSavesExplicitly() {
        val original = MediaConfig.defaults(MediaKind.IMAGE)
        var saved: MediaConfig? = null
        compose.setContent { MaterialTheme {
            MediaModelPickerSheet(MediaKind.IMAGE, original,
                MediaModelCatalog.available(MediaKind.IMAGE, setOf(ModelProvider.OPENAI), emptyList()),
                setOf(ModelProvider.OPENAI), false, null, { saved = it }, {}, {}, {})
        } }
        compose.onNodeWithText("GPT Image 1.5").performClick()
        compose.onNodeWithText("Save images settings").performClick()
        compose.runOnIdle {
            assertEquals(ModelProvider.OPENAI, saved?.provider)
            assertEquals("gpt-image-1.5", saved?.model); assertTrue(saved!!.enabled)
            assertFalse(original.enabled)
        }
    }
    @Test fun noConnectedMusicProviderOffersSetupAndCanKeepMusicOff() {
        var managed = false
        var saved: MediaConfig? = null
        compose.setContent { MaterialTheme {
            MediaModelPickerSheet(MediaKind.MUSIC, MediaConfig.defaults(MediaKind.MUSIC), emptyList(), emptySet(),
                false, null, { saved = it }, {}, {}, { managed = true })
        } }
        compose.onNodeWithText("Manage providers").performClick()
        compose.runOnIdle { assertTrue(managed) }
        compose.onNodeWithText("Save music & songs settings").performClick()
        compose.runOnIdle { assertFalse(saved!!.enabled) }
    }
    @Test fun assignmentToggleCanDisableWithoutChangingTheSelectedModel() {
        val current = mutableStateOf(MediaConfig.defaults(MediaKind.VIDEO).copy(enabled = true))
        compose.setContent { MaterialTheme {
            MediaAssignmentRow(MediaKind.VIDEO, current.value, true, false, {}, { current.value = current.value.copy(enabled = it) })
        } }
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertFalse(current.value.enabled); assertEquals("veo-3.1-fast-generate-preview", current.value.model) }
    }
}
