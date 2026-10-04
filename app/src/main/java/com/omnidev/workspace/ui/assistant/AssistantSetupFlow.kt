package com.omnidev.workspace.ui.assistant

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.LifecycleOwner

/** The same result chain is used by the setup Activity and deterministic registry tests. */
internal class AssistantSetupFlow(
    registry: ActivityResultRegistry,
    owner: LifecycleOwner,
    private val roleRequest: () -> Intent?,
    private val isSelected: () -> Boolean,
    private val finish: (Int, Intent?) -> Unit
) {
    private val role = registry.register("assistant_role", owner, ActivityResultContracts.StartActivityForResult()) {
        openSettings()
    }
    private val settings = registry.register("assistant_settings", owner, ActivityResultContracts.StartActivityForResult()) {
        finish(if (isSelected()) Activity.RESULT_OK else Activity.RESULT_CANCELED, null)
    }

    fun start() {
        val request = roleRequest()
        if (request == null || runCatching { role.launch(request) }.isFailure) openSettings()
    }

    private fun openSettings() {
        for (candidate in AssistantSettings.settingsIntents()) {
            if (runCatching { settings.launch(candidate) }.isSuccess) return
        }
        finish(Activity.RESULT_CANCELED, Intent().putExtra(AssistantSetupActivity.ERROR,
            "Open Android Settings → Apps → Default apps → Digital assistant app, select OmniDev, and allow screen content and screenshots."))
    }
}
