package com.omnidev.workspace

import android.app.Application
import android.util.Log
import com.omnidev.workspace.data.auth.CopilotModelRefresher
import com.omnidev.workspace.data.debug.CrashHandler
import com.omnidev.workspace.data.debug.DebugLogManager
import com.omnidev.workspace.data.ipc.ExtensionConnectionManager
import com.omnidev.workspace.data.ipc.LauncherConnectionManager
import com.omnidev.workspace.data.ipc.PrivilegedExecutionManager
import com.omnidev.workspace.data.model.ModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * OmniDev Workspace Application class.
 * Initializes application-wide dependencies and services.
 */
class OmniDevApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialise the debug log directory before installing the crash handler
        // so that the first crash can be written to disk immediately.
        DebugLogManager.init(applicationContext)
        CrashHandler.install()

        // Initialise PrivilegedExecutionManager with application context.
        // This enables the rish (Remote Interactive Shell) backend for Shizuku-based
        // shell execution and unlocks RishShellManager for the AI agent.
        PrivilegedExecutionManager.init(applicationContext)

        // Initialize universal launcher IPC binding manager (binds to current default launcher
        // if it exposes the OmniDev launcher control AIDL service).
        LauncherConnectionManager.initialize(applicationContext)

        // Initialize Omni-Link extension discovery (binds to third-party extension services
        // exposing com.omnidev.action.BIND_EXTENSION).
        ExtensionConnectionManager.initialize(applicationContext)

        // Restore dynamic Copilot models from the persisted cache so the model
        // selector is populated immediately — without waiting for a network round-trip.
        restoreCopilotModelsAsync()
    }

    /**
     * If the user previously connected with GitHub Copilot, re-fetch the available
     * models in the background and inject them into [com.omnidev.workspace.registry.ModelRegistry].
     *
     * Uses [ApiKeyRepository] to read the stored OAuth token (same DataStore used by
     * [GitHubDeviceFlowManager]), then delegates to [CopilotModelRefresher].
     * Runs in the background; the UI is not blocked.
     */
    private fun restoreCopilotModelsAsync() {
        appScope.launch {
            try {
                val apiKeyRepo = com.omnidev.workspace.data.repository.ApiKeyRepository(applicationContext)
                val oauthToken = apiKeyRepo.getApiKey(ModelProvider.GITHUB_COPILOT)
                if (!oauthToken.isNullOrBlank()) {
                    CopilotModelRefresher.refreshModels(oauthToken)
                }
            } catch (e: Exception) {
                // Startup restoration is best-effort — never crash the app.
                Log.w("OmniDevApp", "Copilot model restore failed on startup: ${e.message}")
            }
        }
    }

    companion object {
        /** Application singleton for accessing context where DI isn't available. */
        lateinit var instance: OmniDevApp
            private set
    }
}
