package com.omnidev.workspace.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.omnidev.workspace.data.model.AIModel
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.ui.chat.saveChatPreview
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ModelSelectionTest {
    @get:Rule val compose = createComposeRule()
    private fun model(title: String) = compose.onNode(hasText(title) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

    @Test fun searchingALargeCatalogSelectsTheStableIdForTheRequestedRole() {
        val models = (0..119).map { AIModel("OPENAI::model-$it", "Model $it", ModelProvider.OPENAI, contextWindow = 128_000) }
        val state = mutableStateOf(AISettingsUiState(modelAssignments = mapOf(ModelRole.CHAT to models[0].id),
            expandedDropdownRole = ModelRole.SWARM_WORKER))
        var selectedRole: ModelRole? = null
        var selectedId: String? = null
        compose.setContent { MaterialTheme {
            ModelSelectionContent(state.value, emptyMap(), setOf(ModelProvider.OPENAI), mapOf(ModelProvider.OPENAI to models),
                {}, {}, { role, id -> selectedRole = role; selectedId = id }, {}, {}, {}, {})
        } }
        compose.onNode(hasSetTextAction()).performTextInput("model-119")
        model("Model 119").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(ModelRole.SWARM_WORKER, selectedRole)
            assertEquals("OPENAI::model-119", selectedId)
            assertEquals(models[0].id, state.value.modelAssignments[ModelRole.CHAT])
        }
        saveChatPreview(compose, "model-picker", "model-picker-light.png")
    }

    @Test fun savingAndSaveFailureKeepTheExistingAssignmentVisible() {
        val models = listOf(AIModel("OPENAI::first", "First model", ModelProvider.OPENAI, contextWindow = 128_000),
            AIModel("OPENAI::second", "Second model", ModelProvider.OPENAI, contextWindow = 128_000))
        val saving = mutableStateOf(true)
        val error = mutableStateOf<String?>(null)
        var attempts = 0
        compose.setContent { MaterialTheme {
            ModelPickerSheet(ModelRole.AGENT, models[0].id, mapOf(ModelProvider.OPENAI to models), emptyMap(), saving.value,
                error.value, {}, { attempts++; error.value = "Could not save this model. Please try again." }, {}, {}, {}, {})
        } }
        model("Second model").assertIsNotEnabled()
        compose.runOnIdle { saving.value = false }
        model("Second model").performClick()
        compose.onNodeWithText("Could not save this model. Please try again.").assertIsDisplayed()
        model("First model").assertIsSelected()
        model("Second model").assertIsNotSelected()
        compose.runOnIdle { assertEquals(1, attempts) }
    }

    @Test fun emptyAvailabilityOffersSetupInsteadOfPhantomModels() {
        var connections = 0
        compose.setContent { MaterialTheme {
            ModelPickerSheet(ModelRole.CHAT, "copilot/unavailable", emptyMap(), emptyMap(), false, null,
                {}, {}, {}, { connections++ }, {}, {})
        } }
        compose.onNodeWithText("Connect your first provider").assertExists()
        compose.onNodeWithText("Connect a provider").performClick()
        compose.runOnIdle { assertEquals(1, connections) }
        compose.onNodeWithText("GitHub Copilot").assertDoesNotExist()
    }
}
