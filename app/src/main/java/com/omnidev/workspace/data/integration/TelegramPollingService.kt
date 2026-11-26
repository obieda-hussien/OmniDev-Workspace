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
import com.omnidev.workspace.BuildConfig
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.data.network.CompletionService
import com.omnidev.workspace.data.network.PartialCompletionException
import com.omnidev.workspace.data.network.RateLimitException
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.ChatRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.tools.CompositeToolManager
import com.omnidev.workspace.data.tools.DiscordBotTool
import com.omnidev.workspace.data.tools.FileToolManager
import com.omnidev.workspace.data.tools.GodEyeProfilerTool
import com.omnidev.workspace.data.tools.HeadlessBrowserManager
import com.omnidev.workspace.data.tools.MemoryManager
import com.omnidev.workspace.data.tools.NotionPublisherTool
import com.omnidev.workspace.data.tools.ShizukuCommandTool
import com.omnidev.workspace.data.tools.VectorMemoryManager
import com.omnidev.workspace.domain.engine.AgentConfig
import com.omnidev.workspace.domain.engine.AgentEvent
import com.omnidev.workspace.domain.engine.AgentPipeline
import com.omnidev.workspace.domain.engine.OmniMode
import com.omnidev.workspace.domain.engine.SwarmOrchestrator
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Foreground service that polls the Telegram Bot API for incoming messages and
 * responds to them using the AI — inspired by OpenClaw's Telegram channel integration.
 *
 * ## Modes (per-chat)
 * Each Telegram chat can be placed in one of three modes via `/mode_chat`, `/mode_agent`,
 * or `/mode_swarm`.  The mode determines which engine processes the message:
 * - **CHAT** (default) — Direct CompletionService call, no tool access.
 * - **AGENT** — Full ReAct loop with all tools (AgentPipeline).
 * - **SWARM** — Multi-agent orchestration (SwarmOrchestrator → workers).
 *
 * ## Conversation mirroring
 * Authorized Telegram conversations are mirrored to [telegramMessages] — a static [StateFlow] that
 * any screen in the app can collect to display an in-app Telegram conversation view.
 *
 * ## Tool commands
 * On startup the service calls `setMyCommands` to register the supported commands.
 *
 * Requires `TELEGRAM_BOT_TOKEN` to be configured in Settings → Integrations.
 */
class TelegramPollingService : Service() {

    // ── Data model ─────────────────────────────────────────────────────────

    /** A single Telegram message mirrored to the in-app conversation view. */
    data class TelegramChatMessage(
        val chatId: Long,
        val chatTitle: String,
        val sender: String,
        val content: String,
        val isFromBot: Boolean,
        val mode: OmniMode = OmniMode.CHAT,
        val timestamp: Long = System.currentTimeMillis()
    )

    // ── Companion / static state ───────────────────────────────────────────

    companion object {
        const val ACTION_STOP = "com.omnidev.workspace.TELEGRAM_POLL_STOP"

        /** Expose running state so UI can show a connected indicator. */
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID = "omni_telegram_polling"
        private const val NOTIFICATION_ID = 5501
        private const val POLL_INTERVAL_MS = 1_000L
        private const val LONG_POLL_TIMEOUT_SEC = 25
        private const val MAX_HISTORY_MSGS = 20
        private const val MAX_SESSIONS = 50

        /** Rolling in-app mirror of Telegram conversations (max 200 messages). */
        private const val MAX_MIRROR_MESSAGES = 200

        /** Maximum time allowed for a single Agent/Swarm response (8 min). */
        private const val AGENT_TIMEOUT_MS = 8 * 60 * 1_000L

        /** Timeout duration in minutes, for user-facing messages. */
        private const val AGENT_TIMEOUT_MINUTES = AGENT_TIMEOUT_MS / 60_000L

        /** Interval to re-send the typing indicator during long operations. */
        private const val TYPING_REFRESH_MS = 4_500L

        private val _telegramMessages = MutableStateFlow<List<TelegramChatMessage>>(emptyList())

        /**
         * Collect this in any Composable / ViewModel to see all Telegram conversations
         * in real-time, as if you were inside the app.
         */
        val telegramMessages: StateFlow<List<TelegramChatMessage>> = _telegramMessages.asStateFlow()

        /** System prompt for CHAT mode on Telegram. */
        private const val TELEGRAM_SYSTEM_PROMPT =
            "You are Omni in Telegram Chat mode. You cannot run tools, inspect the phone, change modes, or manage sessions. " +
            "Use the current conversation and any supplied profile for memory. A Telegram handle is not evidence of a remembered name. Never invent device data or report tool execution. Answer naturally without a reasoning transcript or execution report. " +
            "Be direct, helpful, and concise. Respond in the same language the user writes in."

        /** Append a message to the in-app mirror, capping at [MAX_MIRROR_MESSAGES]. */
        private fun mirrorMessage(msg: TelegramChatMessage) {
            val current = _telegramMessages.value
            _telegramMessages.value = if (current.size >= MAX_MIRROR_MESSAGES)
                current.drop(1) + msg
            else
                current + msg
        }
    }

    // ── Instance state ─────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    @Volatile private var nextOffset: Long = 0L

    /** Per-chat conversation history (LRU, max [MAX_SESSIONS]). */
    private val sessionHistory: MutableMap<Long, MutableList<ChatMessage>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<Long, MutableList<ChatMessage>>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<Long, MutableList<ChatMessage>>?
                ) = size > MAX_SESSIONS
            }
        )

    /** Named sessions per chatId — list of (name, messages) for /sessions listing. */
    private val namedSessions: MutableMap<Long, MutableList<Pair<String, Int>>> =
        Collections.synchronizedMap(mutableMapOf())

    /** Current session name per chatId. */
    private val sessionNameMap: MutableMap<Long, String> =
        Collections.synchronizedMap(mutableMapOf())

    /** Auto-incrementing session counter per chatId. */
    private val sessionCounters: MutableMap<Long, Int> =
        Collections.synchronizedMap(mutableMapOf())

    /** Current OmniMode per chatId — defaults to CHAT. */
    private val chatModes: MutableMap<Long, OmniMode> =
        Collections.synchronizedMap(mutableMapOf())

    /** Display name per chatId (for mirroring). */
    private val chatTitles: MutableMap<Long, String> =
        Collections.synchronizedMap(mutableMapOf())

    // ── Lazy dependencies ──────────────────────────────────────────────────

    private val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(applicationContext)
    }
    private val ownerLinkStore by lazy { TelegramOwnerLinkStore(applicationContext) }
    private val offsetPrefs by lazy { getSharedPreferences("telegram_poll_cursor_v1", MODE_PRIVATE) }

    private fun tokenFingerprint(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun restoreOffset(token: String) {
        nextOffset = if (offsetPrefs.getString("token_hash", null) == tokenFingerprint(token))
            offsetPrefs.getLong("next_offset", 0L) else 0L
    }

    private fun advanceOffset(token: String, updateId: Long) {
        if (updateId < nextOffset) return
        nextOffset = updateId + 1
        if (!offsetPrefs.edit().putString("token_hash", tokenFingerprint(token))
                .putLong("next_offset", nextOffset).commit()) {
            android.util.Log.w("TelegramPolling", "Unable to persist Telegram update offset")
        }
    }
    private val apiKeyRepository: ApiKeyRepository by lazy {
        ApiKeyRepository(applicationContext)
    }
    private val completionService: CompletionService by lazy { CompletionService(settingsRepository) }

    private val chatRepository: ChatRepository by lazy {
        val db = OmniDevDatabase.getInstance(applicationContext)
        ChatRepository(db.chatSessionDao(), db.chatMessageDao())
    }

    private val toolManager: CompositeToolManager by lazy {
        val db = OmniDevDatabase.getInstance(applicationContext)
        val memoryManager = MemoryManager(db.knowledgeDao())
        // ── Agent Brain 2.0: references to engines initialized in OmniDevApp ──
        val omniApp = com.omnidev.workspace.OmniDevApp.instance
        CompositeToolManager(
            fileToolManager = FileToolManager(),
            memoryManager = memoryManager,
            context = applicationContext,
            settingsRepository = settingsRepository,
            godEyeProfilerTool = GodEyeProfilerTool(applicationContext, ShizukuCommandTool),

            notionPublisherTool = NotionPublisherTool(settingsRepository),
            vectorMemoryManager = VectorMemoryManager(db.knowledgeDao()),
            apiKeyRepository = apiKeyRepository,
            headlessBrowserManager = HeadlessBrowserManager(applicationContext),
            agentBrainTools = com.omnidev.workspace.data.tools.AgentBrainTools(
                reflexion = omniApp.reflexionEngine,
                episodic = omniApp.episodicMemoryStore
            ),
            rollbackTools = com.omnidev.workspace.data.tools.RollbackTools(omniApp.rollbackManager),
            repoContextTools = com.omnidev.workspace.data.tools.RepoContextTools(omniApp.repoIndexer, omniApp.repoContextEngine),
            buildDoctorTools = com.omnidev.workspace.data.tools.BuildDoctorTools(omniApp.buildDoctorPro)
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
            streamingCompletionProvider = { req, onChunk -> completionService.stream(req, onChunk) },
            teamStateStore = com.omnidev.workspace.data.repository.roomTeamStateStore(
                com.omnidev.workspace.data.db.OmniDevDatabase.getInstance(applicationContext))
        )
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

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
        startForeground(NOTIFICATION_ID, buildNotification("🔵 Omni Telegram listener starting…"))
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
            val token = settingsRepository.observeTelegramBotToken().first()
            if (token.isNullOrBlank()) {
                updateNotification("⚠️ Telegram Bot Token not configured. Open Settings → Integrations.")
                stopSelf()
                return@launch
            }
            restoreOffset(token)

            val botUsername = fetchBotUsername(token) ?: "OmniBot"
            updateNotification("✅ $botUsername listening — use /mode_agent or /mode_swarm to upgrade a chat")

            // Telegram commands are hints, never a substitute for authorization.
            launch { registerBotCommands(token) }

            while (isActive) {
                try {
                    if (settingsRepository.observeTelegramBotToken().first() != token) {
                        stopSelf()
                        return@launch
                    }
                    val updates = fetchUpdates(token, nextOffset)
                    for (update in updates) {
                        val updateId = update.optLong("update_id")
                        if (updateId < nextOffset) continue
                        try {
                        // Edits must never re-execute an earlier command or tool call.
                        val msg = update.optJSONObject("message") ?: continue
                        val chatObj = msg.optJSONObject("chat") ?: continue
                        val chatId = chatObj.optLong("id")
                        val messageId = msg.optLong("message_id")
                        val fromObj = msg.optJSONObject("from") ?: continue
                        if (fromObj.optBoolean("is_bot")) continue
                        val userId = fromObj.optLong("id")
                        val chatType = chatObj.optString("type")
                        val rawText = msg.optString("text", "").trim()
                        val command = TelegramConversationPolicy.parse(rawText)
                        if (command?.target?.equals(botUsername.removePrefix("@"), ignoreCase = true) == false) continue
                        if (command?.name == "pair") {
                            if (TelegramOwnerLinkStore.isPrivateOwnerChat(chatId, userId, chatType)) {
                                val code = command.argument
                                val linked = ownerLinkStore.pair(token, chatId, userId, chatType, code)
                                sendReply(token, chatId, messageId, if (linked)
                                    "✅ This private chat is linked to OmniDev. Use /help to get started."
                                else "⚠️ Pairing code is invalid or expired. Generate a new code in OmniDev settings.")
                            }
                            continue
                        }
                        if (!ownerLinkStore.isAuthorized(token, chatId, userId, chatType)) continue

                        // Extract text or a human-readable description of media content
                        val text: String = when {
                            msg.has("text") -> msg.optString("text", "").trim()
                            msg.has("photo") -> {
                                val photoArr = msg.optJSONArray("photo")
                                val best = photoArr?.optJSONObject((photoArr.length() - 1).coerceAtLeast(0))
                                val fid = best?.optString("file_id", "") ?: ""
                                val cap = msg.optString("caption", "")
                                "[📷 Photo${if (cap.isNotBlank()) ": $cap" else ""}] file_id=$fid"
                            }
                            msg.has("document") -> {
                                val doc = msg.optJSONObject("document")
                                val name = doc?.optString("file_name", "document") ?: "document"
                                val fid = doc?.optString("file_id", "") ?: ""
                                val cap = msg.optString("caption", "")
                                "[📄 File: $name${if (cap.isNotBlank()) " ($cap)" else ""}] file_id=$fid"
                            }
                            msg.has("location") -> {
                                val loc = msg.optJSONObject("location")
                                val lat = loc?.optDouble("latitude") ?: 0.0
                                val lon = loc?.optDouble("longitude") ?: 0.0
                                val isLive = loc?.has("live_period") == true
                                "[${if (isLive) "📍 Live location" else "📍 Location"}: lat=$lat, lon=$lon]"
                            }
                            msg.has("contact") -> {
                                val c = msg.optJSONObject("contact")
                                val name = "${c?.optString("first_name", "")} ${c?.optString("last_name", "")}".trim()
                                val phone = c?.optString("phone_number", "") ?: ""
                                "[👤 Contact: $name, phone: $phone]"
                            }
                            msg.has("sticker") -> {
                                val e = msg.optJSONObject("sticker")?.optString("emoji", "") ?: ""
                                "[🎭 Sticker $e]"
                            }
                            msg.has("voice") -> {
                                val fid = msg.optJSONObject("voice")?.optString("file_id", "") ?: ""
                                "[🎤 Voice message] file_id=$fid"
                            }
                            msg.has("video") -> {
                                val fid = msg.optJSONObject("video")?.optString("file_id", "") ?: ""
                                val cap = msg.optString("caption", "")
                                "[🎥 Video${if (cap.isNotBlank()) ": $cap" else ""}] file_id=$fid"
                            }
                            msg.has("audio") -> {
                                val audio = msg.optJSONObject("audio")
                                val fid = audio?.optString("file_id", "") ?: ""
                                val title = audio?.optString("title", "") ?: ""
                                "[🎵 Audio${if (title.isNotBlank()) ": $title" else ""}] file_id=$fid"
                            }
                            msg.has("video_note") -> {
                                val fid = msg.optJSONObject("video_note")?.optString("file_id", "") ?: ""
                                "[📹 Video note] file_id=$fid"
                            }
                            else -> ""
                        }
                        if (text.isBlank()) continue

                        val senderName = fromObj.let {
                            val fn = it.optString("first_name", "")
                            val un = it.optString("username", "")
                            if (un.isNotBlank()) "@$un" else fn
                        }.ifBlank { "User" }

                        // Cache chat display name for mirroring
                        val chatTitle = chatObj.optString("title")
                            .ifBlank { chatObj.optString("first_name") }
                            .ifBlank { chatId.toString() }
                        chatTitles[chatId] = chatTitle

                        // Mirror user message to app
                        mirrorMessage(
                            TelegramChatMessage(
                                chatId = chatId,
                                chatTitle = chatTitle,
                                sender = senderName,
                                content = text,
                                isFromBot = false,
                                mode = chatModes[chatId] ?: OmniMode.CHAT
                            )
                        )

                        handleIncoming(token, chatId, messageId, senderName, text, command)
                        } finally {
                            // Commit after handling, including ignored updates, to prevent replays on restart.
                            advanceOffset(token, updateId)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("TelegramPolling", "Polling failed: ${e.message}")
                    delay(5_000)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    // ── Message handling ───────────────────────────────────────────────────

    private suspend fun handleIncoming(
        token: String,
        chatId: Long,
        messageId: Long,
        senderName: String,
        text: String,
        command: TelegramConversationPolicy.Command?
    ) {
        val currentMode = chatModes[chatId] ?: OmniMode.CHAT
        val chatTitle = chatTitles[chatId] ?: chatId.toString()

        when (command?.name) {
            "mode" -> {
                sendReply(token, chatId, messageId,
                    "Current mode: ${currentMode.label}\nUse /mode chat, /mode agent, or /mode swarm.")
                return
            }
            "start" -> {
                sendReply(token, chatId, messageId,
                    "👋 Welcome! I'm Omni — your AI assistant on Telegram.\n\n" +
                    "Current mode: ${currentMode.label}\n\n" +
                    "Send any question or task and I'll respond right away 🤖\n\n" +
                    "/help — command list\n/clear — clear the conversation\n" +
                    "/mode_chat — standard chat mode\n" +
                    "/mode_agent — Agent mode with permitted tools\n" +
                    "/mode_swarm — multi-agent Swarm mode\n" +
                    "/status — current session status")
                return
            }

            "clear", "reset" -> {
                // Archive before clearing
                val oldHistory = sessionHistory[chatId]
                val oldName = sessionNameMap[chatId] ?: "Session ${sessionCounters.getOrDefault(chatId, 1)}"
                if (!oldHistory.isNullOrEmpty()) {
                    val sessionList = namedSessions.getOrPut(chatId) { mutableListOf() }
                    sessionList.add(oldName to oldHistory.size)
                }
                sessionHistory.remove(chatId)
                sendReply(token, chatId, messageId,
                    "✅ Conversation history cleared.\nUse /sessions to view previous sessions.")
                return
            }

            "help" -> {
                sendReply(token, chatId, messageId,
                    "Omni — available commands:\n\n" +
                    "🎛️ Modes:\n/mode — show current mode\n" +
                    "/mode_chat — standard conversation\n" +
                    "/mode_agent — agent with permitted tools\n" +
                    "/mode_swarm — multi-agent team\n\n" +
                    "📋 Session management:\n" +
                    "/status — show mode and statistics\n" +
                    "/new_session [name] — start a new session and save the current one\n" +
                    "/sessions — show saved sessions\n" +
                    "/clear — clear the current conversation context\n\n" +
                    "Agent and Swarm modes use the Target Context configured in OmniDev settings.")
                return
            }

            "mode_chat" -> {
                chatModes[chatId] = OmniMode.CHAT
                sessionHistory.remove(chatId)
                sendReply(token, chatId, messageId,
                    "✅ Switched to Chat mode 💬\nDirect responses without tools.")
                return
            }

            "mode_agent" -> {
                if (!BuildConfig.ALLOW_AGENT) {
                    sendReply(token, chatId, messageId, "Agent mode is unavailable in this app edition.")
                    return
                }
                if (configuredScope() == null) {
                    sendReply(token, chatId, messageId,
                        "⚠️ Choose a specific workspace folder in OmniDev Settings → Target Context, then send /mode agent again.")
                    return
                }
                chatModes[chatId] = OmniMode.AGENT
                sessionHistory.remove(chatId)
                sendReply(token, chatId, messageId,
                    "🤖 Switched to Agent mode ⚡\n" +
                    "The agent can use permitted tools within the configured workspace.\n" +
                    "Replies can take longer while tools are being executed.")
                return
            }

            "mode_swarm" -> {
                if (!BuildConfig.ALLOW_TEAM) {
                    sendReply(token, chatId, messageId, "Swarm mode is unavailable in this app edition.")
                    return
                }
                if (configuredScope() == null) {
                    sendReply(token, chatId, messageId,
                        "⚠️ Choose a specific workspace folder in OmniDev Settings → Target Context, then send /mode swarm again.")
                    return
                }
                chatModes[chatId] = OmniMode.SWARM
                sessionHistory.remove(chatId)
                sendReply(token, chatId, messageId,
                    "🐝 Switched to Swarm mode 🌐\n" +
                    "The orchestrator divides the task across a team of specialized agents.\n" +
                    "Best for complex multi-step tasks.")
                return
            }

            "status" -> {
                val history = sessionHistory[chatId]
                val msgCount = history?.size ?: 0
                val sesName = sessionNameMap[chatId] ?: "Default session"
                sendReply(token, chatId, messageId,
                    "📊 Session status:\n\n" +
                    "🎛️ Mode: ${(chatModes[chatId] ?: OmniMode.CHAT).label}\n" +
                    "📝 Session name: $sesName\n" +
                    "💬 Messages in context: $msgCount\n" +
                    "🤖 Bot running: ${if (isRunning) "✅" else "❌"}\n\n" +
                    "/new_session [name] — start a new session\n" +
                    "/sessions — show previous session summaries")
                return
            }

            "new_session" -> {
                // Archive current session
                val oldHistory = sessionHistory[chatId]
                val oldName = sessionNameMap[chatId] ?: "Session ${sessionCounters.getOrDefault(chatId, 1)}"
                if (!oldHistory.isNullOrEmpty()) {
                    val sessionList = namedSessions.getOrPut(chatId) { mutableListOf() }
                    sessionList.add(oldName to oldHistory.size)
                }
                // Start new session
                val counter = (sessionCounters.getOrDefault(chatId, 1)) + 1
                sessionCounters[chatId] = counter
                val newName = command?.argument.orEmpty().ifBlank { "Session $counter" }.take(100)
                sessionHistory.remove(chatId)
                sessionNameMap[chatId] = newName
                sendReply(token, chatId, messageId,
                    "🆕 Started a new session: $newName\n" +
                    "Conversation context was cleared — start fresh!")
                return
            }

            "sessions" -> {
                val list = namedSessions[chatId]
                if (list.isNullOrEmpty()) {
                    sendReply(token, chatId, messageId,
                        "📋 No saved sessions yet.\n\n" +
                        "Use /new_session [name] to start a session.")
                } else {
                    val sb = StringBuilder("📋 Previous sessions:\n\n")
                    list.takeLast(10).forEachIndexed { i, (name, count) ->
                        sb.append("${i + 1}. $name — $count messages\n")
                    }
                    val currentName = sessionNameMap[chatId] ?: "Current session"
                    val currentCount = sessionHistory[chatId]?.size ?: 0
                    sb.append("\n🟢 Current: $currentName ($currentCount messages)")
                    sendReply(token, chatId, messageId, sb.toString())
                }
                return
            }
            null -> Unit
            else -> {
                sendReply(token, chatId, messageId, "Unknown command. Use /help for commands.")
                return
            }
        }

        if (currentMode == OmniMode.CHAT && TelegramConversationPolicy.needsDeviceEvidence(text)) {
            sendReply(token, chatId, messageId,
                "Chat mode cannot inspect this phone. Configure a workspace in OmniDev, then use /mode agent.")
            return
        }

        // ── Route to the right engine based on mode ──────────────────────
        sendTypingAction(token, chatId)

        // Persist user message to the app's conversation database
        val dbSessionId = try {
            chatRepository.findOrCreateTelegramSession(chatId, chatTitle)
        } catch (e: Exception) {
            android.util.Log.w("TelegramPolling", "Failed to find/create DB session for chat $chatId: ${e.message}")
            -1L
        }
        if (dbSessionId > 0) {
            try {
                chatRepository.saveMessage(dbSessionId,
                    ChatMessage(role = MessageRole.USER, content = "$senderName: $text"))
            } catch (e: Exception) {
                android.util.Log.w("TelegramPolling", "Failed to save user message to DB (session $dbSessionId): ${e.message}")
            }
        }

        val reply = TelegramConversationPolicy.visibleReply(when (chatModes[chatId] ?: OmniMode.CHAT) {
            OmniMode.AGENT -> handleAgentMode(token, chatId, senderName, text)
            OmniMode.SWARM -> handleSwarmMode(token, chatId, text)
            else -> handleChatMode(chatId, senderName, text)
        })

        if (reply.isNotBlank()) {
            // Mirror bot reply to app
            mirrorMessage(
                TelegramChatMessage(
                    chatId = chatId,
                    chatTitle = chatTitle,
                    sender = "Omni",
                    content = reply,
                    isFromBot = true,
                    mode = chatModes[chatId] ?: OmniMode.CHAT
                )
            )
            // Persist bot reply to the app's conversation database
            if (dbSessionId > 0) {
                try {
                    chatRepository.saveMessage(dbSessionId,
                        ChatMessage(role = MessageRole.ASSISTANT, content = reply))
                } catch (e: Exception) {
                    android.util.Log.w("TelegramPolling", "Failed to save bot reply to DB (session $dbSessionId): ${e.message}")
                }
            }
            sendReply(token, chatId, messageId, reply)
        }
    }

    // ── Chat mode (direct completion) ──────────────────────────────────────

    private suspend fun handleChatMode(
        chatId: Long,
        senderName: String,
        text: String
    ): String {
        val history = sessionHistory.getOrPut(chatId) { mutableListOf() }
        while (history.size > MAX_HISTORY_MSGS) {
            history.removeAt(0)
            if (history.isNotEmpty()) history.removeAt(0)
        }
        history.add(ChatMessage(role = MessageRole.USER, content = "$senderName: $text"))

        val accumulated = StringBuilder()
        return try {
            val modelId = settingsRepository.observeModelIdForRole(ModelRole.CHAT).first()
            val model = ModelRegistry.findModelById(modelId)
            val apiKey = model?.let { apiKeyRepository.getApiKey(it.provider) }
            val persona = settingsRepository.observeUserPromptContext().first()
            val systemPrompt = if (!persona.isNullOrBlank())
                "$TELEGRAM_SYSTEM_PROMPT\n\n## User Context\n$persona"
            else TELEGRAM_SYSTEM_PROMPT

            val request = CompletionRequest(
                modelId = modelId,
                messages = history.toList(),
                systemPrompt = systemPrompt,
                maxTokens = model?.maxOutputTokens ?: 8192,
                temperature = 0.7,
                apiKey = apiKey
            )
            val response = completionService.stream(request) { chunk -> accumulated.append(chunk) }
            val reply = TelegramConversationPolicy.visibleReply(
                response.content.ifBlank { accumulated.toString() }, chatHasNoTools = true
            )
            if (reply.isNotBlank()) {
                history.add(ChatMessage(role = MessageRole.ASSISTANT, content = reply))
            }
            reply
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (partial: PartialCompletionException) {
            val visible = TelegramConversationPolicy.visibleReply(accumulated.toString(), chatHasNoTools = true)
            if (visible.isBlank()) "The model response was interrupted before a usable reply."
            else "$visible\n\nThe model response was interrupted."
        } catch (limited: RateLimitException) {
            "The selected model provider rate-limited this request. ${limited.message.orEmpty()}"
        } catch (_: Exception) {
            "Could not get a reply from the selected model provider."
        }
    }

    // ── Agent mode (ReAct loop with tools) ────────────────────────────────

    private suspend fun configuredScope(): String? = settingsRepository.observeTargetContext().first()
        ?.takeIf { it.isNotBlank() && it != "/" }

    /**
     * Runs [block] while periodically refreshing the Telegram typing indicator.
     * Cancels the typing coroutine when done.
     */
    private suspend fun <T> withTypingIndicator(token: String, chatId: Long, block: suspend () -> T): T {
        val typingJob = serviceScope.launch {
            while (isActive) {
                delay(TYPING_REFRESH_MS)
                sendTypingAction(token, chatId)
            }
        }
        return try {
            block()
        } finally {
            typingJob.cancel()
        }
    }

    private suspend fun handleAgentMode(
        token: String,
        chatId: Long,
        senderName: String,
        text: String
    ): String {
        return try {
            val scope = configuredScope() ?: return "⚠️ Set a specific Target Context in OmniDev settings."
            val modelId = settingsRepository.observeModelIdForRole(ModelRole.AGENT).first()
            val persona = settingsRepository.observeUserPromptContext().first()
            val history = sessionHistory.getOrPut(chatId) { mutableListOf() }

            val replyBuilder = StringBuilder()
            val result = withTypingIndicator(token, chatId) {
                withTimeoutOrNull(AGENT_TIMEOUT_MS) {
                    agentPipeline.execute(
                        userMessage = text,
                        conversationHistory = history.toList(),
                        modelId = modelId,
                        scopePath = scope,
                        userContext = if (!persona.isNullOrBlank()) persona else null
                    ).collect { event ->
                        when (event) {
                            is AgentEvent.FinalAnswer -> {
                                replyBuilder.clear()
                                replyBuilder.append(event.content)
                            }
                            is AgentEvent.Error -> replyBuilder.append("\n⚠️ ${event.message}")
                            else -> Unit
                        }
                    }
                    true
                }
            }

            if (result == null) {
                return "⏱ Agent timed out after $AGENT_TIMEOUT_MINUTES minutes. " +
                    "Try simplifying or splitting the task."
            }

            // Store the exchange in session history
            val answer = TelegramConversationPolicy.visibleReply(replyBuilder.toString())
            if (answer.isNotBlank()) {
                while (history.size > MAX_HISTORY_MSGS) {
                    history.removeAt(0)
                    if (history.isNotEmpty()) history.removeAt(0)
                }
                history.add(ChatMessage(role = MessageRole.USER, content = "$senderName: $text"))
                history.add(ChatMessage(role = MessageRole.ASSISTANT, content = answer))
            }

            answer.ifBlank { "The agent did not produce a verified response." }
        } catch (e: Exception) {
            "⚠️ Agent mode error: ${e.message?.take(200) ?: "Unknown error"}"
        }
    }

    // ── Swarm mode (multi-agent orchestration) ────────────────────────────

    private suspend fun handleSwarmMode(token: String, chatId: Long, text: String): String {
        return try {
            val scope = configuredScope() ?: return "⚠️ Set a specific Target Context in OmniDev settings."
            val orchestratorModelId = settingsRepository
                .observeModelIdForRole(ModelRole.SWARM_ORCHESTRATOR).first()
            val workerModelId = settingsRepository
                .observeModelIdForRole(ModelRole.SWARM_WORKER).first()

            val replyBuilder = StringBuilder()

            val result = withTypingIndicator(token, chatId) {
                withTimeoutOrNull(AGENT_TIMEOUT_MS) {
                    swarmOrchestrator.execute(
                        userMessage = text,
                        orchestratorModelId = orchestratorModelId,
                        workerModelId = workerModelId,
                        scopePath = scope
                    ).collect { event ->
                        when (event) {
                            is com.omnidev.workspace.domain.engine.SwarmEvent.Completed ->
                                replyBuilder.append(event.summary)
                            is com.omnidev.workspace.domain.engine.SwarmEvent.Error ->
                                replyBuilder.append("\n⚠️ ${event.message}")
                            is com.omnidev.workspace.domain.engine.SwarmEvent.TaskFailed ->
                                replyBuilder.append("\n❌ Failed: ${event.task.description} — ${event.error}")
                            else -> Unit
                        }
                    }
                    true
                }
            }

            if (result == null) {
                return "⏱ Swarm timed out after $AGENT_TIMEOUT_MINUTES minutes. " +
                    "Try simplifying or splitting the task."
            }

            replyBuilder.toString().trim().ifBlank {
                "The team did not produce a verified response."
            }
        } catch (e: Exception) {
            "⚠️ Swarm mode error: ${e.message?.take(200) ?: "Unknown error"}"
        }
    }

    // ── Register bot commands via setMyCommands ────────────────────────────

    /**
     * Registers supported commands. Tool definitions are invoked through Agent mode,
     * and are not standalone Telegram slash commands.
     */
    private suspend fun registerBotCommands(token: String) = withContext(Dispatchers.IO) {
        try {
            val builtIn = listOf(
                "start" to "Start a conversation with Omni",
                "pair" to "Link this private chat with a code from OmniDev",
                "help" to "Show available commands and tools",
                "clear" to "Clear conversation history",
                "status" to "Show mode and statistics",
                "new_session" to "Start a new session and save the current one",
                "sessions" to "Show saved previous sessions",
                "mode" to "Show the active mode",
                "mode_chat" to "Enable standard chat mode 💬",
                "mode_agent" to "Enable Agent mode with tools 🤖",
                "mode_swarm" to "Enable multi-agent Swarm mode 🐝"
            )

            val arr = JSONArray()
            builtIn.forEach { (cmd, desc) ->
                arr.put(JSONObject().apply {
                    put("command", cmd)
                    put("description", desc)
                })
            }

            val body = JSONObject().apply { put("commands", arr) }
            val conn = URL("https://api.telegram.org/bot$token/setMyCommands")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val responseCode = conn.responseCode
            conn.disconnect()
            if (responseCode !in 200..299) {
                android.util.Log.w("TelegramPolling",
                    "setMyCommands returned HTTP $responseCode")
            }
        } catch (e: Exception) {
            android.util.Log.e("TelegramPolling", "Failed to register bot commands: ${e.message}", e)
        }
    }

    // ── Telegram API helpers ───────────────────────────────────────────────

    private suspend fun fetchUpdates(token: String, offset: Long): List<JSONObject> =
        withContext(Dispatchers.IO) {
            val urlStr = "https://api.telegram.org/bot$token/getUpdates" +
                "?offset=$offset&limit=50&timeout=$LONG_POLL_TIMEOUT_SEC"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = (LONG_POLL_TIMEOUT_SEC + 10) * 1_000
                conn.readTimeout = (LONG_POLL_TIMEOUT_SEC + 15) * 1_000
                val code = conn.responseCode
                val body = if (code in 200..299)
                    conn.inputStream.bufferedReader().readText()
                else conn.errorStream?.bufferedReader()?.readText() ?: ""
                if (code !in 200..299) throw java.io.IOException("getUpdates returned HTTP $code: ${body.take(200)}")
                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) throw java.io.IOException("getUpdates rejected request")
                val arr = json.optJSONArray("result") ?: return@withContext emptyList()
                (0 until arr.length()).map { arr.getJSONObject(it) }
            } finally {
                conn.disconnect()
            }
        }

    private suspend fun sendReply(token: String, chatId: Long, replyToId: Long, text: String) =
        withContext(Dispatchers.IO) {
            try {
                text.chunked(4000).forEachIndexed { idx, chunk ->
                    val body = JSONObject().apply {
                        put("chat_id", chatId)
                        put("text", chunk)
                        if (idx == 0) put("reply_to_message_id", replyToId)
                    }
                    val firstCode = postTelegramMessage(token, body)
                    if (firstCode == 400 && idx == 0) {
                        body.remove("reply_to_message_id")
                        val retryCode = postTelegramMessage(token, body)
                        if (retryCode !in 200..299) throw java.io.IOException("sendMessage HTTP $retryCode")
                    } else if (firstCode !in 200..299) {
                        throw java.io.IOException("sendMessage HTTP $firstCode")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("TelegramPolling", "Unable to send Telegram reply: ${e.message}")
            }
        }

    private fun postTelegramMessage(token: String, body: JSONObject): Int {
        val conn = URL("https://api.telegram.org/bot$token/sendMessage")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun sendTypingAction(token: String, chatId: Long) =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("chat_id", chatId)
                    put("action", "typing")
                }
                val conn = URL("https://api.telegram.org/bot$token/sendChatAction")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) { /* best effort */ }
        }

    private suspend fun fetchBotUsername(token: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = URL("https://api.telegram.org/bot$token/getMe")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) return@withContext null
            "@${json.optJSONObject("result")?.optString("username") ?: "OmniBot"}"
        } catch (_: Exception) { null }
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Omni Telegram Bot",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Telegram bot message listener"
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Omni Telegram Bot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
