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
import android.content.Intent
import com.omnidev.workspace.data.auth.GitHubDeviceFlowManager
import com.omnidev.workspace.data.integration.DiscordPollingService
import com.omnidev.workspace.data.integration.TelegramPollingService
import com.omnidev.workspace.data.integration.WhatsAppBridgeService
import com.omnidev.workspace.data.model.ModelProvider
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings screen for configuring external platform integrations:
 * - GitHub Device Flow (RFC 8628) with sub-mode toggle (Copilot / Models)
 * - Telegram Bot Token & Chat ID + Polling listener toggle (OpenClaw-style)
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

    // Which GitHub sub-mode the user has selected (persisted)
    var selectedSubMode by remember { mutableStateOf(GitHubDeviceFlowManager.SubMode.COPILOT) }
    // Which sub-mode the currently stored token belongs to (loaded from DataStore)
    var connectedSubMode by remember { mutableStateOf<GitHubDeviceFlowManager.SubMode?>(null) }

    // Device Flow state (Copilot only)
    var deviceFlowUserCode by remember { mutableStateOf<String?>(null) }
    var deviceFlowVerificationUri by remember { mutableStateOf("https://github.com/login/device") }
    var deviceFlowPolling by remember { mutableStateOf(false) }
    var deviceFlowError by remember { mutableStateOf<String?>(null) }
    var deviceFlowInProgress by remember { mutableStateOf(false) }

    // GitHub Models PAT (Personal Access Token) — used instead of Device Flow for Models
    var githubModelsPat by remember { mutableStateOf("") }
    var githubModelsPatError by remember { mutableStateOf<String?>(null) }

    // Telegram
    var telegramToken by remember { mutableStateOf("") }
    var telegramChatId by remember { mutableStateOf("") }

    // Discord Webhook (legacy)
    var discordWebhookUrl by remember { mutableStateOf("") }

    // Discord Bot (new full integration)
    var discordBotToken by remember { mutableStateOf("") }
    var discordListenerChannelId by remember { mutableStateOf("") }
    var discordListenerEnabled by remember { mutableStateOf(false) }

    // WhatsApp Business Cloud API
    var whatsappPhoneNumberId by remember { mutableStateOf("") }
    var whatsappAccessToken by remember { mutableStateOf("") }

    // WhatsApp Baileys Bridge
    var whatsappBridgeUrl by remember { mutableStateOf("") }
    var whatsappBridgePhone by remember { mutableStateOf("") }
    var whatsappBridgeEnabled by remember { mutableStateOf(false) }
    var whatsappBridgePairingCode by remember { mutableStateOf<String?>(null) }
    var whatsappBridgePairingLoading by remember { mutableStateOf(false) }
    var whatsappBridgeStatus by remember { mutableStateOf<String?>(null) }

    // Notion
    var notionApiKey by remember { mutableStateOf("") }
    var notionDatabaseId by remember { mutableStateOf("") }

    var saved by remember { mutableStateOf(false) }

    // Load existing values on first composition
    LaunchedEffect(Unit) {
        githubOAuthToken = settingsRepository.observeGitHubOAuthToken().first()
        // Restore the persisted sub-mode so the UI reflects the actual connected state
        val storedMode = settingsRepository.observeGitHubSubMode().first()
        val mode = GitHubDeviceFlowManager.SubMode.fromSerializedName(storedMode)
        if (githubOAuthToken != null) {
            connectedSubMode = mode
            selectedSubMode  = mode
        } else {
            selectedSubMode = mode
        }
        // Load saved GitHub Models PAT
        val repo = apiKeyRepository ?: ApiKeyRepository(context)
        githubModelsPat = repo.getApiKey(ModelProvider.GITHUB_MODELS) ?: ""
        telegramToken = settingsRepository.observeTelegramBotToken().first() ?: ""
        telegramChatId = settingsRepository.observeTelegramChatId().first() ?: ""
        discordWebhookUrl = settingsRepository.observeDiscordWebhookUrl().first() ?: ""
        discordBotToken = settingsRepository.observeDiscordBotToken().first() ?: ""
        discordListenerChannelId = settingsRepository.observeDiscordListenerChannelId().first() ?: ""
        discordListenerEnabled = settingsRepository.observeDiscordListenerEnabled().first()
        whatsappPhoneNumberId = settingsRepository.observeWhatsAppPhoneNumberId().first() ?: ""
        whatsappAccessToken = settingsRepository.observeWhatsAppAccessToken().first() ?: ""
        whatsappBridgeUrl = settingsRepository.observeWhatsAppBridgeUrl().first() ?: ""
        whatsappBridgePhone = settingsRepository.observeWhatsAppBridgePhone().first() ?: ""
        whatsappBridgeEnabled = settingsRepository.observeWhatsAppBridgeEnabled().first()
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
                // ── Connected state ──────────────────────────────────────────
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
                val modeLabel = when (connectedSubMode) {
                    GitHubDeviceFlowManager.SubMode.COPILOT ->
                        "GitHub Copilot is active — models routed via api.githubcopilot.com."
                    else ->
                        "GitHub Repos integration and GitHub AI Models are both active."
                }
                Text(
                    text = modeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            settingsRepository.setGitHubOAuthToken(null)
                            settingsRepository.setGitHubPat(null)
                            // Clear only the key for the active sub-mode
                            val repo = apiKeyRepository ?: ApiKeyRepository(context)
                            when (connectedSubMode) {
                                GitHubDeviceFlowManager.SubMode.COPILOT ->
                                    repo.clearApiKey(ModelProvider.GITHUB_COPILOT)
                                GitHubDeviceFlowManager.SubMode.MODELS -> {
                                    repo.clearApiKey(ModelProvider.GITHUB_MODELS)
                                    githubModelsPat = ""
                                }
                                null -> {
                                    repo.clearApiKey(ModelProvider.GITHUB_COPILOT)
                                    repo.clearApiKey(ModelProvider.GITHUB_MODELS)
                                    githubModelsPat = ""
                                }
                            }
                            githubOAuthToken = null
                            connectedSubMode = null
                            githubModelsPatError = null
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
                // ── Not yet connected ────────────────────────────────────────

                // Sub-mode selector
                Text(
                    text = "Choose connection type:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // ── Copilot option ──
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(
                            selected = selectedSubMode == GitHubDeviceFlowManager.SubMode.COPILOT,
                            onClick = { selectedSubMode = GitHubDeviceFlowManager.SubMode.COPILOT }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🤖 GitHub Copilot",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Zero registration · Uses your Copilot subscription · Access GPT-4o, Claude, Gemini, o3-mini",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // ── Models option ──
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(
                            selected = selectedSubMode == GitHubDeviceFlowManager.SubMode.MODELS,
                            onClick = { selectedSubMode = GitHubDeviceFlowManager.SubMode.MODELS }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🛒 GitHub Models",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Enter your GitHub PAT · Free AI marketplace · GPT-4o, Llama, DeepSeek, Phi and more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (deviceFlowUserCode != null) {
                    // ── Device Flow active: show the code ───────────────────
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
                    if (selectedSubMode == GitHubDeviceFlowManager.SubMode.MODELS) {
                        // ── GitHub Models: PAT input ─────────────────────────
                        Text(
                            text = "Enter your GitHub Personal Access Token",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = githubModelsPat,
                            onValueChange = { githubModelsPat = it; githubModelsPatError = null },
                            label = { Text("GitHub PAT (ghp_…)") },
                            placeholder = { Text("ghp_xxxxxxxxxxxxxxxxxxxx") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text(
                            text = "Generate at github.com/settings/tokens → Classic token → repo scope",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (githubModelsPatError != null) {
                            Text(
                                text = "⚠️ ${githubModelsPatError}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Button(
                            onClick = {
                                val pat = githubModelsPat.trim()
                                if (pat.isBlank()) {
                                    githubModelsPatError = "Token cannot be empty."
                                    return@Button
                                }
                                scope.launch {
                                    val repo = apiKeyRepository ?: ApiKeyRepository(context)
                                    // Store PAT in three places that serve different consumers:
                                    //  • ApiKeyRepository/GITHUB_MODELS → CompletionService (AI calls)
                                    //  • SettingsRepository.githubOAuthToken → UI "connected" state
                                    //  • SettingsRepository.githubPat → GitHubManagerTool (repo ops)
                                    repo.setApiKey(ModelProvider.GITHUB_MODELS, pat)
                                    settingsRepository.setGitHubOAuthToken(pat)
                                    settingsRepository.setGitHubPat(pat)
                                    settingsRepository.setGitHubSubMode(
                                        GitHubDeviceFlowManager.SubMode.MODELS.serializedName
                                    )
                                    githubOAuthToken = pat
                                    connectedSubMode = GitHubDeviceFlowManager.SubMode.MODELS
                                    githubModelsPatError = null
                                }
                            },
                            enabled = githubModelsPat.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save Token")
                        }
                    } else {
                        // ── GitHub Copilot: Device Flow start button ─────────
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
                                val chosenMode = selectedSubMode
                                scope.launch {
                                    val repoArg = apiKeyRepository
                                        ?: ApiKeyRepository(context)
                                    GitHubDeviceFlowManager.startDeviceFlowAndPoll(
                                        settingsRepository, repoArg, chosenMode
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
                                                connectedSubMode = chosenMode
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
                                Text("Connect with ${selectedSubMode.displayName}")
                            }
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

            // Telegram Bot Listener toggle (OpenClaw-style polling)
            var telegramBotRunning by remember { mutableStateOf(TelegramPollingService.isRunning) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🤖 Telegram Bot Listener",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = if (telegramBotRunning)
                            "Active — Omni is listening to Telegram messages"
                        else
                            "Start to let Omni listen and reply to Telegram messages (OpenClaw-style)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (telegramBotRunning)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = telegramBotRunning,
                    onCheckedChange = { enabled ->
                        telegramBotRunning = enabled
                        val svcIntent = Intent(context, TelegramPollingService::class.java)
                        if (enabled) {
                            context.startService(svcIntent)
                        } else {
                            svcIntent.action = TelegramPollingService.ACTION_STOP
                            context.startService(svcIntent)
                        }
                    }
                )
            }

            HorizontalDivider()

            // ── Discord Section ──
            Text(
                text = "🎮 Discord",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Discord Webhook: let the AI push notifications to a channel.\nDiscord Bot: full bidirectional listener — the AI reads messages and responds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = discordWebhookUrl,
                onValueChange = { discordWebhookUrl = it; saved = false },
                label = { Text("Webhook URL (one-way notifications)") },
                placeholder = { Text("https://discord.com/api/webhooks/...") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = discordBotToken,
                onValueChange = { discordBotToken = it; saved = false },
                label = { Text("Bot Token (full bidirectional)") },
                placeholder = { Text("Bot Token from Discord Developer Portal") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = discordListenerChannelId,
                onValueChange = { discordListenerChannelId = it; saved = false },
                label = { Text("Default Channel ID to listen on") },
                placeholder = { Text("Channel ID from Discord (right-click → Copy ID)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (discordListenerEnabled) "✅ Discord Bot Listener — Running" else "Discord Bot Listener",
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = discordListenerEnabled,
                    onCheckedChange = { enabled ->
                        discordListenerEnabled = enabled
                        scope.launch {
                            settingsRepository.setDiscordListenerEnabled(enabled)
                            if (enabled) {
                                context.startForegroundService(
                                    Intent(context, DiscordPollingService::class.java)
                                )
                            } else {
                                context.startService(
                                    Intent(context, DiscordPollingService::class.java)
                                        .apply { action = DiscordPollingService.ACTION_STOP }
                                )
                            }
                        }
                    }
                )
            }

            HorizontalDivider()

            // ── WhatsApp Section ──
            Text(
                text = "💬 WhatsApp",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Connect via Meta WhatsApp Business Cloud API to let the AI send messages, images, documents, locations and contacts.\n\nSetup: Meta Business Suite → WhatsApp → Get Started → copy Phone Number ID and Access Token.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = whatsappPhoneNumberId,
                onValueChange = { whatsappPhoneNumberId = it; saved = false },
                label = { Text("Phone Number ID") },
                placeholder = { Text("1234567890 from Meta Business Suite") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = whatsappAccessToken,
                onValueChange = { whatsappAccessToken = it; saved = false },
                label = { Text("Access Token") },
                placeholder = { Text("EAAxxxxxxxx...") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            // ── WhatsApp Bridge Section (Baileys) ──
            Text(
                text = "📱 WhatsApp Bridge (Baileys)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Link your personal WhatsApp account via a self-hosted Baileys bridge server.\n\n" +
                    "Setup:\n" +
                    "1. Host the companion Node.js bridge server (Baileys)\n" +
                    "2. Enter the bridge URL and your phone number below\n" +
                    "3. Tap 'Request Pairing Code'\n" +
                    "4. Open WhatsApp → Linked Devices → Link with phone number → enter the code\n" +
                    "5. Enable the listener toggle",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = whatsappBridgeUrl,
                onValueChange = { whatsappBridgeUrl = it; saved = false },
                label = { Text("Bridge Server URL") },
                placeholder = { Text("http://192.168.1.100:3000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = whatsappBridgePhone,
                onValueChange = { whatsappBridgePhone = it; saved = false },
                label = { Text("Phone Number (international format)") },
                placeholder = { Text("201012345678") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            // Pairing code request button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (whatsappBridgeUrl.isBlank() || whatsappBridgePhone.isBlank()) return@Button
                        whatsappBridgePairingLoading = true
                        whatsappBridgePairingCode = null
                        whatsappBridgeStatus = null
                        scope.launch {
                            try {
                                settingsRepository.setWhatsAppBridgeUrl(whatsappBridgeUrl.trimEnd('/'))
                                settingsRepository.setWhatsAppBridgePhone(whatsappBridgePhone)
                                val url = java.net.URL("${whatsappBridgeUrl.trimEnd('/')}/pair")
                                val conn = url.openConnection() as java.net.HttpURLConnection
                                conn.requestMethod = "POST"
                                conn.setRequestProperty("Content-Type", "application/json")
                                conn.doOutput = true
                                conn.connectTimeout = 10_000
                                conn.readTimeout = 15_000
                                conn.connect()
                                val body = org.json.JSONObject().apply { put("phone", whatsappBridgePhone) }
                                java.io.OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                                val responseCode = conn.responseCode
                                val text = if (responseCode in 200..299) {
                                    conn.inputStream.bufferedReader().readText()
                                } else {
                                    conn.errorStream?.bufferedReader()?.readText() ?: "Error $responseCode"
                                }
                                conn.disconnect()
                                val json = org.json.JSONObject(text)
                                val code = json.optString("code", "").ifBlank { json.optString("pairingCode", "") }
                                whatsappBridgePairingCode = code.ifBlank { "Error: ${json.optString("error", text)}" }
                            } catch (e: Exception) {
                                whatsappBridgePairingCode = "Error: ${e.message}"
                            } finally {
                                whatsappBridgePairingLoading = false
                            }
                        }
                    },
                    enabled = !whatsappBridgePairingLoading && whatsappBridgeUrl.isNotBlank() && whatsappBridgePhone.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    if (whatsappBridgePairingLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Request Pairing Code")
                }
                // Check status button
                IconButton(
                    onClick = {
                        if (whatsappBridgeUrl.isBlank()) return@IconButton
                        scope.launch {
                            try {
                                val url = java.net.URL("${whatsappBridgeUrl.trimEnd('/')}/status")
                                val conn = url.openConnection() as java.net.HttpURLConnection
                                conn.requestMethod = "GET"
                                conn.connectTimeout = 5_000
                                conn.readTimeout = 5_000
                                conn.connect()
                                val text = conn.inputStream.bufferedReader().readText()
                                conn.disconnect()
                                val json = org.json.JSONObject(text)
                                whatsappBridgeStatus = json.optString("status", "unknown")
                            } catch (e: Exception) {
                                whatsappBridgeStatus = "unreachable"
                            }
                        }
                    }
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Check status")
                }
            }
            // Show pairing code if available
            whatsappBridgePairingCode?.let { code ->
                if (code.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (code.startsWith("Error"))
                                MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (!code.startsWith("Error")) {
                                Text(
                                    "🔑 Pairing Code",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    code,
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 4.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    "Open WhatsApp → Linked Devices → Link with phone number → enter this code",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                                    Text("Copy Code")
                                }
                            } else {
                                Text(
                                    code,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }
            // Show connection status badge
            whatsappBridgeStatus?.let { status ->
                val isConnected = status.lowercase() == "connected" || status.lowercase() == "open"
                Text(
                    "Bridge: $status",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            // Enable/disable listener toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (whatsappBridgeEnabled) "✅ WhatsApp Bridge Listener — Running" else "WhatsApp Bridge Listener",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = whatsappBridgeEnabled,
                    onCheckedChange = { enabled ->
                        whatsappBridgeEnabled = enabled
                        scope.launch {
                            settingsRepository.setWhatsAppBridgeEnabled(enabled)
                            settingsRepository.setWhatsAppBridgeUrl(whatsappBridgeUrl.trimEnd('/'))
                            settingsRepository.setWhatsAppBridgePhone(whatsappBridgePhone)
                        }
                        if (enabled) {
                            context.startForegroundService(Intent(context, WhatsAppBridgeService::class.java))
                        } else {
                            context.startService(
                                Intent(context, WhatsAppBridgeService::class.java)
                                    .apply { action = WhatsAppBridgeService.ACTION_STOP }
                            )
                        }
                    }
                )
            }

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
                            settingsRepository.setDiscordBotToken(discordBotToken.ifBlank { null })
                            settingsRepository.setDiscordListenerChannelId(discordListenerChannelId.ifBlank { null })
                            settingsRepository.setWhatsAppPhoneNumberId(whatsappPhoneNumberId.ifBlank { null })
                            settingsRepository.setWhatsAppAccessToken(whatsappAccessToken.ifBlank { null })
                            settingsRepository.setWhatsAppBridgeUrl(whatsappBridgeUrl.ifBlank { null })
                            settingsRepository.setWhatsAppBridgePhone(whatsappBridgePhone.ifBlank { null })
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

