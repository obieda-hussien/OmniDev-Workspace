package com.omnidev.workspace.ui.chat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.omnidev.workspace.domain.teamstate.*
import com.omnidev.workspace.ui.theme.OmniDevTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TeamRecoverySectionTest {
    @get:Rule val compose = createComposeRule()
    private fun team(resumable: Boolean) = TeamRecoverySummary("team", 42, "Inspect source and edit Kotlin", "Inspect source and edit Kotlin", 0,
        "INTERRUPTED", listOf(TeamWorkerRecovery("edit", "INTERRUPTED", 1, emptyList(), "write_file", "Review unresolved effect",
            if (resumable) emptyList() else listOf("write-call"), "team:r0:edit:a1", listOf("write-call"))), resumable,
        if (resumable) emptyList() else listOf("No automatic replay"), "/tmp")
    @Test fun unknownMutationHasReviewButNoResumeButtonAndNeedsExactReconciliationText() {
        var reconciled = false
        compose.setContent { OmniDevTheme(dynamicColor = false) { TeamRecoverySection(listOf(team(false)), true, {}, {},
            { _, _, _, _ -> reconciled = true }, {}) } }
        compose.onNodeWithTag("team-resume-team").assertDoesNotExist()
        compose.onNodeWithText("Inspect plan and failures").performClick()
        compose.onNodeWithText("Confirm exact operation was not executed").assertIsNotEnabled()
        compose.onNodeWithText("Evidence that this exact operation did not execute").performTextInput("Verified exact destination: no operation was executed")
        compose.onNodeWithText("Confirm exact operation was not executed").performClick()
        assertTrue(reconciled)
        compose.onNodeWithText("Saved Team · COMPLETED").assertDoesNotExist()
    }
    @Test fun authorizationFailureAllowsExactReviewBeforePermissionRetry() {
        val saved = team(false).let { it.copy(workers = it.workers.map { worker -> worker.copy(interventionRequired = true) }) }
        compose.setContent { OmniDevTheme(dynamicColor = false) { TeamRecoverySection(listOf(saved), true, {}, {}, { _, _, _, _ -> }, {}) } }
        compose.onNodeWithText("Inspect plan and failures").performClick()
        compose.onNodeWithText("Confirm exact operation was not executed").assertExists()
        compose.onNodeWithText("Recheck permissions on next attempt").assertDoesNotExist()
        compose.onNodeWithTag("team-resume-team").assertDoesNotExist()
    }
    @Test fun safeResumeUsesThePersistedTeamIdentityAndRespectsProcessingState() {
        var resumed: String? = null
        compose.setContent { OmniDevTheme(dynamicColor = false) { TeamRecoverySection(listOf(team(true)), true, { resumed = it }, {}, { _, _, _, _ -> }, {}) } }
        compose.onNodeWithTag("team-resume-team").performClick()
        assertEquals("team", resumed)
    }
    @Test fun busyComposerDisablesSafeContinuation() {
        compose.setContent { OmniDevTheme(dynamicColor = false) { TeamRecoverySection(listOf(team(true)), false, {}, {}, { _, _, _, _ -> }, {}) } }
        compose.onNodeWithTag("team-resume-team").assertIsNotEnabled()
    }
}
