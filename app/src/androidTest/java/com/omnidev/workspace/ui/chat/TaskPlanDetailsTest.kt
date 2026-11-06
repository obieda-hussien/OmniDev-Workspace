package com.omnidev.workspace.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.omnidev.workspace.domain.planning.*
import com.omnidev.workspace.ui.theme.OmniDevTheme
import org.junit.Rule
import org.junit.Test

class TaskPlanDetailsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun planShowsActiveWorkDependenciesAndReplanReason() {
        val p = TaskPlanSummary("Repair source", "Inspect before changing", 2, "A dependency changed", emptyList(), listOf(
            TaskPlanStepSummary("S1", "Inspect source", StepReadiness.COMPLETED, emptyList(), "Source", "Read source", 1, null),
            TaskPlanStepSummary("S2", "Repair and read back", StepReadiness.RUNNING, listOf("S1"), "Fixed content", "Read back content", 0, null)
        ), "S2", false)
        compose.setContent { OmniDevTheme(dynamicColor = false) {
            Column(Modifier.verticalScroll(rememberScrollState())) { TaskPlanDetails(p) }
        } }
        compose.onNodeWithText("Task plan · Revision 2").assertIsDisplayed()
        compose.onNodeWithText("Plan changed: A dependency changed").assertExists()
        compose.onNodeWithText("S1 · COMPLETED").assertExists()
        compose.onNodeWithText("S2 · RUNNING").assertExists()
        compose.onNodeWithText("Depends on: S1").assertExists()
        compose.onNodeWithText("Verification: Read back content").assertExists()
    }
    @Test fun failedDependencyAndApprovalRemainVisible() {
        val p = TaskPlanSummary("Repair source", "Review the blocker", 1, "Initial", emptyList(), listOf(
            TaskPlanStepSummary("S1", "Missing source", StepReadiness.FAILED, emptyList(), "Source", "Read", 0, null),
            TaskPlanStepSummary("S2", "Privileged change", StepReadiness.AWAITING_APPROVAL, listOf("S1"), "Changed state", "Read back", 0, "SHIZUKU_COMMAND")
        ), null, true)
        compose.setContent { OmniDevTheme(dynamicColor = false) { TaskPlanDetails(p) } }
        compose.onNodeWithText("S1 · FAILED").assertExists()
        compose.onNodeWithText("S2 · AWAITING APPROVAL").assertExists()
        compose.onNodeWithText("Policy review: SHIZUKU COMMAND").assertExists()
        compose.onNodeWithText("No step can run. Review failed dependencies or required approval.").assertExists()
    }
}
