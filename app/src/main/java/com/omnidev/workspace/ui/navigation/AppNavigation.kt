package com.omnidev.workspace.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.ui.chat.ChatScreen
import com.omnidev.workspace.ui.chat.ChatViewModel
import com.omnidev.workspace.ui.debug.DebugScreen
import com.omnidev.workspace.ui.debug.DebugViewModel
import com.omnidev.workspace.ui.providers.ProvidersScreen
import com.omnidev.workspace.ui.providers.ProvidersViewModel
import com.omnidev.workspace.ui.settings.AISettingsScreen
import com.omnidev.workspace.ui.settings.AISettingsViewModel
import com.omnidev.workspace.ui.settings.MemoryExplorerScreen
import com.omnidev.workspace.ui.settings.SystemPromptEditorScreen

/**
 * Navigation route constants.
 */
object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val PROVIDERS = "providers"
    const val DEBUG = "debug"
    const val SYSTEM_PROMPT = "system_prompt"
    const val MEMORY_EXPLORER = "memory_explorer"
}

/**
 * Top-level navigation host for OmniDev Workspace.
 */
@Composable
fun AppNavigation(
    settingsViewModel: AISettingsViewModel,
    chatViewModel: ChatViewModel,
    providersViewModel: ProvidersViewModel,
    settingsRepository: SettingsRepository,
    database: OmniDevDatabase
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.CHAT
    ) {
        composable(Routes.CHAT) {
            ChatScreen(
                viewModel = chatViewModel,
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(Routes.SETTINGS) {
            AISettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToProviders = { navController.navigate(Routes.PROVIDERS) },
                onNavigateToDebug = { navController.navigate(Routes.DEBUG) },
                onNavigateToSystemPrompt = { navController.navigate(Routes.SYSTEM_PROMPT) },
                onNavigateToMemoryExplorer = { navController.navigate(Routes.MEMORY_EXPLORER) }
            )
        }

        composable(Routes.PROVIDERS) {
            ProvidersScreen(
                viewModel = providersViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.DEBUG) {
            val debugViewModel: DebugViewModel = viewModel()
            DebugScreen(
                viewModel = debugViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SYSTEM_PROMPT) {
            SystemPromptEditorScreen(
                settingsRepository = settingsRepository,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.MEMORY_EXPLORER) {
            MemoryExplorerScreen(
                knowledgeDao = database.knowledgeDao(),
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
