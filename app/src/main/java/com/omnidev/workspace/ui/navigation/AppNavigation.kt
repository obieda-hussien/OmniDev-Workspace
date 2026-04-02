package com.omnidev.workspace.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.omnidev.workspace.OmniDevApp
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.repository.AnalyticsRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.ui.analytics.AnalyticsDashboardScreen
import com.omnidev.workspace.ui.analytics.AnalyticsDashboardViewModel
import com.omnidev.workspace.ui.brain.AgentBrainDashboard
import com.omnidev.workspace.ui.brain.AgentBrainViewModel
import com.omnidev.workspace.ui.chat.ChatScreen
import com.omnidev.workspace.ui.chat.ChatViewModel
import com.omnidev.workspace.ui.debug.DebugScreen
import com.omnidev.workspace.ui.debug.DebugViewModel
import com.omnidev.workspace.ui.providers.ProvidersScreen
import com.omnidev.workspace.ui.providers.ProvidersViewModel
import com.omnidev.workspace.ui.settings.AISettingsScreen
import com.omnidev.workspace.ui.settings.AISettingsViewModel
import com.omnidev.workspace.ui.settings.IntegrationsScreen
import com.omnidev.workspace.ui.settings.LocalModelManagerScreen
import com.omnidev.workspace.ui.settings.MemoryExplorerScreen
import com.omnidev.workspace.ui.settings.ScheduledTasksScreen
import com.omnidev.workspace.ui.settings.SystemPromptEditorScreen
import com.omnidev.workspace.ui.settings.ToolRegistryScreen
import com.omnidev.workspace.ui.settings.UserProfileScreen

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
    const val INTEGRATIONS = "integrations"
    const val LOCAL_MODELS = "local_models"
    const val SCHEDULED_TASKS = "scheduled_tasks"
    const val TOOL_REGISTRY = "tool_registry"
    const val PROFILE = "profile"
    const val ANALYTICS = "analytics"
    const val AGENT_BRAIN = "agent_brain"  // شاشة عقل الـ Agent الجديدة
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
                onNavigateToMemoryExplorer = { navController.navigate(Routes.MEMORY_EXPLORER) },
                onNavigateToIntegrations = { navController.navigate(Routes.INTEGRATIONS) },
                onNavigateToLocalModels = { navController.navigate(Routes.LOCAL_MODELS) },
                onNavigateToScheduledTasks = { navController.navigate(Routes.SCHEDULED_TASKS) },
                onNavigateToToolRegistry = { navController.navigate(Routes.TOOL_REGISTRY) },
                onNavigateToProfile = { navController.navigate(Routes.PROFILE) },
                onNavigateToAnalytics = { navController.navigate(Routes.ANALYTICS) },
                onNavigateToAgentBrain = { navController.navigate(Routes.AGENT_BRAIN) }
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

        composable(Routes.INTEGRATIONS) {
            IntegrationsScreen(
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

        composable(Routes.TOOL_REGISTRY) {
            ToolRegistryScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Routes.PROFILE) {
            UserProfileScreen(
                settingsRepository = settingsRepository,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.ANALYTICS) {
            val analyticsViewModel = AnalyticsDashboardViewModel(
                AnalyticsRepository(navController.context)
            )
            AnalyticsDashboardScreen(
                viewModel = analyticsViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // ═══════════════════════════════════════════════════════════════
        // 🧠 Agent Brain Dashboard — لوحة تحكم عقل الـ Agent
        // ═══════════════════════════════════════════════════════════════
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
    }
}
