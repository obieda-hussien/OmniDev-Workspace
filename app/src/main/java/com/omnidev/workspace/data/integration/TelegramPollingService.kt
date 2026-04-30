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
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.model.ModelRole
import com.omnidev.workspace.data.network.CompletionService
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.ChatRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.data.tools.CompositeToolManager
import com.omnidev.workspace.data.tools.DiscordPublisherTool
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
 * All Telegram conversations are mirrored to [telegramMessages] — a static [StateFlow] that
 * any screen in the app can collect to display an in-app Telegram conversation view.
 *
 * ## Tool commands
 * On startup the service calls `setMyCommands` to register every available tool as a `/command`
 * in Telegram, so users can type `/` and see the full list of capabilities.
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
            "You are Omni — an autonomous AI assistant accessible via Telegram. " +
            "You can answer questions, help with tasks, write code, and coordinate complex workflows. " +
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
    private val apiKeyRepository: ApiKeyRepository by lazy {
        ApiKeyRepository(applicationContext)
    }
    private val completionService: CompletionService by lazy { CompletionService() }

    private val chatRepository: ChatRepository by lazy {
        val db = OmniDevDatabase.getInstance(applicationContext)
        ChatRepository(db.chatSessionDao(), db.chatMessageDao())
    }

    private val toolManager: CompositeToolManager by lazy {
        val db = OmniDevDatabase.getInstance(applicationContext)
        val memoryManager = MemoryManager(db.knowledgeDao())
        // ── Agent Brain 2.0: مراجع المحركات المُهيَّأة في OmniDevApp ──
        val omniApp = com.omnidev.workspace.OmniDevApp.instance
        CompositeToolManager(
            fileToolManager = FileToolManager(),
            memoryManager = memoryManager,
            context = applicationContext,
            settingsRepository = settingsRepository,
            godEyeProfilerTool = GodEyeProfilerTool(applicationContext, ShizukuCommandTool),
            discordPublisherTool = DiscordPublisherTool(settingsRepository),
            notionPublisherTool = NotionPublisherTool(settingsRepository),
            vectorMemoryManager = VectorMemoryManager(db.knowledgeDao()),
            apiKeyRepository = apiKeyRepository,
            headlessBrowserManager = HeadlessBrowserManager(applicationContext),
            agentBrainTools = com.omnidev.workspace.data.tools.AgentBrainTools(
                reflexion = omniApp.reflexionEngine,
                episodic = omniApp.episodicMemoryStore,
                reflexionDao = db.reflexionDao(),
                episodicDao = db.episodicMemoryDao()
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
            streamingCompletionProvider = { req, onChunk -> completionService.stream(req, onChunk) }
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

            val botUsername = fetchBotUsername(token) ?: "OmniBot"
            updateNotification("✅ $botUsername listening — use /mode_agent or /mode_swarm to upgrade a chat")

            // Register all tool definitions as Telegram slash commands
            launch { registerBotCommands(token) }

            while (isActive) {
                try {
                    val updates = fetchUpdates(token, nextOffset)
                    for (update in updates) {
                        val updateId = update.optLong("update_id")
                        if (updateId >= nextOffset) nextOffset = updateId + 1

                        val msg = update.optJSONObject("message")
                            ?: update.optJSONObject("edited_message")
                            ?: continue

                        // Extract text or a human-readable description of media content
                        val text: String = when {
                            msg.has("text") -> msg.optString("text", "").trim()
                            msg.has("photo") -> {
                                val photoArr = msg.optJSONArray("photo")
                                val best = photoArr?.optJSONObject((photoArr.length() - 1).coerceAtLeast(0))
                                val fid = best?.optString("file_id", "") ?: ""
                                val cap = msg.optString("caption", "")
                                "[📷 صورة${if (cap.isNotBlank()) ": $cap" else ""}] file_id=$fid"
                            }
                            msg.has("document") -> {
                                val doc = msg.optJSONObject("document")
                                val name = doc?.optString("file_name", "document") ?: "document"
                                val fid = doc?.optString("file_id", "") ?: ""
                                val cap = msg.optString("caption", "")
                                "[📄 ملف: $name${if (cap.isNotBlank()) " ($cap)" else ""}] file_id=$fid"
                            }
                            msg.has("location") -> {
                                val loc = msg.optJSONObject("location")
                                val lat = loc?.optDouble("latitude") ?: 0.0
                                val lon = loc?.optDouble("longitude") ?: 0.0
                                val isLive = loc?.has("live_period") == true
                                "[${if (isLive) "📍 موقع مباشر" else "📍 موقع"}: lat=$lat, lon=$lon]"
                            }
                            msg.has("contact") -> {
                                val c = msg.optJSONObject("contact")
                                val name = "${c?.optString("first_name", "")} ${c?.optString("last_name", "")}".trim()
                                val phone = c?.optString("phone_number", "") ?: ""
                                "[👤 جهة اتصال: $name, هاتف: $phone]"
                            }
                            msg.has("sticker") -> {
                                val e = msg.optJSONObject("sticker")?.optString("emoji", "") ?: ""
                                "[🎭 ملصق $e]"
                            }
                            msg.has("voice") -> {
                                val fid = msg.optJSONObject("voice")?.optString("file_id", "") ?: ""
                                "[🎤 رسالة صوتية] file_id=$fid"
                            }
                            msg.has("video") -> {
                                val fid = msg.optJSONObject("video")?.optString("file_id", "") ?: ""
                                val cap = msg.optString("caption", "")
                                "[🎥 فيديو${if (cap.isNotBlank()) ": $cap" else ""}] file_id=$fid"
                            }
                            msg.has("audio") -> {
                                val audio = msg.optJSONObject("audio")
                                val fid = audio?.optString("file_id", "") ?: ""
                                val title = audio?.optString("title", "") ?: ""
                                "[🎵 صوت${if (title.isNotBlank()) ": $title" else ""}] file_id=$fid"
                            }
                            msg.has("video_note") -> {
                                val fid = msg.optJSONObject("video_note")?.optString("file_id", "") ?: ""
                                "[📹 فيديو مستدير] file_id=$fid"
                            }
                            else -> ""
                        }
                        if (text.isBlank()) continue

                        val chatObj = msg.optJSONObject("chat") ?: continue
                        val chatId = chatObj.optLong("id")
                        val messageId = msg.optLong("message_id")
                        val fromObj = msg.optJSONObject("from")
                        val senderName = fromObj?.let {
                            val fn = it.optString("first_name", "")
                            val un = it.optString("username", "")
                            if (un.isNotBlank()) "@$un" else fn
                        } ?: "User"

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

                        launch { handleIncoming(token, chatId, messageId, senderName, text) }
                    }
                } catch (_: Exception) {
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
        text: String
    ) {
        val currentMode = chatModes[chatId] ?: OmniMode.CHAT
        val chatTitle = chatTitles[chatId] ?: chatId.toString()

        // ── Built-in commands ──────────────────────────────────────────────
        // Strip optional @BotName suffix (Telegram appends it in groups, e.g. /status@OmniBot)
        val cmd = text.lowercase().trim().split(" ")[0].let {
            if (it.contains("@")) it.substringBefore("@") else it
        }
        when (cmd) {
            "/start" -> {
                sendReply(token, chatId, messageId,
                    "👋 مرحباً! أنا *أومني* — مساعدك الذكي على تيليجرام.\n\n" +
                    "الوضع الحالي: *${currentMode.label}*\n\n" +
                    "اكتب أي سؤال أو طلب وسأرد عليك فوراً 🤖\n\n" +
                    "/help — قائمة الأوامر\n/clear — مسح المحادثة\n" +
                    "/mode\\_chat — وضع المحادثة العادية\n" +
                    "/mode\\_agent — وضع الوكيل (Agent) بكل الأدوات\n" +
                    "/mode\\_swarm — وضع الفريق (Swarm)\n" +
                    "/status — حالة الجلسة الحالية")
                return
            }

            "/clear", "/reset" -> {
                // Archive before clearing
                val oldHistory = sessionHistory[chatId]
                val oldName = sessionNameMap[chatId] ?: "جلسة ${sessionCounters.getOrDefault(chatId, 1)}"
                if (!oldHistory.isNullOrEmpty()) {
                    val sessionList = namedSessions.getOrPut(chatId) { mutableListOf() }
                    sessionList.add(oldName to oldHistory.size)
                }
                sessionHistory.remove(chatId)
                sendReply(token, chatId, messageId,
                    "✅ تم مسح تاريخ المحادثة.\n_استخدم /sessions لرؤية الجلسات السابقة._")
                return
            }

            "/help" -> {
                val toolList = toolManager.getToolDefinitions()
                    .take(20)
                    .joinToString("\n") { "  • `${it.name}` — ${it.description?.take(60) ?: ""}" }
                sendReply(token, chatId, messageId,
                    "*Omni — الأوامر المتاحة:*\n\n" +
                    "🎛️ *الأوضاع:*\n" +
                    "/mode\\_chat — محادثة عادية\n" +
                    "/mode\\_agent — وكيل ذاتي بكل الأدوات\n" +
                    "/mode\\_swarm — فريق من الوكلاء\n\n" +
                    "📋 *إدارة الجلسات:*\n" +
                    "/status — عرض الوضع والإحصائيات\n" +
                    "/new\\_session [اسم] — بدء جلسة جديدة مع حفظ الحالية\n" +
                    "/sessions — عرض الجلسات المحفوظة\n" +
                    "/clear — مسح سياق المحادثة الحالي\n\n" +
                    "🛠️ *أمثلة على الأدوات المتاحة:*\n$toolList\n\n" +
                    "_اكتب / لرؤية القائمة الكاملة للأدوات_")
                return
            }

            "/mode_chat" -> {
                chatModes[chatId] = OmniMode.CHAT
                sessionHistory.remove(chatId)
                sendReply(token, chatId, messageId,
                    "✅ تم التبديل إلى *وضع المحادثة* 💬\nردود مباشرة بدون أدوات.")
                return
            }

            "/mode_agent" -> {
                chatModes[chatId] = OmniMode.AGENT
                sessionHistory.remove(chatId)
                sendReply(token, chatId, messageId,
                    "🤖 تم التبديل إلى *وضع الوكيل* ⚡\n" +
                    "الوكيل يملك وصولاً كاملاً لجميع الأدوات ويعمل في حلقة ReAct.\n" +
                    "_تنبيه: الردود قد تأخذ وقتاً أطول نظراً لتنفيذ الأدوات._")
                return
            }

            "/mode_swarm" -> {
                chatModes[chatId] = OmniMode.SWARM
                sessionHistory.remove(chatId)
                sendReply(token, chatId, messageId,
                    "🐝 تم التبديل إلى *وضع الفريق* 🌐\n" +
                    "المُنسّق يقسّم المهمة على فريق من الوكلاء المتخصصين.\n" +
                    "_مثالي للمهام المعقدة متعددة الخطوات._")
                return
            }

            "/status" -> {
                val history = sessionHistory[chatId]
                val msgCount = history?.size ?: 0
                val toolCount = toolManager.getToolDefinitions().size
                val sesName = sessionNameMap[chatId] ?: "الجلسة الافتراضية"
                sendReply(token, chatId, messageId,
                    "📊 *حالة الجلسة:*\n\n" +
                    "🎛️ الوضع: *${(chatModes[chatId] ?: OmniMode.CHAT).label}*\n" +
                    "📝 اسم الجلسة: *$sesName*\n" +
                    "💬 رسائل في السياق: *$msgCount*\n" +
                    "🛠️ أدوات متاحة: *$toolCount*\n" +
                    "🤖 البوت شغّال: ${if (isRunning) "✅" else "❌"}\n\n" +
                    "_/new\\_session [اسم] — ابدأ جلسة جديدة_\n" +
                    "_/sessions — عرض كل الجلسات السابقة_")
                return
            }

            "/new_session" -> {
                // Archive current session
                val oldHistory = sessionHistory[chatId]
                val oldName = sessionNameMap[chatId] ?: "جلسة ${sessionCounters.getOrDefault(chatId, 1)}"
                if (!oldHistory.isNullOrEmpty()) {
                    val sessionList = namedSessions.getOrPut(chatId) { mutableListOf() }
                    sessionList.add(oldName to oldHistory.size)
                }
                // Start new session
                val counter = (sessionCounters.getOrDefault(chatId, 1)) + 1
                sessionCounters[chatId] = counter
                val parts = text.split(" ", limit = 2)
                val newName = if (parts.size > 1 && parts[1].isNotBlank())
                    parts[1].trim() else "جلسة $counter"
                sessionHistory.remove(chatId)
                sessionNameMap[chatId] = newName
                sendReply(token, chatId, messageId,
                    "🆕 تم بدء جلسة جديدة: *$newName*\n" +
                    "سياق المحادثة تم مسحه — ابدأ من جديد!")
                return
            }

            "/sessions" -> {
                val list = namedSessions[chatId]
                if (list.isNullOrEmpty()) {
                    sendReply(token, chatId, messageId,
                        "📋 لا توجد جلسات محفوظة بعد.\n\n" +
                        "_استخدم /new\\_session [اسم] لبدء جلسة وحفظ الحالية_")
                } else {
                    val sb = StringBuilder("📋 *الجلسات السابقة:*\n\n")
                    list.takeLast(10).forEachIndexed { i, (name, count) ->
                        sb.append("${i + 1}. *$name* — $count رسالة\n")
                    }
                    val currentName = sessionNameMap[chatId] ?: "الجلسة الحالية"
                    val currentCount = sessionHistory[chatId]?.size ?: 0
                    sb.append("\n🟢 الحالية: *$currentName* ($currentCount رسالة)")
                    sendReply(token, chatId, messageId, sb.toString())
                }
                return
            }
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

        val reply = when (chatModes[chatId] ?: OmniMode.CHAT) {
            OmniMode.AGENT -> handleAgentMode(token, chatId, senderName, text)
            OmniMode.SWARM -> handleSwarmMode(token, chatId, text)
            else -> handleChatMode(chatId, senderName, text)
        }

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

        return try {
            val modelId = settingsRepository.observeModelIdForRole(ModelRole.CHAT).first()
            val model = ModelRegistry.findModelById(modelId)
            val apiKey = model?.let { apiKeyRepository.getApiKey(it.provider) }
            val persona = settingsRepository.observeUserPersona().first()
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
            val accumulated = StringBuilder()
            completionService.stream(request) { chunk -> accumulated.append(chunk) }
            val reply = accumulated.toString().trim()
            if (reply.isNotBlank()) {
                history.add(ChatMessage(role = MessageRole.ASSISTANT, content = reply))
            }
            reply
        } catch (e: Exception) {
            "⚠️ خطأ: ${e.message?.take(200) ?: "خطأ غير معروف"}"
        }
    }

    // ── Agent mode (ReAct loop with tools) ────────────────────────────────

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
            val modelId = settingsRepository.observeModelIdForRole(ModelRole.AGENT).first()
            val persona = settingsRepository.observeUserPersona().first()
            val history = sessionHistory.getOrPut(chatId) { mutableListOf() }

            val replyBuilder = StringBuilder()
            val toolLog = StringBuilder()

            val result = withTypingIndicator(token, chatId) {
                withTimeoutOrNull(AGENT_TIMEOUT_MS) {
                    agentPipeline.execute(
                        userMessage = text,
                        conversationHistory = history.toList(),
                        modelId = modelId,
                        scopePath = "/",
                        userContext = if (!persona.isNullOrBlank()) persona else null
                    ).collect { event ->
                        when (event) {
                            is AgentEvent.FinalAnswer -> replyBuilder.append(event.content)
                            is AgentEvent.ToolExecution ->
                                toolLog.append("\n🛠 `${event.toolName}` — iteration ${event.iteration}")
                            is AgentEvent.Error -> replyBuilder.append("\n⚠️ ${event.message}")
                            is AgentEvent.StreamChunk -> replyBuilder.append(event.delta)
                            else -> Unit
                        }
                    }
                    true
                }
            }

            if (result == null) {
                return "⏱ انتهت مهلة الوكيل ($AGENT_TIMEOUT_MINUTES دقائق). " +
                    "حاول تبسيط المهمة أو تقسيمها."
            }

            // Store the exchange in session history
            if (replyBuilder.isNotBlank()) {
                while (history.size > MAX_HISTORY_MSGS) {
                    history.removeAt(0)
                    if (history.isNotEmpty()) history.removeAt(0)
                }
                history.add(ChatMessage(role = MessageRole.USER, content = "$senderName: $text"))
                history.add(ChatMessage(role = MessageRole.ASSISTANT, content = replyBuilder.toString()))
            }

            val suffix = if (toolLog.isNotEmpty())
                "\n\n_⚙️ الأدوات المُستخدمة:${toolLog}_"
            else ""

            (replyBuilder.toString().trim() + suffix).ifBlank {
                "✅ اكتمل تنفيذ المهمة. (لم يُنتج الوكيل رسالة نصية)"
            }
        } catch (e: Exception) {
            "⚠️ خطأ في وضع الوكيل: ${e.message?.take(200) ?: "خطأ غير معروف"}"
        }
    }

    // ── Swarm mode (multi-agent orchestration) ────────────────────────────

    private suspend fun handleSwarmMode(token: String, chatId: Long, text: String): String {
        return try {
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
                        scopePath = "/"
                    ).collect { event ->
                        when (event) {
                            is com.omnidev.workspace.domain.engine.SwarmEvent.Completed ->
                                replyBuilder.append(event.summary)
                            is com.omnidev.workspace.domain.engine.SwarmEvent.Error ->
                                replyBuilder.append("\n⚠️ ${event.message}")
                            is com.omnidev.workspace.domain.engine.SwarmEvent.TaskFailed ->
                                replyBuilder.append("\n❌ فشل: ${event.task.description} — ${event.error}")
                            else -> Unit
                        }
                    }
                    true
                }
            }

            if (result == null) {
                return "⏱ انتهت مهلة الفريق ($AGENT_TIMEOUT_MINUTES دقائق). " +
                    "حاول تبسيط المهمة أو تقسيمها."
            }

            replyBuilder.toString().trim().ifBlank {
                "✅ اكتمل تنفيذ مهمة الفريق. (لم يُنتج الفريق رسالة نصية)"
            }
        } catch (e: Exception) {
            "⚠️ خطأ في وضع الفريق: ${e.message?.take(200) ?: "خطأ غير معروف"}"
        }
    }

    // ── Register bot commands via setMyCommands ────────────────────────────

    /**
     * Registers all available tool definitions as Telegram slash-commands so the
     * user can type `/` in Telegram and see the full tool list.
     * Built-in utility commands are listed first, then all agent tools.
     */
    private suspend fun registerBotCommands(token: String) = withContext(Dispatchers.IO) {
        try {
            val builtIn = listOf(
                "start" to "بدء المحادثة مع أومني",
                "help" to "قائمة الأوامر والأدوات المتاحة",
                "clear" to "مسح تاريخ المحادثة",
                "status" to "عرض الوضع والإحصائيات",
                "new_session" to "بدء جلسة جديدة وحفظ الحالية",
                "sessions" to "عرض الجلسات السابقة المحفوظة",
                "mode_chat" to "تفعيل وضع المحادثة العادية 💬",
                "mode_agent" to "تفعيل وضع الوكيل بالأدوات 🤖",
                "mode_swarm" to "تفعيل وضع الفريق متعدد الوكلاء 🐝"
            )

            // Sanitize tool names to valid Telegram command format (a-z, 0-9, underscore only)
            val toolCommands = toolManager.getToolDefinitions()
                .map { tool ->
                    val safeName = tool.name
                        .lowercase()
                        .replace(Regex("[^a-z0-9_]"), "_")
                        .take(32)
                    val safeDesc = (tool.description ?: "Run ${tool.name}").take(255)
                    safeName to safeDesc
                }
                .filter { (name, _) -> name.isNotBlank() }

            // Merge built-in first, then tools; keep first occurrence on name collision
            val allCommands = (builtIn + toolCommands)
                .distinctBy { it.first }
                .take(100)  // Telegram hard limit: 100 commands

            val arr = JSONArray()
            allCommands.forEach { (cmd, desc) ->
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
                    "setMyCommands returned HTTP $responseCode for ${allCommands.size} commands")
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
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = (LONG_POLL_TIMEOUT_SEC + 10) * 1_000
                conn.readTimeout = (LONG_POLL_TIMEOUT_SEC + 15) * 1_000
                val code = conn.responseCode
                val body = if (code in 200..299)
                    conn.inputStream.bufferedReader().readText()
                else conn.errorStream?.bufferedReader()?.readText() ?: ""
                conn.disconnect()
                if (code !in 200..299) return@withContext emptyList()
                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) return@withContext emptyList()
                val arr = json.optJSONArray("result") ?: return@withContext emptyList()
                (0 until arr.length()).map { arr.getJSONObject(it) }
            } catch (_: Exception) {
                emptyList()
            }
        }

    private suspend fun sendReply(token: String, chatId: Long, replyToId: Long, text: String) =
        withContext(Dispatchers.IO) {
            try {
                text.chunked(4000).forEachIndexed { idx, chunk ->
                    val body = JSONObject().apply {
                        put("chat_id", chatId)
                        put("text", chunk)
                        put("parse_mode", "Markdown")
                        if (idx == 0) put("reply_to_message_id", replyToId)
                    }
                    val conn = URL("https://api.telegram.org/bot$token/sendMessage")
                        .openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    conn.doOutput = true
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    conn.responseCode
                    conn.disconnect()
                }
            } catch (_: Exception) { /* best effort */ }
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
