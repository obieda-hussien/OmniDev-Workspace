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
import com.omnidev.workspace.data.tools.CompositeToolManager
import com.omnidev.workspace.data.tools.FileToolManager
import com.omnidev.workspace.data.tools.HeadlessBrowserManager
import com.omnidev.workspace.data.tools.MemoryManager
import com.omnidev.workspace.domain.engine.AgentConfig
import com.omnidev.workspace.domain.engine.AgentEvent
import com.omnidev.workspace.domain.engine.AgentPipeline
import com.omnidev.workspace.domain.engine.OmniMode
import com.omnidev.workspace.domain.engine.SwarmEvent
import com.omnidev.workspace.domain.engine.SwarmOrchestrator
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Foreground service that polls a self-hosted **Baileys bridge server** for incoming
 * WhatsApp messages and responds using the AI.
 *
 * ## Baileys Bridge Server
 * Users must host a simple Node.js server that uses the Baileys library and exposes REST endpoints.
 * The bridge handles the WhatsApp Web protocol pairing and session management.
 *
 * ## Pairing Flow
 * 1. User enters phone number + bridge URL in Settings → Integrations → WhatsApp Bridge
 * 2. Taps "Request Pairing Code" — the app POSTs to `POST /pair` on the bridge
 * 3. Bridge returns a 8-digit pairing code (shown in the app)
 * 4. User opens WhatsApp → Linked Devices → Link with phone number → enters the code
 * 5. Bridge is now connected; the app enables the listener toggle
 *
 * ## Modes (per-chat JID)
 * - `!mode_chat`  — Chat mode (default)
 * - `!mode_agent` — Full ReAct loop with all tools (AgentPipeline)
 * - `!mode_swarm` — Multi-agent orchestration (SwarmOrchestrator)
 *
 * Sessions are persisted to the app Room DB (whatsappJid column, DB v5).
 * WhatsApp conversations appear in the app sidebar with a 📱 badge.
 */
class WhatsAppBridgeService : Service() {

    // ── Data model ─────────────────────────────────────────────────────────

    data class WhatsAppMessage(
        val jid: String,
        val senderName: String,
        val body: String,
        val isFromMe: Boolean,
        val mode: OmniMode = OmniMode.CHAT,
        val timestamp: Long = System.currentTimeMillis()
    )

    // ── Companion / static state ───────────────────────────────────────────

    companion object {
        const val ACTION_STOP = "com.omnidev.workspace.WHATSAPP_BRIDGE_STOP"

        @Volatile
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID_NOTIF    = "omni_whatsapp_bridge"
        private const val NOTIFICATION_ID     = 5503
        private const val POLL_INTERVAL_MS    = 3_000L
        private const val MAX_HISTORY_MSGS    = 20
        private const val MAX_SESSIONS        = 50
        private const val MAX_MIRROR_MESSAGES = 200
        private const val AGENT_TIMEOUT_MS    = 8 * 60 * 1_000L

        private val _whatsappMessages = MutableStateFlow<List<WhatsAppMessage>>(emptyList())
        val whatsappMessages: StateFlow<List<WhatsAppMessage>> = _whatsappMessages.asStateFlow()

        private const val WHATSAPP_SYSTEM_PROMPT =
            "You are OmniDev, an AI assistant connected to WhatsApp via a Baileys bridge. " +
            "Reply concisely and helpfully. You can run as an autonomous agent with tools " +
            "when the user switches to agent mode (!mode_agent)."
    }

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
        val memoryManager = MemoryManager(db.knowledgeDao())
        // Agent Brain 2.0: engine references initialized by OmniDevApp.
        val omniApp = com.omnidev.workspace.OmniDevApp.instance
        CompositeToolManager(
            fileToolManager = FileToolManager(),
            memoryManager = memoryManager,
            context = applicationContext,
            settingsRepository = settingsRepository,
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
            streamingCompletionProvider = { req, onChunk -> completionService.stream(req, onChunk) }
        )
    }

    // ── Instance state ─────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var bridgeUrl: String = ""
    private lateinit var bridgeClient: WhatsAppBridgeClient
    private val cursorPrefs by lazy { getSharedPreferences("whatsapp_bridge_cursor_v1", MODE_PRIVATE) }
    private var lastMessageId = 0L
    private var activeMessageId = 0L

    private val chatModes: MutableMap<String, OmniMode> = Collections.synchronizedMap(
        object : LinkedHashMap<String, OmniMode>(MAX_SESSIONS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OmniMode>?) =
                size > MAX_SESSIONS
        }
    )
    private val sessionHistories: MutableMap<String, MutableList<ChatMessage>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, MutableList<ChatMessage>>(MAX_SESSIONS, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<ChatMessage>>?) =
                    size > MAX_SESSIONS
            }
        )

    // ── Service lifecycle ──────────────────────────────────────────────────

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
        startForeground(NOTIFICATION_ID, buildNotification("📱 WhatsApp Bridge connecting…"))
        isRunning = true

        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            try {
            bridgeUrl = settingsRepository.observeWhatsAppBridgeUrl().first()?.trimEnd('/') ?: ""
            val key = settingsRepository.observeWhatsAppBridgeApiKey().first().orEmpty()
            bridgeClient = WhatsAppBridgeClient(bridgeUrl, key)
            val identity = MessageDigest.getInstance("SHA-256")
                .digest("$bridgeUrl:$key".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            lastMessageId = if (cursorPrefs.getString("identity", null) == identity)
                cursorPrefs.getLong("cursor", 0L) else 0L
            updateNotification("WhatsApp Bridge listener active — checking connection")
            while (isActive) {
                try {
                    pollMessages(identity)
                    delay(POLL_INTERVAL_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: WhatsAppBridgeClient.BridgeException) {
                    if (e.status == 410) {
                        val oldest = bridgeClient.messages(0).optJSONArray("messages")?.optJSONObject(0)?.optLong("id") ?: 1L
                        lastMessageId = oldest - 1
                        cursorPrefs.edit().putString("identity", identity).putLong("cursor", lastMessageId).commit()
                    }
                    android.util.Log.w("WhatsAppBridge", "Bridge HTTP ${e.status}: ${e.message}")
                    delay(5_000)
                } catch (e: Exception) {
                    android.util.Log.w("WhatsAppBridge", "Listener error: ${e.message}")
                    updateNotification("⚠️ WhatsApp Bridge unreachable; retrying")
                    delay(5_000)
                }
            }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("WhatsAppBridge", "Cannot start bridge listener: ${e.message}")
                updateNotification("⚠️ Configure a local Bridge URL and API key in Integrations")
                settingsRepository.setWhatsAppBridgeEnabled(false)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        pollingJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Polling ────────────────────────────────────────────────────────────

    private suspend fun pollMessages(identity: String) {
        val response = bridgeClient.messages(lastMessageId)
        val msgs = response.optJSONArray("messages") ?: return
        val ownerJid = bridgeClient.status().optString("ownerJid")
        for (index in 0 until msgs.length()) {
            val msg = msgs.getJSONObject(index)
            val id = msg.optLong("id")
            if (id <= lastMessageId) continue

            val jid      = msg.optString("from", "")
            val body     = msg.optString("body", "")
            val isFromMe = msg.optBoolean("fromMe", false)
            if (jid == ownerJid && body.isNotBlank()) {
                activeMessageId = id
                val senderName = msg.optString("senderName", jid.substringBefore("@"))
                val waMsg = WhatsAppMessage(jid, senderName, body, isFromMe)
                _whatsappMessages.value =
                    (_whatsappMessages.value + waMsg).takeLast(MAX_MIRROR_MESSAGES)

                val sessionId = chatRepository.findOrCreateWhatsAppBridgeSession(jid, senderName)
                chatRepository.saveMessage(sessionId, ChatMessage(role = MessageRole.USER, content = body))
                processIncomingMessage(jid, senderName, body, sessionId)
            }
            lastMessageId = id
            check(cursorPrefs.edit().putString("identity", identity).putLong("cursor", id).commit()) {
                "Could not persist WhatsApp cursor"
            }
        }
    }

    // ── Message processing ─────────────────────────────────────────────────

    private suspend fun processIncomingMessage(
        jid: String,
        senderName: String,
        body: String,
        sessionId: Long
    ) {
        val cmd = body.trim().lowercase()
        when {
            cmd == "!mode_chat" -> {
                chatModes[jid] = OmniMode.CHAT
                sendWhatsApp(jid, "✅ Switched to Chat mode.")
                return
            }
            cmd == "!mode_agent" -> {
                if (configuredScope() == null) {
                    sendWhatsApp(jid, "⚠️ Set a specific Target Context in OmniDev before Agent mode.")
                    return
                }
                chatModes[jid] = OmniMode.AGENT
                sendWhatsApp(jid, "🤖 Switched to Agent mode — full ReAct loop with all tools.")
                return
            }
            cmd == "!mode_swarm" -> {
                if (configuredScope() == null) {
                    sendWhatsApp(jid, "⚠️ Set a specific Target Context in OmniDev before Swarm mode.")
                    return
                }
                chatModes[jid] = OmniMode.SWARM
                sendWhatsApp(jid, "🐝 Switched to Swarm mode — multi-agent orchestration.")
                return
            }
            cmd == "!status" -> {
                val mode = chatModes.getOrDefault(jid, OmniMode.CHAT)
                val historySize = sessionHistories[jid]?.size ?: 0
                sendWhatsApp(jid, "📊 *Status*\nMode: ${mode.name}\nContext messages: $historySize")
                return
            }
            cmd == "!clear" -> {
                sessionHistories.remove(jid)
                sendWhatsApp(jid, "🗑️ Conversation cleared.")
                return
            }
            cmd == "!help" -> {
                sendWhatsApp(
                    jid,
                    "🆘 *OmniDev WhatsApp Bridge*\n\n" +
                    "*Mode commands:*\n" +
                    "!mode_chat — Chat with AI\n" +
                    "!mode_agent — Full agent with tools\n" +
                    "!mode_swarm — Multi-agent swarm\n\n" +
                    "*Other:*\n" +
                    "!status — Current mode & context size\n" +
                    "!clear — Clear conversation\n" +
                    "!help — This help message"
                )
                return
            }
        }

        val history = sessionHistories.getOrPut(jid) { mutableListOf() }
        history.add(ChatMessage(role = MessageRole.USER, content = body))
        if (history.size > MAX_HISTORY_MSGS) history.subList(0, history.size - MAX_HISTORY_MSGS).clear()

        val mode = chatModes.getOrDefault(jid, OmniMode.CHAT)

        val reply: String = withTimeoutOrNull(AGENT_TIMEOUT_MS) {
            when (mode) {
                OmniMode.CHAT, OmniMode.AUTO -> {
                    val modelId = settingsRepository.observeModelIdForRole(ModelRole.CHAT).first()
                        ?: ModelRegistry.getDefaultModelForRole(ModelRole.CHAT).id
                    val request = com.omnidev.workspace.data.model.CompletionRequest(
                        modelId = modelId,
                        systemPrompt = WHATSAPP_SYSTEM_PROMPT,
                        messages = history.takeLast(MAX_HISTORY_MSGS),
                        temperature = 0.7
                    )
                    runCatching {
                        completionService.invoke(request).content ?: "No response."
                    }.getOrElse { e ->
                        if (e is CancellationException) throw e
                        "Error: ${e.message}"
                    }
                }
                OmniMode.AGENT -> {
                    val scope = configuredScope() ?: return@withTimeoutOrNull "⚠️ Set a specific Target Context in OmniDev."
                    val modelId = settingsRepository.observeModelIdForRole(ModelRole.AGENT).first()
                        ?: ModelRegistry.getDefaultModelForRole(ModelRole.AGENT).id
                    val replyBuilder = StringBuilder()
                    val streamBuilder = StringBuilder()
                    runCatching {
                        agentPipeline.execute(
                            userMessage = body,
                            conversationHistory = history.takeLast(MAX_HISTORY_MSGS),
                            modelId = modelId,
                            scopePath = scope
                        ).collect { event ->
                            when (event) {
                                is AgentEvent.FinalAnswer  -> { replyBuilder.clear(); replyBuilder.append(event.content) }
                                is AgentEvent.StreamChunk  -> if (streamBuilder.length < 30_000) streamBuilder.append(event.delta)
                                is AgentEvent.Error        -> replyBuilder.append("\n⚠️ ${event.message}")
                                else -> Unit
                            }
                        }
                    }.getOrElse { e ->
                        if (e is CancellationException) throw e
                        replyBuilder.append("⚠️ Agent error: ${e.message}")
                    }
                    replyBuilder.toString().ifBlank { streamBuilder.toString() }.trim()
                        .ifBlank { "✅ Task completed." }
                }
                OmniMode.SWARM -> {
                    val scope = configuredScope() ?: return@withTimeoutOrNull "⚠️ Set a specific Target Context in OmniDev."
                    val orchestratorModelId = settingsRepository.observeModelIdForRole(ModelRole.SWARM_ORCHESTRATOR).first()
                        ?: ModelRegistry.getDefaultModelForRole(ModelRole.SWARM_ORCHESTRATOR).id
                    val workerModelId = settingsRepository.observeModelIdForRole(ModelRole.SWARM_WORKER).first()
                        ?: orchestratorModelId
                    val replyBuilder = StringBuilder()
                    runCatching {
                        swarmOrchestrator.execute(
                            userMessage = body,
                            orchestratorModelId = orchestratorModelId,
                            workerModelId = workerModelId,
                            scopePath = scope
                        ).collect { event ->
                            when (event) {
                                is SwarmEvent.Completed -> replyBuilder.append(event.summary)
                                is SwarmEvent.Error     -> replyBuilder.append("\n⚠️ ${event.message}")
                                else -> Unit
                            }
                        }
                    }.getOrElse { e ->
                        if (e is CancellationException) throw e
                        replyBuilder.append("⚠️ Swarm error: ${e.message}")
                    }
                    replyBuilder.toString().trim().ifBlank { "✅ Swarm task completed." }
                }
            }
        } ?: "⏱ Agent timed out (8 min). Please try a simpler request."

        if (reply.isBlank()) return

        history.add(ChatMessage(role = MessageRole.ASSISTANT, content = reply))

        chatRepository.saveMessage(sessionId, ChatMessage(role = MessageRole.ASSISTANT, content = reply))

        val botMsg = WhatsAppMessage(jid, "OmniDev", reply, isFromMe = true)
        _whatsappMessages.value = (_whatsappMessages.value + botMsg).takeLast(MAX_MIRROR_MESSAGES)

        val replyChunks = reply.chunked(4000)
        replyChunks.forEachIndexed { i, chunk ->
            sendWhatsApp(jid, chunk, i)
            if (i < replyChunks.size - 1) delay(300)
        }
    }

    private suspend fun configuredScope(): String? = settingsRepository.observeTargetContext().first()
        ?.takeIf { it.isNotBlank() && it != "/" }

    // ── Bridge REST helpers ────────────────────────────────────────────────

    private fun sendWhatsApp(jid: String, message: String, part: Int = 0) {
        bridgeClient.send(jid, message, "$activeMessageId:$part")
    }

    // ── Notification helpers ───────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_NOTIF,
                "WhatsApp Bridge Listener",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID_NOTIF)
            .setContentTitle("OmniDev — WhatsApp Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
