package com.omnidev.workspace.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings screen for configuring external platform integrations:
 * - Telegram Bot Token & Chat ID
 * - GitHub Personal Access Token
 *
 * All credentials are stored in the app's private DataStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationsScreen(
    settingsRepository: SettingsRepository,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var telegramToken by remember { mutableStateOf("") }
    var telegramChatId by remember { mutableStateOf("") }
    var githubPat by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    // Load existing values
    LaunchedEffect(Unit) {
        telegramToken = settingsRepository.observeTelegramBotToken().first() ?: ""
        telegramChatId = settingsRepository.observeTelegramChatId().first() ?: ""
        githubPat = settingsRepository.observeGitHubPat().first() ?: ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Integrations & Linked Accounts") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Telegram Section ──
            Text(
                text = "📨 Telegram Bot",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Connect a Telegram Bot to let the AI publish messages to your channel or group.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = telegramToken,
                onValueChange = { telegramToken = it; saved = false },
                label = { Text("Bot Token") },
                placeholder = { Text("123456:ABCdefGHI...") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = telegramChatId,
                onValueChange = { telegramChatId = it; saved = false },
                label = { Text("Chat ID / @channel") },
                placeholder = { Text("-1001234567890 or @mychannel") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            // ── GitHub Section ──
            Text(
                text = "🐙 GitHub",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Connect a GitHub Personal Access Token to let the AI create issues and pull requests.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = githubPat,
                onValueChange = { githubPat = it; saved = false },
                label = { Text("Personal Access Token") },
                placeholder = { Text("ghp_xxxx...") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            // ── Save Button ──
            Button(
                onClick = {
                    scope.launch {
                        try {
                            settingsRepository.setTelegramBotToken(telegramToken.ifBlank { null })
                            settingsRepository.setTelegramChatId(telegramChatId.ifBlank { null })
                            settingsRepository.setGitHubPat(githubPat.ifBlank { null })
                            saved = true
                        } catch (_: Exception) {
                            saved = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (saved) "✅ Saved" else "Save All Integrations")
            }
        }
    }
}
