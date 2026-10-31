package com.omnidev.workspace.ui.assistant

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AssistantWindowControlsTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun unlockedAssistantWindowAllowsUserScreenshots() {
        val context = instrumentation.targetContext
        org.junit.Assume.assumeFalse(com.omnidev.workspace.data.admin.DeviceConsentStore(context).locked())
        instrumentation.runOnMainSync {
            val dialog = android.app.Dialog(android.view.ContextThemeWrapper(context, android.R.style.Theme_Material_Light_Dialog))
            val window = requireNotNull(dialog.window)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            val privacy = AssistantWindowPrivacy(context, window)
            try { assertEquals(0, window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            finally { privacy.close() }
        }
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

    @Test fun expandIsDisabledWhileRunIsActive() {
        val processing = mutableStateOf(true)
        var expansions = 0
        compose.setContent { MaterialTheme { AssistantExpandButton(false, false, processing.value) { expansions++ } } }
        val expand = compose.onNodeWithContentDescription("Open full conversation")
        expand.assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, expansions); processing.value = false }
        expand.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, expansions) }
    }

    @Test fun cancelledOemRoleRequestFallsBackToDefaultAppSettings() {
        val launched = mutableListOf<String?>()
        var result: Int? = null
        instrumentation.runOnMainSync {
            val owner = object : LifecycleOwner {
                val state = LifecycleRegistry(this)
                override val lifecycle: Lifecycle get() = state
            }
            owner.state.currentState = Lifecycle.State.CREATED
            val registry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                    input: I, options: ActivityOptionsCompat?) {
                    launched += contract.createIntent(instrumentation.targetContext, input).action
                    dispatchResult(requestCode, Activity.RESULT_CANCELED, null)
                }
            }
            val flow = AssistantSetupFlow(registry, owner,
                roleRequest = { Intent("android.app.role.action.REQUEST_ROLE") },
                isSelected = { false }, finish = { code, _ -> result = code })
            owner.state.currentState = Lifecycle.State.STARTED
            flow.start()
            owner.state.currentState = Lifecycle.State.DESTROYED
        }
        assertEquals(listOf("android.app.role.action.REQUEST_ROLE", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), launched)
        assertEquals(Activity.RESULT_CANCELED, result)
    }
}
