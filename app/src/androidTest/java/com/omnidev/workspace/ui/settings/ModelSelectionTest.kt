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
        // Auto choices and provider headers can push rows off-screen in a LazyColumn.
        // Scroll to the stable target instead of assuming the row is already composed.
        compose.onNodeWithTag("model-options").performScrollToNode(hasText("Second model"))
        model("Second model").assertIsNotEnabled()
        compose.runOnIdle { saving.value = false }
        compose.onNodeWithTag("model-options").performScrollToNode(hasText("Second model"))
        model("Second model").performClick()
        compose.onNodeWithText("Could not save this model. Please try again.").assertIsDisplayed()
        model("First model").assertIsSelected()
        model("Second model").assertIsNotSelected()
        compose.runOnIdle { assertEquals(1, attempts) }
    }

    @Test fun autoProviderScopeCanBeLimitedToOneConnectedProvider() {
        val models = mapOf(
            ModelProvider.GEMINI to listOf(AIModel("GEMINI::gemini-2.5-flash", "Gemini Flash",
                ModelProvider.GEMINI, contextWindow = 128_000)),
            ModelProvider.OPEN_ROUTER to listOf(AIModel("OPEN_ROUTER::free:free", "OpenRouter Free",
                ModelProvider.OPEN_ROUTER, contextWindow = 128_000))
        )
        var chosen: ModelProvider? = null
        var changes = 0
        compose.setContent { MaterialTheme {
            ModelPickerSheet(ModelRole.AGENT, "AUTO_FREE_ONLY", models, emptyMap(), false,
                null, {}, {}, {}, {}, {}, {},
                onAutoProviderScopeChange = { chosen = it; changes++ })
        } }
        compose.onNodeWithTag("model-options").performScrollToNode(hasTestTag("auto-scope-GEMINI"))
        compose.onNodeWithTag("auto-scope-GEMINI").performClick()
        compose.runOnIdle {
            assertEquals(ModelProvider.GEMINI, chosen)
            assertEquals(1, changes)
        }
        compose.onNodeWithTag("auto-scope-all").performClick()
        compose.runOnIdle {
            assertEquals(null, chosen)
            assertEquals(2, changes)
        }
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
        compose.onNodeWithText("Set up a local model").assertDoesNotExist()
    }
}
