package com.omnidev.workspace.ui.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Result-aware role request with public Settings fallbacks for OEM role controllers. */
class AssistantSetupActivity : ComponentActivity() {
    private val role = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Some OEM role controllers return CANCELED without displaying their chooser.
        // The default-app page also lets an already-selected user review screen access.
        openSettings()
    }
    private val settings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        setResult(if (AssistantSettings.isSelected(this)) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // Activity Result restores pending requests.
        val request = AssistantSettings.roleRequest(this)
        if (request == null || runCatching { role.launch(request) }.isFailure) openSettings()
    }

    private fun openSettings() {
        for (candidate in AssistantSettings.settingsIntents()) {
            if (runCatching { settings.launch(candidate) }.isSuccess) return
        }
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(ERROR,
            "Open Android Settings → Apps → Default apps → Digital assistant app, select OmniDev, and allow screen content and screenshots."))
        finish()
    }

    companion object { const val ERROR = "assistant_setup_error" }
}
