package com.omnidev.workspace.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.omnidev.workspace.data.repository.SettingsRepository

/** Legacy route recovery only; Lite contains no account-management page. */
@Composable
fun IntegrationsLinkedAccountsScreen(settingsRepository: SettingsRepository, onNavigateBack: () -> Unit) {
    LaunchedEffect(Unit) { onNavigateBack() }
}
