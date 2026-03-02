package com.omnidev.workspace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.tools.FileToolManager
import com.omnidev.workspace.domain.engine.AgentConfig
import com.omnidev.workspace.domain.engine.AgentPipeline
import com.omnidev.workspace.ui.chat.ChatViewModel
import com.omnidev.workspace.ui.navigation.AppNavigation
import com.omnidev.workspace.ui.providers.ProvidersViewModel
import com.omnidev.workspace.ui.settings.AISettingsViewModel
import com.omnidev.workspace.ui.theme.OmniDevTheme

/**
 * Main entry point for OmniDev Workspace.
 *
 * Sets up the dependency graph manually (without DI framework) and launches
 * the Compose navigation host.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ── Manual Dependency Injection ──
        val settingsRepository = SettingsRepository(applicationContext)
        val apiKeyRepository = ApiKeyRepository(applicationContext)
        val toolManager = FileToolManager()

        // Placeholder completion provider — replace with actual API implementation.
        // request.apiKey contains the resolved key from ApiKeyRepository when available.
        val completionProvider: suspend (CompletionRequest) -> CompletionResponse = { request ->
            // TODO: Route to Anthropic/OpenAI/Gemini/Copilot APIs using request.apiKey
            CompletionResponse(
                content = "API integration pending. Model: ${request.modelId}" +
                    if (request.apiKey != null) " (key configured ✓)" else " (no key set)",
                finishReason = "placeholder"
            )
        }

        val agentPipeline = AgentPipeline(
            toolManager = toolManager,
            completionProvider = completionProvider,
            config = AgentConfig.THOROUGH,
            apiKeyRepository = apiKeyRepository
        )

        val settingsViewModel = AISettingsViewModel(settingsRepository)
        val chatViewModel = ChatViewModel(settingsRepository, agentPipeline)
        val providersViewModel = ProvidersViewModel(apiKeyRepository)

        setContent {
            OmniDevTheme {
                AppNavigation(
                    settingsViewModel = settingsViewModel,
                    chatViewModel = chatViewModel,
                    providersViewModel = providersViewModel
                )
            }
        }
    }
}
