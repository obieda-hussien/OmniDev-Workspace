package com.omnidev.workspace.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.MainActivity
import com.omnidev.workspace.data.auth.OAuthManager
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings screen for configuring external platform integrations:
 * - GitHub OAuth 2.0 (Connect with GitHub button / disconnect)
 * - Telegram Bot Token & Chat ID
 * - Discord Webhook URL
 * - Notion API Key & Database ID
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
    val context = LocalContext.current

    // GitHub OAuth state
    var githubOAuthToken by remember { mutableStateOf<String?>(null) }

    // Telegram
    var telegramToken by remember { mutableStateOf("") }
    var telegramChatId by remember { mutableStateOf("") }

    // Discord
    var discordWebhookUrl by remember { mutableStateOf("") }

    // Notion
    var notionApiKey by remember { mutableStateOf("") }
    var notionDatabaseId by remember { mutableStateOf("") }

    var saved by remember { mutableStateOf(false) }

    // Load existing values on first composition
    LaunchedEffect(Unit) {
        githubOAuthToken = settingsRepository.observeGitHubOAuthToken().first()
        telegramToken = settingsRepository.observeTelegramBotToken().first() ?: ""
        telegramChatId = settingsRepository.observeTelegramChatId().first() ?: ""
        discordWebhookUrl = settingsRepository.observeDiscordWebhookUrl().first() ?: ""
        notionApiKey = settingsRepository.observeNotionApiKey().first() ?: ""
        notionDatabaseId = settingsRepository.observeNotionDatabaseId().first() ?: ""
    }

    // Observe pending OAuth code delivered by MainActivity deep link handling.
    // NOTE: The authorization code must be exchanged for an access token on a backend server
    // that holds the client_secret securely. If you have configured a backend, call
    // OAuthManager.handleGitHubCallback(code, clientSecret, settingsRepository) here.
    // Until a backend is wired up, the code is intentionally NOT stored — the user will see
    // the "Connect with GitHub" button again.
    val pendingCode by MainActivity.pendingOAuthCode.collectAsState()
    LaunchedEffect(pendingCode) {
        val code = pendingCode ?: return@LaunchedEffect
        MainActivity.pendingOAuthCode.value = null
        // Backend token exchange would happen here. Example (with your own endpoint):
        //   val result = OAuthManager.handleGitHubCallback(code, BuildConfig.GITHUB_CLIENT_SECRET, settingsRepository)
        //   result.onSuccess { githubOAuthToken = it }
        // Refresh the UI state after any potential external exchange
        githubOAuthToken = settingsRepository.observeGitHubOAuthToken().first()
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
            // ── GitHub OAuth Section ──
            Text(
                text = "🐙 GitHub",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            if (githubOAuthToken != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Connected",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Connected to GitHub ✓",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            settingsRepository.setGitHubOAuthToken(null)
                            settingsRepository.setGitHubPat(null)
                            githubOAuthToken = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Log Out of GitHub")
                }
            } else {
                Text(
                    text = "Connect your GitHub account to let the AI create issues and pull requests on your behalf.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { OAuthManager.launchGitHubAuth(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect with GitHub")
                }
            }

            HorizontalDivider()

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

            // ── Discord Section ──
            Text(
                text = "🎮 Discord",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Add a Discord webhook URL to let the AI send notifications to your Discord server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = discordWebhookUrl,
                onValueChange = { discordWebhookUrl = it; saved = false },
                label = { Text("Webhook URL") },
                placeholder = { Text("https://discord.com/api/webhooks/...") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            // ── Notion Section ──
            Text(
                text = "📝 Notion",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Connect a Notion Integration to let the AI create pages in your database.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = notionApiKey,
                onValueChange = { notionApiKey = it; saved = false },
                label = { Text("Notion API Key") },
                placeholder = { Text("secret_...") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = notionDatabaseId,
                onValueChange = { notionDatabaseId = it; saved = false },
                label = { Text("Database ID") },
                placeholder = { Text("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx") },
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
                            settingsRepository.setDiscordWebhookUrl(discordWebhookUrl.ifBlank { null })
                            settingsRepository.setNotionApiKey(notionApiKey.ifBlank { null })
                            settingsRepository.setNotionDatabaseId(notionDatabaseId.ifBlank { null })
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
