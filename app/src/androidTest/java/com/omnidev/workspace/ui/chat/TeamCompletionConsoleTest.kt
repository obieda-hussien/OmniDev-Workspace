package com.omnidev.workspace.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import com.omnidev.workspace.domain.engine.completion.FinishStatus
import com.omnidev.workspace.domain.engine.teamcompletion.*
import com.omnidev.workspace.ui.theme.OmniDevTheme
import org.junit.Rule
import org.junit.Test

class TeamCompletionConsoleTest {
    @get:Rule val compose = createComposeRule()
    @Test fun approvalReportSurvivesConsoleSerializationAndIsNotDisplayedAsDone() {
        val r = TeamCompletionReport("team", 0, FinishStatus.AWAITING_APPROVAL, listOf(
            TeamMemberResult("review", FinishStatus.AWAITING_APPROVAL, 1, 0, "", "")), emptyList(), "verified-digest")
        val entries = AgentConsoleSerializer.deserialize(AgentConsoleSerializer.serialize(teamCompletionEntries(r)))
        compose.setContent { OmniDevTheme(dynamicColor = false) { AgentLiveConsole(entries, false) } }
        compose.onNodeWithText("FAILED").assertIsDisplayed()
        compose.onNodeWithText("DONE").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand").performClick()
        compose.onNodeWithText("Team verification —", substring = true).assertExists()
    }
    @Test fun verifiedReportUsesTheExistingCompletedConsolePresentation() {
        val r = TeamCompletionReport("team", 0, FinishStatus.COMPLETED, listOf(
            TeamMemberResult("inspect", FinishStatus.COMPLETED, 1, 1, "digest", "output-hash")), emptyList(), "team-digest")
        val entries = AgentConsoleSerializer.deserialize(AgentConsoleSerializer.serialize(teamCompletionEntries(r)))
        compose.setContent { OmniDevTheme(dynamicColor = false) { AgentLiveConsole(entries, false) } }
        compose.onNodeWithText("DONE").assertIsDisplayed()
        compose.onNodeWithText("FAILED").assertDoesNotExist()
    }
}
