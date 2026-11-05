package com.omnidev.workspace.ui.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omnidev.workspace.domain.taskstate.*
import com.omnidev.workspace.ui.theme.OmniDevTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskRecoverySectionTest {
    @get:Rule val compose = createComposeRule()

    private fun summary(canResume: Boolean, decision: RecoveryDecision) = TaskRecoverySummary(
        taskId = "task-42",
        sessionId = 9L,
        originalRequest = "Update the project and verify the result",
        objective = "Update the project",
        status = TaskStatus.INTERRUPTED,
        planRevision = 2,
        completedSteps = 1,
        totalSteps = 3,
        completedStepDescriptions = listOf("Inspect the source file"),
        openSteps = listOf("Read the changed file", "Verify the result"),
        latestCycle = 4,
        latestActivityAt = 100L,
        counts = TaskCounts(4, 5, 2, 1, 1, 3, 1),
        unresolvedInvocations = if (decision == RecoveryDecision.REQUIRES_RECONCILIATION) listOf(
            RecoveryInvocationSummary(
                invocationId = "task-42/task-42:c3/call-9",
                toolName = "patch_file_content",
                target = "Settings.kt",
                status = InvocationStatus.UNKNOWN,
                requestedAt = 90L,
                resultSummary = "",
                requiresReconciliation = true,
                reason = "The original side effect may or may not have happened."
            )
        ) else emptyList(),
        decision = decision,
        reasons = listOf("A saved task was interrupted."),
        canResume = canResume
    )

    @Test fun safeTaskShowsPersistedProgressAndExplicitResume() {
        var resumed = ""
        compose.setContent {
            OmniDevTheme(dynamicColor = false) {
                TaskRecoverySection(
                    tasks = listOf(summary(true, RecoveryDecision.SAFE_TO_RESUME)),
                    loading = false, error = null, actionsEnabled = true,
                    onResume = { resumed = it }, onReconcile = { _, _, _, _ -> }, onDismiss = {}
                )
            }
        }
        compose.onNodeWithText("Update the project and verify the result").assertIsDisplayed()
        compose.onNodeWithText("Steps completed: 1 of 3 · Plan revision 2").assertExists()
        compose.onNodeWithText("Resume").performClick()
        compose.runOnIdle { assertEquals("task-42", resumed) }
    }

    @Test fun unresolvedMutationHidesResumeAndRequiresVerificationBeforeReconciliation() {
        compose.setContent {
            OmniDevTheme(dynamicColor = false) {
                TaskRecoverySection(
                    tasks = listOf(summary(false, RecoveryDecision.REQUIRES_RECONCILIATION)),
                    loading = false, error = null, actionsEnabled = true,
                    onResume = {}, onReconcile = { _, _, _, _ -> }, onDismiss = {}
                )
            }
        }
        compose.onNodeWithText("Resume").assertDoesNotExist()
        compose.onNodeWithText("Reconciliation required before this task can safely continue.").assertIsDisplayed()
        compose.onNodeWithText("Review task").performClick()
        compose.onNodeWithText("Inspect the source file").assertExists()
        compose.onNodeWithText("Reconcile this operation").performClick()
        compose.onNodeWithText("Invocation: task-42/task-42:c3/call-9").assertExists()
        compose.onNodeWithText("Reconcile operation").assertIsDisplayed()
        compose.onNodeWithText("Record outcome").assertIsNotEnabled()
        compose.onNodeWithText("Verification statement").assertExists()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
    }
}
