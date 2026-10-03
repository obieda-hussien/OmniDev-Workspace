package com.omnidev.workspace.ui.assistant

import android.app.Activity
import android.app.Instrumentation
import android.app.role.RoleManager
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.ui.chat.ConfirmationGateCard
import com.omnidev.workspace.ui.chat.ConfirmationType
import com.omnidev.workspace.ui.chat.PendingConfirmation
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class AssistantWindowControlsTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun nonActivityContextCanReviewAndApproveWithoutCreatingDialogWindow() {
        var approvals = 0
        compose.setContent {
            CompositionLocalProvider(LocalContext provides instrumentation.targetContext.applicationContext) {
                MaterialTheme {
                    ConfirmationGateCard(PendingConfirmation("request", ConfirmationType.ASSISTANT_ACTION,
                        "Fill the Name field with Obieda", onApprove = { approvals++ }, onDeny = {}))
                }
            }
        }
        compose.onNodeWithText("Fill the Name field with Obieda").assertExists()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText("Allow").performClick()
        compose.runOnIdle { assertEquals(1, approvals) }
    }

    @Test fun cancellingInlineConfirmationDoesNotApproveTheAction() {
        var approvals = 0
        var denials = 0
        compose.setContent {
            CompositionLocalProvider(LocalContext provides instrumentation.targetContext.applicationContext) {
                MaterialTheme {
                    ConfirmationGateCard(PendingConfirmation("request", ConfirmationType.GOD_MODE_FILE_PATCH,
                        "Change settings.txt", "@@ -1 +1 @@\n-old\n+new",
                        onApprove = { approvals++ }, onDeny = { denials++ }))
                }
            }
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, approvals); assertEquals(1, denials) }
    }

    @Test fun attachmentOptionsAndFilePathWorkWithoutActivityContext() {
        val panel = mutableStateOf(AssistantPanel.ATTACHMENTS)
        val path = mutableStateOf("")
        var pickerRequests = 0
        var addedPath = ""
        compose.setContent {
            CompositionLocalProvider(LocalContext provides instrumentation.targetContext.applicationContext) {
                MaterialTheme {
                    AssistantInlinePanel(panel.value, path.value, { path.value = it },
                        onAddPath = { addedPath = path.value; panel.value = AssistantPanel.NONE },
                        onChoosePath = { panel.value = AssistantPanel.FILE_PATH },
                        onAttach = { pickerRequests++; panel.value = AssistantPanel.NONE },
                        onSystemVoice = {}, onClose = { panel.value = AssistantPanel.NONE })
                }
            }
        }
        compose.onNodeWithText("Files, photos & videos").performClick()
        compose.runOnIdle { assertEquals(1, pickerRequests); panel.value = AssistantPanel.ATTACHMENTS }
        compose.onNodeWithText("File path").performClick()
        compose.onNodeWithText("Add path").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("/storage/emulated/0/Movies/demo.mp4")
        compose.onNodeWithText("Add path").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.runOnIdle { assertEquals("/storage/emulated/0/Movies/demo.mp4", addedPath) }
    }

    @Test fun cancelledOemRoleRequestFallsBackToDefaultAppSettings() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val roles = instrumentation.targetContext.getSystemService(RoleManager::class.java) ?: return
        assumeTrue(roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !roles.isRoleHeld(RoleManager.ROLE_ASSISTANT))
        val cancelled = Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        val roleMonitor = instrumentation.addMonitor(IntentFilter("android.app.role.action.REQUEST_ROLE"), cancelled, true)
        val defaultsMonitor = instrumentation.addMonitor(IntentFilter(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), cancelled, true)
        try {
            compose.setContent { MaterialTheme { AssistantSettingsCard() } }
            compose.onNodeWithText("Set up assistant").performClick()
            compose.waitUntil(5_000) { roleMonitor.hits > 0 && defaultsMonitor.hits > 0 }
            assertEquals(1, defaultsMonitor.hits)
        } finally {
            instrumentation.removeMonitor(roleMonitor)
            instrumentation.removeMonitor(defaultsMonitor)
        }
    }
}
