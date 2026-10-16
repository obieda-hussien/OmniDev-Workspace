package com.omnidev.workspace.ui.assistant

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.omnidev.workspace.data.assistant.AssistantRuntime
import com.omnilink.sdk.OmniLinkConstants
import org.junit.Assert.*
import org.junit.Test

class LauncherSearchEntryTest {
    @Test fun publicSearchOpensAssistantWithDraftAndSurvivesRotation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val action = Intent(OmniLinkConstants.ACTION_PUBLIC_OMNI_REQUEST).setPackage(context.packageName)
            .addCategory(Intent.CATEGORY_DEFAULT)
        val resolved = context.packageManager.resolveActivity(action, 0)
        assertEquals(AssistantActivity::class.java.name, resolved?.activityInfo?.name)
        val query = "Launcher draft regression"
        val intent = Intent(context, AssistantActivity::class.java).setAction(OmniLinkConstants.ACTION_PUBLIC_OMNI_REQUEST)
            .putExtra(OmniLinkConstants.EXTRA_PUBLIC_REQUEST_JSON, "{\"kind\":\"ASK_OMNI\",\"text\":\"$query\"}")
        ActivityScenario.launch<AssistantActivity>(intent).use { scenario ->
            scenario.onActivity {
                assertTrue(AssistantRuntime.get(it).state.value.input.contains(query))
                assertFalse(AssistantRuntime.get(it).chat.uiState.value.isProcessing)
            }
            scenario.recreate()
            scenario.onActivity {
                assertEquals(1, Regex(query).findAll(AssistantRuntime.get(it).state.value.input).count())
            }
        }
    }
}
