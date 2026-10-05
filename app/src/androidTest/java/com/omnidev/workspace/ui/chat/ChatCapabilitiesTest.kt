package com.omnidev.workspace.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.omnidev.workspace.data.skills.AgentSkill
import com.omnidev.workspace.data.skills.ChatCapabilityStore
import com.omnidev.workspace.data.skills.SkillOrigin
import com.omnidev.workspace.domain.model.ChatSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatCapabilitiesTest {
    @get:Rule val compose = createComposeRule()
    private fun control(title: String, role: Role) = compose.onNode(hasText(title) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, role))

    @Test fun toolsAndSkillsStaySeparateAndPreserveCapabilityDependencies() {
        val settings = mutableStateOf(ChatSettings())
        val policy = mutableStateOf(ChatCapabilityStore.Snapshot(useAllEnabledSkills = false))
        val skill = AgentSkill("Review-Code", "Review changes carefully", "instructions", SkillOrigin.USER, true)
        compose.setContent { MaterialTheme {
            ChatCapabilitiesContent(settings.value, policy.value, listOf(skill), {}, { settings.value = it }, { policy.value = it })
        } }
        compose.onNodeWithText("Review-Code").assertDoesNotExist()
        control("Off", Role.RadioButton).performClick()
        control("Web search", Role.Switch).assertIsNotEnabled()
        control("On demand", Role.RadioButton).performClick()
        control("Web search", Role.Switch).performClick()
        control("Deep research", Role.Switch).performScrollTo().assertIsNotEnabled().assertIsOff()
        compose.runOnIdle { assertFalse(settings.value.webSearchEnabled); assertFalse(settings.value.deepResearchEnabled) }
        compose.onNodeWithText("Skills").performClick()
        control("Review-Code", Role.Checkbox).performScrollTo().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(setOf("review-code"), policy.value.selectedSkillNames) }
        compose.onNode(hasSetTextAction()).performTextInput("no matching skill")
        compose.onNodeWithText("No skills match your search.").assertExists()
        compose.onNodeWithContentDescription("Clear search").performClick()
        control("Review-Code", Role.Checkbox).assertIsOn()
        saveChatPreview(compose, "chat-capabilities", "tools-skills-light.png")
        compose.onNodeWithText("Tools").performClick()
        compose.onNodeWithText("Review-Code").assertDoesNotExist()
        control("Web search", Role.Switch).assertIsOff()
    }
}
