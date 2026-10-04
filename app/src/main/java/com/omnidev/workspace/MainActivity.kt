package com.omnidev.workspace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.omnidev.workspace.data.auth.OAuthManager
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.tools.NotificationCaptureTool
import com.omnidev.workspace.data.tools.PermissionRequestBridge
import com.omnidev.workspace.ui.browser.BrowserFileChooserBridge
import com.omnidev.workspace.ui.navigation.AppNavigation
import com.omnidev.workspace.ui.providers.ProvidersViewModel
import com.omnidev.workspace.ui.settings.AISettingsViewModel
import com.omnidev.workspace.ui.theme.OmniDevTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Main entry point for OmniDev Workspace. */
class MainActivity : ComponentActivity() {

    companion object {
        /** Emits the OAuth authorization code received via deep link callback. */
        val pendingOAuthCode: MutableStateFlow<String?> = MutableStateFlow(null)
        val pendingChatSession = MutableStateFlow<Long?>(null)
        data class OmniSearchNavigation(val id: String, val prompt: String?)
        val pendingOmniSearch = MutableStateFlow<OmniSearchNavigation?>(null)

        /**
         * One-shot navigation signal used by the autonomous agent when it reaches
         * a password/OTP/CAPTCHA/passkey/payment/consent step that needs a human.
         * AppNavigation consumes it and opens Browser Viewer immediately.
         */
        val pendingBrowserHandoff = MutableStateFlow(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PermissionRequestBridge.attach(this)
        enableEdgeToEdge()

        NotificationCaptureTool.initialize(applicationContext)

        val settingsRepository = SettingsRepository(applicationContext)
        val apiKeyRepository = ApiKeyRepository(applicationContext)
        val database = OmniDevDatabase.getInstance(applicationContext)
        val settingsViewModel = ViewModelProvider(this, viewModelFactory {
            initializer { AISettingsViewModel(settingsRepository) }
        })[AISettingsViewModel::class.java]
        val chatViewModel = WorkspaceChatRuntime.get(applicationContext)
        val providersViewModel = ViewModelProvider(this, viewModelFactory {
            initializer { ProvidersViewModel(apiKeyRepository, settingsRepository) }
        })[ProvidersViewModel::class.java]

        setContent {
            OmniDevTheme {
                AppNavigation(
                    settingsViewModel = settingsViewModel,
                    chatViewModel = chatViewModel,
                    providersViewModel = providersViewModel,
                    settingsRepository = settingsRepository,
                    database = database
                )
            }
        }

        handleOAuthCallback(intent)
        handleNavigationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        PermissionRequestBridge.attach(this)
    }

    override fun onPause() {
        PermissionRequestBridge.detach(this)
        super.onPause()
    }

    @Deprecated("WebView FileChooserParams still delivers results through Activity results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (BrowserFileChooserBridge.handleActivityResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) OmniDevApp.instance.headlessBrowserManager.onAppClosed()
    }

    override fun onDestroy() {
        PermissionRequestBridge.detach(this)
        if (isFinishing) BrowserFileChooserBridge.cancelPending()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthCallback(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent) {
        if (intent.getBooleanExtra("open_assistant_conversation", false)) {
            intent.removeExtra("open_assistant_conversation")
            val assistantSession = intent.getLongExtra("assistant_session_id", -1L)
            if (assistantSession >= 0) WorkspaceChatRuntime.get(this).loadSession(assistantSession)
            pendingOmniSearch.value = OmniSearchNavigation(java.util.UUID.randomUUID().toString(), null)
            return
        }
        if (intent.action == com.omnilink.sdk.OmniLinkConstants.ACTION_PUBLIC_OMNI_REQUEST) {
            val navigation = runCatching {
                intent.getStringExtra(com.omnilink.sdk.OmniLinkConstants.EXTRA_PUBLIC_REQUEST_JSON)
                    ?.let(com.omnidev.workspace.data.ipc.OmniSearchIngress::parse)
            }.getOrNull()
            // Consume once so activity recreation cannot reinsert an already submitted question.
            intent.removeExtra(com.omnilink.sdk.OmniLinkConstants.EXTRA_PUBLIC_REQUEST_JSON)
            if (navigation != null) {
                pendingOmniSearch.value = OmniSearchNavigation(java.util.UUID.randomUUID().toString(), navigation.prompt)
            }
            return
        }

        intent.getLongExtra("deep_link_session_id", -1L)
            .takeIf { it > 0 }
            ?.let { pendingChatSession.value = it }

        if (intent.hasExtra("browser_handoff_notification_id") ||
            intent.getBooleanExtra("open_browser_handoff", false)
        ) {
            pendingBrowserHandoff.value = true
        }
    }

    private fun handleOAuthCallback(intent: Intent) {
        val data: Uri = intent.data ?: return
        if (data.scheme == "omnidev" && data.host == "task") {
            data.getQueryParameter("id")?.let { taskId ->
                lifecycleScope.launch {
                    val session = OmniDevDatabase.getInstance(applicationContext).chatSessionDao()
                        .getByBackgroundKey("scheduled_task:$taskId")
                    session?.let { pendingChatSession.value = it.id }
                }
            }
            return
        }
        val code = OAuthManager.extractCodeFromCallback(data) ?: return
        pendingOAuthCode.value = code
    }
}
