package com.omnidev.workspace.data.integration

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.data.network.CompletionService
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.ChatRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.tools.AgentBrainTools
import com.omnidev.workspace.data.tools.BuildDoctorTools
import com.omnidev.workspace.data.tools.CompositeToolManager
import com.omnidev.workspace.data.tools.DiscordBotTool
import com.omnidev.workspace.data.tools.FileToolManager
import com.omnidev.workspace.data.tools.HeadlessBrowserManager
import com.omnidev.workspace.data.tools.MemoryManager
import com.omnidev.workspace.data.tools.RepoContextTools
import com.omnidev.workspace.data.tools.RollbackTools
import com.omnidev.workspace.domain.engine.AgentConfig
import com.omnidev.workspace.domain.engine.AgentPipeline
import com.omnidev.workspace.domain.engine.OmniMode
import com.omnidev.workspace.domain.engine.SwarmOrchestrator
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Foreground service that polls the Discord Bot REST API for incoming messages and
 * responds using the AI — mirroring the OpenClaw/TelegramPollingService architecture.
 *
 * ## Modes (per-channel)
 * Each Discord channel can be placed in one of three modes via `!mode_chat`, `!mode_agent`,
 * or `!mode_swarm`:
 * - **CHAT** (default) — Direct CompletionService call, no tool access.
 * - **AGENT** — Full ReAct loop with all tools (AgentPipeline).
 * - **SWARM** — Multi-agent orchestration (SwarmOrchestrator → workers).
 *
 * ## Conversation mirroring
 * All Discord conversations are mirrored to [discordMessages] — a static [StateFlow].
 *
 * ## Prefix
 * The bot responds to messages that start with `!omni` OR direct @mentions in CHAT mode.
 * In AGENT / SWARM mode, all messages in the channel are processed.
 *
 * Requires `DISCORD_BOT_TOKEN` and `DISCORD_DEFAULT_CHANNEL_ID` in Settings → Integrations.
 */
class DiscordPollingService : Service() {

    // ── Data model ─────────────────────────────────────────────────────────

    data class DiscordMessage(
        val channelId: String,
        val channelName: String,
        val sender: String,
        val content: String,
        val isFromBot: Boolean,
        val mode: OmniMode = OmniMode.CHAT,
        val timestamp: Long = System.currentTimeMillis()
    )

    // ── Companion / static state ───────────────────────────────────────────

    companion object {
        const val ACTION_STOP = "com.omnidev.workspace.DISCORD_POLL_STOP"

        @Volatile
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID_NOTIF = "omni_discord_polling"
        private const val NOTIFICATION_ID  = 5502
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_HISTORY_MSGS = 20
        private const val MAX_SESSIONS     = 50
        private const val MAX_MIRROR_MESSAGES = 200
        private const val AGENT_TIMEOUT_MS    = 8 * 60 * 1_000L
        private const val TYPING_REFRESH_MS   = 4_500L
        private const val DISCORD_API         = "https://discord.com/api/v10"

        /** Bot command prefix — can be changed in future. */
        private const val PREFIX = "!omni "

        private val _discordMessages = MutableStateFlow<List<DiscordMessage>>(emptyList())
        val discordMessages: StateFlow<List<DiscordMessage>> = _discordMessages.asStateFlow()

        private const val DISCORD_SYSTEM_PROMPT =
            "You are Omni — an autonomous AI assistant accessible via Discord. " +
            "You can answer questions, help with tasks, write code, and run agentic workflows. " +
            "Be direct, helpful, and concise. Respond in the same language the user writes in."

        private fun mirrorMessage(msg: DiscordMessage) {
            val current = _discordMessages.value
            _discordMessages.value = if (current.size >= MAX_MIRROR_MESSAGES)
                current.drop(1) + msg
            else
                current + msg
        }
    }

    // ── Instance state ─────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    /** Last processed message ID per channel (to avoid re-processing old messages). */
    private val lastMessageId: MutableMap<String, String> =
        Collections.synchronizedMap(mutableMapOf())

    /** Per-channel conversation history (LRU, max [MAX_SESSIONS]). */
    private val sessionHistory: MutableMap<String, MutableList<ChatMessage>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, MutableList<ChatMessage>>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<ChatMessage>>?) =
                    size > MAX_SESSIONS
            }
        )

    /** Current OmniMode per channelId — defaults to CHAT. */
    private val channelModes: MutableMap<String, OmniMode> =
        Collections.synchronizedMap(mutableMapOf())

    /** Display name per channelId. */
    private val channelNames: MutableMap<String, String> =
        Collections.synchronizedMap(mutableMapOf())

    // ── Lazy dependencies ──────────────────────────────────────────────────

    private val settingsRepository: SettingsRepository by lazy { SettingsRepository(applicationContext) }
    private val apiKeyRepository: ApiKeyRepository by lazy { ApiKeyRepository(applicationContext) }
    private val completionService: CompletionService by lazy { CompletionService(settingsRepository) }

    private val chatRepository: ChatRepository by lazy {
        val db = OmniDevDatabase.getInstance(applicationContext)
        ChatRepository(db.chatSessionDao(), db.chatMessageDao())
    }

    private val toolManager: CompositeToolManager by lazy {
        val db = OmniDevDatabase.getInstance(applicationContext)
        val memoryManager = MemoryManager(db.knowledgeDao(), db.sharedMemoryDao())
        // ── Agent Brain 2.0: engines are initialized in OmniDevApp ──
        val omniApp = com.omnidev.workspace.OmniDevApp.instance
        CompositeToolManager(
            fileToolManager = FileToolManager(),
            memoryManager = memoryManager,
            context = applicationContext,
            settingsRepository = settingsRepository,

            apiKeyRepository = apiKeyRepository,
            headlessBrowserManager = HeadlessBrowserManager(applicationContext),
            agentBrainTools = AgentBrainTools(
                reflexion = omniApp.reflexionEngine,
                episodic = omniApp.episodicMemoryStore
            ),
            rollbackTools = RollbackTools(omniApp.rollbackManager),
            repoContextTools = RepoContextTools(omniApp.repoIndexer, omniApp.repoContextEngine),
            buildDoctorTools = BuildDoctorTools(omniApp.buildDoctorPro)
        )
    }

    private val agentPipeline: AgentPipeline by lazy {
        AgentPipeline(
            toolManager = toolManager,
            mcpRegistry = com.omnidev.workspace.OmniDevApp.instance.mcpRegistry,
            completionProvider = completionService::invoke,
            streamingCompletionProvider = { req, onChunk -> completionService.stream(req, onChunk) },
            config = AgentConfig.THOROUGH,
            apiKeyRepository = apiKeyRepository,
            memoryManager = toolManager.memoryManager,
            smartLearningBridge = com.omnidev.workspace.OmniDevApp.instance.smartLearningBridge
        )
    }

    private val swarmOrchestrator: SwarmOrchestrator by lazy {
        SwarmOrchestrator(
            toolManager = toolManager,
            completionProvider = completionService::invoke,
            apiKeyRepository = apiKeyRepository,
            memoryManager = toolManager.memoryManager,
            smartLearningBridge = com.omnidev.workspace.OmniDevApp.instance.smartLearningBridge,
            streamingCompletionProvider = { req, onChunk -> completionService.stream(req, onChunk) }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("🔵 Omni Discord listener starting…"))
        isRunning = true
        startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        pollingJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Polling loop ───────────────────────────────────────────────────────

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            val token = settingsRepository.observeDiscordBotToken().first()
            if (token.isNullOrBlank()) {
                updateNotification("⚠️ Discord Bot Token not configured. Open Settings → Integrations.")
                stopSelf()
                return@launch
            }

            val defaultChannelId = settingsRepository.observeDiscordListenerChannelId().first()
            if (defaultChannelId.isNullOrBlank()) {
                updateNotification("⚠️ Discord Channel ID not configured. Open Settings → Integrations.")
                stopSelf()
                return@launch
            }

            // Resolve bot info
            val botUser = fetchBotUser(token)
            val botId   = botUser?.optString("id", "") ?: ""
            val botName = botUser?.optString("username", "OmniBot") ?: "OmniBot"
            updateNotification("✅ $botName listening — !mode_agent / !mode_swarm per channel")

            // Seed lastMessageId so we don't replay old messages
            seedLastMessageIds(token, defaultChannelId)

            while (isActive) {
                try {
                    pollChannels(token, defaultChannelId, botId, botName)
                } catch (e: Exception) {
                    // silently retry
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Initialize lastMessageId for the channel so we skip messages before service start. */
    private suspend fun seedLastMessageIds(token: String, channelId: String) = withContext(Dispatchers.IO) {
        if (lastMessageId.containsKey(channelId)) return@withContext
        val msgs = fetchMessages(token, channelId, limit = 1, after = null)
        if (msgs != null && msgs.length() > 0) {
            val latestId = msgs.getJSONObject(0).optString("id", "")
            if (latestId.isNotBlank()) lastMessageId[channelId] = latestId
        }
    }

    private suspend fun pollChannels(token: String, defaultChannelId: String, botId: String, botName: String) {
        // Poll the configured default channel + any channels that have been seen before
        val channelsToCheck = mutableSetOf(defaultChannelId)
        channelsToCheck.addAll(channelModes.keys)
        channelsToCheck.addAll(lastMessageId.keys)

        for (channelId in channelsToCheck) {
            val after = lastMessageId[channelId]
            val msgs = fetchMessages(token, channelId, limit = 10, after = after) ?: continue
            if (msgs.length() == 0) continue

            // Process from oldest to newest
            val msgList = (0 until msgs.length()).map { msgs.getJSONObject(it) }
                .sortedBy { it.optString("id") }

            for (msg in msgList) {
                val msgId   = msg.optString("id")
                val author  = msg.optJSONObject("author")
                val userId  = author?.optString("id", "") ?: ""
                if (userId == botId) {
                    // Update lastMessageId even for own messages so we don't re-process
                    if (msgId.isNotBlank()) lastMessageId[channelId] = msgId
                    continue
                }

                val username = author?.optString("username", "user") ?: "user"
                val content  = msg.optString("content", "").trim()

                // Update lastMessageId
                if (msgId.isNotBlank()) lastMessageId[channelId] = msgId

                // Determine mode for this channel
                val mode = channelModes[channelId] ?: OmniMode.CHAT

                // In CHAT mode: only respond to "!omni " prefix or @mentions
                val isCommand  = content.startsWith(PREFIX, ignoreCase = true)
                val isMention  = content.contains("<@$botId>") || content.contains("<@!$botId>")
                val isModeCmd  = content.startsWith("!")

                if (mode == OmniMode.CHAT && !isCommand && !isMention && !isModeCmd) continue

                // Handle control commands
                val cmdText = content.trimStart('/')
                val lc = content.lowercase().trim()

                when {
                    lc == "!mode_chat"  -> {
                        channelModes[channelId] = OmniMode.CHAT
                        sendDiscordMessage(token, channelId, "✅ Mode: **Chat** — standard conversation. Use `!omni your question` to get a reply.")
                        continue
                    }
                    lc == "!mode_agent" -> {
                        channelModes[channelId] = OmniMode.AGENT
                        sendDiscordMessage(token, channelId, "✅ Mode: **Agent** 🤖 — full autonomous agent with all tools.")
                        continue
                    }
                    lc == "!mode_swarm" -> {
                        channelModes[channelId] = OmniMode.SWARM
                        sendDiscordMessage(token, channelId, "✅ Mode: **Swarm** 🐝 — multi-agent team execution.")
                        continue
                    }
                    lc == "!clear" -> {
                        sessionHistory.remove(channelId)
                        sendDiscordMessage(token, channelId, "🧹 Conversation context cleared.")
                        continue
                    }
                    lc == "!status" -> {
                        val currentMode = channelModes[channelId] ?: OmniMode.CHAT
                        val histSize    = sessionHistory[channelId]?.size ?: 0
                        sendDiscordMessage(token, channelId,
                            "📊 **Status**\n" +
                            "Mode: **${currentMode.name}**\n" +
                            "Context: $histSize messages\n" +
                            "Tools: ${toolManager.getToolDefinitions().size}")
                        continue
                    }
                    lc == "!help" -> {
                        sendDiscordMessage(token, channelId, buildHelpText())
                        continue
                    }
                    // Unknown ! command — skip
                    lc.startsWith("!") && !isCommand && !isMention -> continue
                }

                // Extract the actual query
                val query = when {
                    isCommand -> content.substring(PREFIX.length).trim()
                    isMention -> content.replace("<@$botId>", "").replace("<@!$botId>", "").trim()
                    else      -> content
                }
                if (query.isBlank()) continue

                // Mirror to in-app
                val channelName = channelNames.getOrPut(channelId) { "discord-$channelId" }
                mirrorMessage(DiscordMessage(channelId, channelName, username, query, isFromBot = false, mode = mode))

                // Persist user message to Room
                val sessionId = getOrCreateRoomSession(channelId, channelName)

                // Process the message
                serviceScope.launch {
                    sendTypingIndicator(token, channelId)
                    val reply = withTimeoutOrNull(AGENT_TIMEOUT_MS) {
                        withTypingIndicator(token, channelId) {
                            processMessage(query, channelId, mode, username)
                        }
                    } ?: "⏱ Agent timed out after 8 minutes. Try simplifying the request."

                    // Persist user + bot messages to Room
                    if (sessionId != null) {
                        chatRepository.saveMessage(sessionId,
                            com.omnidev.workspace.data.model.ChatMessage(
                                role = MessageRole.USER, content = query))
                        chatRepository.saveMessage(sessionId,
                            com.omnidev.workspace.data.model.ChatMessage(
                                role = MessageRole.ASSISTANT, content = reply))
                    }

                    // Mirror bot reply
                    mirrorMessage(DiscordMessage(channelId, channelName, botName, reply, isFromBot = true, mode = mode))

                    // Send to Discord (split if >2000 chars)
                    sendLongMessage(token, channelId, reply)
                }
            }
        }
    }

    private suspend fun processMessage(
        text: String,
        channelId: String,
        mode: OmniMode,
        sender: String
    ): String {
        val history = sessionHistory.getOrPut(channelId) { mutableListOf() }

        return when (mode) {
            OmniMode.CHAT, OmniMode.AUTO -> {
                history.add(ChatMessage(role = MessageRole.USER, content = "$sender: $text"))
                val modelId = settingsRepository.observeModelIdForRole(ModelRole.CHAT).first()
                    ?: ModelRegistry.getDefaultModelForRole(ModelRole.CHAT).id
                val request = com.omnidev.workspace.data.model.CompletionRequest(
                    modelId = modelId,
                    systemPrompt = DISCORD_SYSTEM_PROMPT,
                    messages = history.takeLast(MAX_HISTORY_MSGS),
                    temperature = 0.7
                )
                val reply = runCatching {
                    completionService.invoke(request).content ?: "No response."
                }.getOrElse { e -> "Error: ${e.message}" }
                history.add(ChatMessage(role = MessageRole.ASSISTANT, content = reply))
                trimHistory(history)
                reply
            }
            OmniMode.AGENT -> {
                val modelId = settingsRepository.observeModelIdForRole(ModelRole.AGENT).first()
                    ?: ModelRegistry.getDefaultModelForRole(ModelRole.AGENT).id
                val replyBuilder = StringBuilder()
                runCatching {
                    agentPipeline.execute(
                        userMessage = text,
                        conversationHistory = history.takeLast(MAX_HISTORY_MSGS),
                        modelId = modelId,
                        scopePath = "/"
                    ).collect { event ->
                        when (event) {
                            is com.omnidev.workspace.domain.engine.AgentEvent.FinalAnswer ->
                                replyBuilder.append(event.content)
                            is com.omnidev.workspace.domain.engine.AgentEvent.StreamChunk ->
                                replyBuilder.append(event.delta)
                            is com.omnidev.workspace.domain.engine.AgentEvent.Error ->
                                replyBuilder.append("\n⚠️ ${event.message}")
                            else -> Unit
                        }
                    }
                }.getOrElse { e -> replyBuilder.append("⚠️ Agent error: ${e.message}") }
                history.add(ChatMessage(role = MessageRole.USER, content = "$sender: $text"))
                history.add(ChatMessage(role = MessageRole.ASSISTANT, content = replyBuilder.toString()))
                trimHistory(history)
                replyBuilder.toString().trim().ifBlank { "✅ Task completed." }
            }
            OmniMode.SWARM -> {
                val orchestratorModelId = settingsRepository.observeModelIdForRole(ModelRole.SWARM_ORCHESTRATOR).first()
                    ?: ModelRegistry.getDefaultModelForRole(ModelRole.SWARM_ORCHESTRATOR).id
                val workerModelId = settingsRepository.observeModelIdForRole(ModelRole.SWARM_WORKER).first()
                    ?: orchestratorModelId
                val replyBuilder = StringBuilder()
                runCatching {
                    swarmOrchestrator.execute(
                        userMessage = text,
                        orchestratorModelId = orchestratorModelId,
                        workerModelId = workerModelId,
                        scopePath = "/"
                    ).collect { event ->
                        when (event) {
                            is com.omnidev.workspace.domain.engine.SwarmEvent.Completed ->
                                replyBuilder.append(event.summary)
                            is com.omnidev.workspace.domain.engine.SwarmEvent.Error ->
                                replyBuilder.append("\n⚠️ ${event.message}")
                            else -> Unit
                        }
                    }
                }.getOrElse { e -> replyBuilder.append("⚠️ Swarm error: ${e.message}") }
                history.add(ChatMessage(role = MessageRole.USER, content = "$sender: $text"))
                history.add(ChatMessage(role = MessageRole.ASSISTANT, content = replyBuilder.toString()))
                trimHistory(history)
                replyBuilder.toString().trim().ifBlank { "✅ Swarm task completed." }
            }
        }
    }

    private fun trimHistory(history: MutableList<ChatMessage>) {
        while (history.size > MAX_HISTORY_MSGS * 2) {
            history.removeAt(0)
            if (history.isNotEmpty()) history.removeAt(0)
        }
    }

    // ── Room DB ────────────────────────────────────────────────────────────

    private suspend fun getOrCreateRoomSession(channelId: String, channelName: String): Long? {
        return try {
            chatRepository.findOrCreateDiscordSession(channelId, channelName)
        } catch (_: Exception) { null }
    }

    // ── Discord API helpers ────────────────────────────────────────────────

    private fun fetchBotUser(token: String): JSONObject? = try {
        val conn = (URL("https://discord.com/api/v10/users/@me").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bot $token")
        }
        if (conn.responseCode in 200..299)
            JSONObject(conn.inputStream.bufferedReader().readText())
        else null
    } catch (_: Exception) { null }

    private fun fetchMessages(token: String, channelId: String, limit: Int, after: String?): JSONArray? = try {
        var url = "$DISCORD_API/channels/$channelId/messages?limit=$limit"
        if (after != null) url += "&after=$after"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bot $token")
        }
        if (conn.responseCode in 200..299)
            JSONArray(conn.inputStream.bufferedReader().readText())
        else null
    } catch (_: Exception) { null }

    private fun sendDiscordMessage(token: String, channelId: String, text: String): Boolean = try {
        val body = JSONObject().put("content", text).toString().toByteArray()
        val conn = (URL("$DISCORD_API/channels/$channelId/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bot $token")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            outputStream.write(body)
        }
        conn.responseCode in 200..299
    } catch (_: Exception) { false }

    private fun sendTypingIndicator(token: String, channelId: String) = try {
        val conn = (URL("$DISCORD_API/channels/$channelId/typing").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bot $token")
            setRequestProperty("Content-Length", "0")
            doOutput = true
        }
        conn.responseCode
    } catch (_: Exception) { 0 }

    private suspend fun <T> withTypingIndicator(token: String, channelId: String, block: suspend () -> T): T {
        val indicatorJob = serviceScope.launch {
            while (isActive) {
                sendTypingIndicator(token, channelId)
                delay(TYPING_REFRESH_MS)
            }
        }
        return try { block() } finally { indicatorJob.cancel() }
    }

    private fun sendLongMessage(token: String, channelId: String, text: String) {
        val maxLen = 2000
        if (text.length <= maxLen) {
            sendDiscordMessage(token, channelId, text)
            return
        }
        var offset = 0
        var part = 1
        while (offset < text.length) {
            val chunk = text.substring(offset, minOf(offset + maxLen, text.length))
            sendDiscordMessage(token, channelId, if (part == 1) chunk else "…$chunk")
            offset += maxLen
            part++
        }
    }

    private fun buildHelpText(): String = buildString {
        appendLine("🤖 **Omni Discord Bot**")
        appendLine()
        appendLine("**Modes:**")
        appendLine("`!mode_chat`  — standard conversation (default)")
        appendLine("`!mode_agent` — autonomous agent with all tools")
        appendLine("`!mode_swarm` — multi-agent team")
        appendLine()
        appendLine("**Commands:**")
        appendLine("`!omni <message>` — send a message to the bot")
        appendLine("`!status`         — show current mode and statistics")
        appendLine("`!clear`          — clear conversation context")
        appendLine("`!help`           — show this list")
        appendLine()
        appendLine("In AGENT/SWARM mode: all messages are processed automatically.")
    }

    // ── Notification helpers ───────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, DiscordPollingService::class.java).apply { action = ACTION_STOP }
        val stopPending = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_NOTIF)
            .setContentTitle("Omni Discord Bot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_NOTIF,
                "Discord Bot Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Omni Discord bot message polling" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
