package com.omnidev.workspace.ui.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity

/** Result-aware role request with public Settings fallbacks for OEM role controllers. */
class AssistantSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val flow = AssistantSetupFlow(activityResultRegistry, this,
            roleRequest = { AssistantSettings.roleRequest(this) },
            isSelected = { AssistantSettings.isSelected(this) },
            finish = { result, data -> setResult(result, data); finish() })
        if (savedInstanceState == null) flow.start() // Restored requests retain the registry keys.
    }

    companion object { const val ERROR = "assistant_setup_error" }
}
