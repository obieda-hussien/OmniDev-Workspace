package com.omnidev.workspace.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.omnidev.workspace.OmniDevApp
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.debug.DebugLogManager
import com.omnidev.workspace.data.mcp.McpConfigManager
import com.omnidev.workspace.data.repository.AnalyticsRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.domain.engine.OmniMode
import com.omnidev.workspace.ui.analytics.AnalyticsDashboardScreen
import com.omnidev.workspace.ui.analytics.AnalyticsDashboardViewModel
import com.omnidev.workspace.ui.brain.AgentBrainDashboard
import com.omnidev.workspace.ui.brain.AgentBrainViewModel
import com.omnidev.workspace.ui.browser.BrowserViewerScreen
import com.omnidev.workspace.ui.browser.BrowserViewerViewModel
import com.omnidev.workspace.ui.chat.ChatScreen
import com.omnidev.workspace.ui.chat.ChatViewModel
import com.omnidev.workspace.ui.debug.DebugScreen
import com.omnidev.workspace.ui.debug.DebugViewModel
import com.omnidev.workspace.ui.motion.LocalOmniMotion
import com.omnidev.workspace.ui.motion.OmniEasing
import com.omnidev.workspace.ui.providers.ProvidersScreen
import com.omnidev.workspace.ui.providers.ProvidersViewModel
import com.omnidev.workspace.ui.settings.AISettingsScreen
import com.omnidev.workspace.ui.settings.AISettingsViewModel
import com.omnidev.workspace.ui.settings.IntegrationsLinkedAccountsScreen
import com.omnidev.workspace.ui.settings.LocalModelManagerScreen
import com.omnidev.workspace.ui.settings.McpSettingsScreen
import com.omnidev.workspace.ui.settings.McpSettingsViewModel
import com.omnidev.workspace.ui.settings.MemoryExplorerScreen
import com.omnidev.workspace.ui.settings.ScheduledTasksScreen
import com.omnidev.workspace.ui.settings.ToolRegistryScreen
import com.omnidev.workspace.ui.settings.UserProfileScreen
import kotlinx.coroutines.delay

object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val PROVIDERS = "providers"
    const val DEBUG = "debug"
    const val MEMORY_EXPLORER = "memory_explorer"
    const val INTEGRATIONS = "integrations"
    const val LOCAL_MODELS = "local_models"
    const val SCHEDULED_TASKS = "scheduled_tasks"
    const val TOOL_REGISTRY = "tool_registry"
    const val MCP_SETTINGS = "mcp_settings"
    const val PROFILE = "profile"
    const val ANALYTICS = "analytics"
    const val AGENT_BRAIN = "agent_brain"
    const val BROWSER_VIEWER = "browser_viewer"
}

@Composable
fun AppNavigation(
    settingsViewModel: AISettingsViewModel,
    chatViewModel: ChatViewModel,
    providersViewModel: ProvidersViewModel,
    settingsRepository: SettingsRepository,
    database: OmniDevDatabase
) {
    val navController = rememberNavController()
    val motion = LocalOmniMotion.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1

    LaunchedEffect(Unit) {
        com.omnidev.workspace.MainActivity.pendingChatSession.collect { sessionId ->
            if (sessionId != null) {
                chatViewModel.loadSession(sessionId)
                navController.navigate(Routes.CHAT) { launchSingleTop = true }
                com.omnidev.workspace.MainActivity.pendingChatSession.value = null
            }
        }
    }

    LaunchedEffect(Unit) {
        com.omnidev.workspace.MainActivity.pendingBrowserHandoff.collect { pending ->
            if (pending) {
                delay(120L)
                if (chatViewModel.uiState.value.isProcessing) {
                    chatViewModel.cancelCurrentRun()
                }
                navController.navigate(Routes.BROWSER_VIEWER) {
                    launchSingleTop = true
                    restoreState = true
                }
                com.omnidev.workspace.MainActivity.pendingBrowserHandoff.value = false
            }
        }
    }

    val startDestination = remember {
        if (DebugLogManager.consumePendingCrashRedirect()) Routes.DEBUG else Routes.CHAT
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            if (motion.reduced) EnterTransition.None else
                slideInHorizontally(tween(motion.navigationMillis, easing = OmniEasing)) { direction * it } +
                    fadeIn(tween(motion.responseMillis))
        },
        exitTransition = {
            if (motion.reduced) ExitTransition.None else
                slideOutHorizontally(tween(motion.navigationMillis, easing = OmniEasing)) { -direction * it / 5 } +
                    fadeOut(tween(motion.responseMillis))
        },
        popEnterTransition = {
            if (motion.reduced) EnterTransition.None else
                slideInHorizontally(tween(motion.navigationMillis, easing = OmniEasing)) { -direction * it / 5 } +
                    fadeIn(tween(motion.responseMillis))
        },
        popExitTransition = {
            if (motion.reduced) ExitTransition.None else
                slideOutHorizontally(tween(motion.navigationMillis, easing = OmniEasing)) { direction * it } +
                    fadeOut(tween(motion.responseMillis))
        }
    ) {
        composable(Routes.CHAT) {
            ChatScreen(
                viewModel = chatViewModel,
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onOpenBrowser = { navController.navigate(Routes.BROWSER_VIEWER) { launchSingleTop = true } }
            )
        }

        composable(Routes.SETTINGS) {
            AISettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { navController.popBackStack() },
                providersViewModel = providersViewModel,
                onNavigateToProviders = { navController.navigate(Routes.PROVIDERS) { launchSingleTop = true } },
                onNavigateToDebug = { navController.navigate(Routes.DEBUG) { launchSingleTop = true } },
                onNavigateToMemoryExplorer = { navController.navigate(Routes.MEMORY_EXPLORER) { launchSingleTop = true } },
                onNavigateToIntegrations = { navController.navigate(Routes.INTEGRATIONS) { launchSingleTop = true } },
                onNavigateToLocalModels = { navController.navigate(Routes.LOCAL_MODELS) { launchSingleTop = true } },
                onNavigateToScheduledTasks = { navController.navigate(Routes.SCHEDULED_TASKS) { launchSingleTop = true } },
                onNavigateToToolRegistry = { navController.navigate(Routes.TOOL_REGISTRY) { launchSingleTop = true } },
                onNavigateToMcpSettings = { navController.navigate(Routes.MCP_SETTINGS) { launchSingleTop = true } },
                onNavigateToProfile = { navController.navigate(Routes.PROFILE) { launchSingleTop = true } },
                onNavigateToAnalytics = { navController.navigate(Routes.ANALYTICS) { launchSingleTop = true } },
                onNavigateToAgentBrain = { navController.navigate(Routes.AGENT_BRAIN) { launchSingleTop = true } }
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

        composable(Routes.MEMORY_EXPLORER) {
            MemoryExplorerScreen(
                knowledgeDao = database.knowledgeDao(),
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.INTEGRATIONS) {
            IntegrationsLinkedAccountsScreen(
                settingsRepository = settingsRepository,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.LOCAL_MODELS) {
            LocalModelManagerScreen(
                settingsRepository = settingsRepository,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SCHEDULED_TASKS) {
            ScheduledTasksScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.MCP_SETTINGS) {
            val context = LocalContext.current
            val mcpConfigManager = remember { McpConfigManager(context) }
            val mcpViewModel: McpSettingsViewModel = viewModel(
                factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return McpSettingsViewModel(mcpConfigManager) as T
                    }
                }
            )
            McpSettingsScreen(
                viewModel = mcpViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.TOOL_REGISTRY) {
            ToolRegistryScreen(
                onNavigateBack = { navController.popBackStack() },
                onCreateSkillWithOmni = { prompt ->
                    chatViewModel.setMode(OmniMode.AGENT)
                    chatViewModel.onInputChanged(prompt)
                    chatViewModel.sendMessage()
                    navController.navigate(Routes.CHAT) {
                        popUpTo(Routes.CHAT) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Routes.PROFILE) {
            UserProfileScreen(
                settingsRepository = settingsRepository,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.ANALYTICS) {
            val context = LocalContext.current.applicationContext
            val analyticsViewModel: AnalyticsDashboardViewModel = viewModel(
                factory = remember(context) {
                    viewModelFactory { initializer { AnalyticsDashboardViewModel(AnalyticsRepository(context)) } }
                }
            )
            AnalyticsDashboardScreen(
                viewModel = analyticsViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.AGENT_BRAIN) {
            val app = OmniDevApp.instance
            val agentBrainViewModel: AgentBrainViewModel = viewModel(
                factory = AgentBrainViewModel.factory(
                    bridge = app.smartLearningBridge,
                    journal = app.toolExecutionJournal,
                    awarenessEngine = app.toolAwarenessEngine
                )
            )
            AgentBrainDashboard(
                viewModel = agentBrainViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.BROWSER_VIEWER) {
            val app = OmniDevApp.instance
            val browserViewModel: BrowserViewerViewModel = viewModel(
                factory = BrowserViewerViewModel.factory(app.headlessBrowserManager)
            )
            BrowserViewerScreen(
                viewModel = browserViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
