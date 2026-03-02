package com.omnidev.workspace.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.omnidev.workspace.ui.chat.ChatScreen
import com.omnidev.workspace.ui.chat.ChatViewModel
import com.omnidev.workspace.ui.providers.ProvidersScreen
import com.omnidev.workspace.ui.providers.ProvidersViewModel
import com.omnidev.workspace.ui.settings.AISettingsScreen
import com.omnidev.workspace.ui.settings.AISettingsViewModel

/**
 * Navigation route constants.
 */
object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val PROVIDERS = "providers"
}

/**
 * Top-level navigation host for OmniDev Workspace.
 */
@Composable
fun AppNavigation(
    settingsViewModel: AISettingsViewModel,
    chatViewModel: ChatViewModel,
    providersViewModel: ProvidersViewModel
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
                onNavigateToProviders = { navController.navigate(Routes.PROVIDERS) }
            )
        }

        composable(Routes.PROVIDERS) {
            ProvidersScreen(
                viewModel = providersViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
