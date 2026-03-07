package com.omnidev.workspace.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnidev.workspace.data.auth.GitHubDeviceFlowManager
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings screen for configuring external platform integrations:
 * - GitHub Device Flow (RFC 8628) — shows XXXX-XXXX code, polls for token
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
    /**
     * Optional — if null, a new instance is created from the local context.
     * Safe because Android's DataStore uses per-name file singletons; multiple
     * [ApiKeyRepository] instances pointing to the same DataStore name are backed
     * by the same file and serialize reads/writes atomically.
     */
    apiKeyRepository: ApiKeyRepository? = null,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // GitHub auth state
    var githubOAuthToken by remember { mutableStateOf<String?>(null) }

    // Device Flow state
    var deviceFlowUserCode by remember { mutableStateOf<String?>(null) }
    var deviceFlowVerificationUri by remember { mutableStateOf("https://github.com/login/device") }
    var deviceFlowPolling by remember { mutableStateOf(false) }
    var deviceFlowError by remember { mutableStateOf<String?>(null) }
    var deviceFlowInProgress by remember { mutableStateOf(false) }

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
            // ── GitHub Device Flow Section ──
            Text(
                text = "🐙 GitHub",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            if (githubOAuthToken != null) {
                // Connected state
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
                Text(
                    text = "GitHub Repos integration and GitHub AI Models are both active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            settingsRepository.setGitHubOAuthToken(null)
                            settingsRepository.setGitHubPat(null)
                            githubOAuthToken = null
                            deviceFlowUserCode = null
                            deviceFlowPolling = false
                            deviceFlowError = null
                            deviceFlowInProgress = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Log Out of GitHub")
                }
            } else {
                // Not yet connected
                Text(
                    text = "Connect your GitHub account to enable GitHub Repos and GitHub AI Models (GPT-4o, Llama, DeepSeek, Phi and more — free with your GitHub account).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (deviceFlowUserCode != null) {
                    // ── Device Flow active: show the code ──
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Step 1: Copy this code",
                            style = MaterialTheme.typography.labelLarge
                        )
                        // Large monospace code display
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = deviceFlowUserCode!!,
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 28.sp,
                                    letterSpacing = 4.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(deviceFlowUserCode!!)) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("📋 Copy Code")
                        }

                        Text(
                            text = "Step 2: Open GitHub and enter the code",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Button(
                            onClick = {
                                GitHubDeviceFlowManager.openVerificationPage(
                                    context, deviceFlowVerificationUri
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open GitHub (${deviceFlowVerificationUri})")
                        }

                        if (deviceFlowPolling) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Text(
                                    text = "Waiting for authorization...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (deviceFlowError != null) {
                            Text(
                                text = "⚠️ ${deviceFlowError}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                deviceFlowUserCode = null
                                deviceFlowPolling = false
                                deviceFlowError = null
                                deviceFlowInProgress = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel")
                        }
                    }
                } else {
                    // ── Start Device Flow button ──
                    if (deviceFlowError != null) {
                        Text(
                            text = "⚠️ ${deviceFlowError}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Button(
                        onClick = {
                            deviceFlowError = null
                            deviceFlowInProgress = true
                            scope.launch {
                                val repoArg = apiKeyRepository
                                    ?: ApiKeyRepository(context)
                                GitHubDeviceFlowManager.startDeviceFlowAndPoll(
                                    settingsRepository, repoArg
                                ).collect { state ->
                                    when (state) {
                                        is GitHubDeviceFlowManager.DeviceFlowState.AwaitingUserCode -> {
                                            deviceFlowUserCode = state.userCode
                                            deviceFlowVerificationUri = state.verificationUri
                                            deviceFlowPolling = false
                                        }
                                        is GitHubDeviceFlowManager.DeviceFlowState.Polling -> {
                                            deviceFlowPolling = true
                                        }
                                        is GitHubDeviceFlowManager.DeviceFlowState.Success -> {
                                            githubOAuthToken = state.token
                                            deviceFlowUserCode = null
                                            deviceFlowPolling = false
                                            deviceFlowInProgress = false
                                        }
                                        is GitHubDeviceFlowManager.DeviceFlowState.Error -> {
                                            deviceFlowError = state.message
                                            deviceFlowUserCode = null
                                            deviceFlowPolling = false
                                            deviceFlowInProgress = false
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !deviceFlowInProgress,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (deviceFlowInProgress) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Text("Connecting...")
                            }
                        } else {
                            Text("Connect with GitHub")
                        }
                    }
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

