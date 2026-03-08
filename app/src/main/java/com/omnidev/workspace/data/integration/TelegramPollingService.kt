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
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.CompletionRequest
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.network.CompletionService
import com.omnidev.workspace.data.repository.ApiKeyRepository
import com.omnidev.workspace.data.repository.SettingsRepository
import com.omnidev.workspace.registry.ModelRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Foreground service that polls the Telegram Bot API for incoming messages and
 * responds to them using the AI — inspired by OpenClaw's Telegram channel integration.
 *
 * ## How it works (OpenClaw-style)
 * 1. Every [LONG_POLL_TIMEOUT_SEC] seconds it calls `getUpdates` (long-polling).
 * 2. Each new text message is routed through [CompletionService] with a per-chat
 *    conversation history stored in [sessionHistory].
 * 3. The AI's reply is sent back to the same Telegram chat via `sendMessage`.
 *
 * ## Start / stop
 * ```kotlin
 * // Start listening
 * context.startService(Intent(context, TelegramPollingService::class.java))
 *
 * // Stop listening
 * context.startService(
 *     Intent(context, TelegramPollingService::class.java)
 *         .apply { action = ACTION_STOP }
 * )
 * ```
 *
 * Requires `TELEGRAM_BOT_TOKEN` to be configured in Settings → Integrations.
 */
class TelegramPollingService : Service() {

    companion object {
        const val ACTION_STOP = "com.omnidev.workspace.TELEGRAM_POLL_STOP"

        /** Expose running state so UI can show a connected indicator. */
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID = "omni_telegram_polling"
        private const val NOTIFICATION_ID = 5501

        /** Gap between polls (actual waiting happens inside getUpdates long-poll). */
        private const val POLL_INTERVAL_MS = 1_000L

        /** Telegram long-poll timeout (seconds) — keeps connection alive, reduces battery. */
        private const val LONG_POLL_TIMEOUT_SEC = 25

        /** Max per-chat history messages to keep in memory. */
        private const val MAX_HISTORY_MSGS = 20

        /** Max simultaneous conversation sessions. */
        private const val MAX_SESSIONS = 50

        /** System prompt injected into every Telegram conversation. */
        private const val TELEGRAM_SYSTEM_PROMPT =
            "You are Omni — an autonomous AI assistant accessible via Telegram. " +
            "You can answer questions, help with tasks, write code, and coordinate complex workflows. " +
            "Be direct, helpful, and concise. Respond in the same language the user writes in. " +
            "If a task requires device-level actions (UI automation, file access), ask the user " +
            "to open the Omni app on their phone directly."
    }

    // ── State ──────────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    /** Next getUpdates offset — set to last_update_id + 1 after each batch. */
    @Volatile
    private var nextOffset: Long = 0L

    /** Per-chat conversation history (chat_id → message list). LRU-ordered for eviction. */
    private val sessionHistory: MutableMap<Long, MutableList<ChatMessage>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<Long, MutableList<ChatMessage>>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, MutableList<ChatMessage>>?) =
                    size > MAX_SESSIONS
            }
        )

    private val settingsRepository: SettingsRepository by lazy { SettingsRepository(applicationContext) }
    private val apiKeyRepository: ApiKeyRepository by lazy { ApiKeyRepository(applicationContext) }
    private val completionService: CompletionService by lazy { CompletionService() }

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
            updateNotification("✅ $botUsername is listening for Telegram messages…")

            while (isActive) {
                try {
                    val updates = fetchUpdates(token, nextOffset)
                    for (update in updates) {
                        val updateId = update.optLong("update_id")
                        if (updateId >= nextOffset) nextOffset = updateId + 1

                        val msg = update.optJSONObject("message")
                            ?: update.optJSONObject("edited_message")
                            ?: continue

                        val text = msg.optString("text", "").trim()
                        if (text.isBlank()) continue  // skip stickers / media

                        val chatObj = msg.optJSONObject("chat") ?: continue
                        val chatId = chatObj.optLong("id")
                        val messageId = msg.optLong("message_id")
                        val fromObj = msg.optJSONObject("from")
                        val senderName = fromObj?.let {
                            val fn = it.optString("first_name", "")
                            val un = it.optString("username", "")
                            if (un.isNotBlank()) "@$un" else fn
                        } ?: "User"

                        // Handle each chat in its own coroutine so they don't block each other
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
        // Built-in slash commands
        when (text.lowercase().trim()) {
            "/start" -> {
                sendReply(token, chatId, messageId,
                    "👋 مرحباً! أنا *أومني* — مساعدك الذكي على تيليجرام.\n\n" +
                    "اكتب أي سؤال أو طلب وسأرد عليك فوراً 🤖\n\n" +
                    "/help — قائمة الأوامر\n/clear — مسح المحادثة")
                return
            }
            "/clear", "/reset" -> {
                sessionHistory.remove(chatId)
                sendReply(token, chatId, messageId, "✅ تم مسح تاريخ المحادثة.")
                return
            }
            "/help" -> {
                sendReply(token, chatId, messageId,
                    "*Omni — الأوامر المتاحة:*\n\n" +
                    "/start — بدء المحادثة\n" +
                    "/clear — مسح تاريخ المحادثة\n" +
                    "/help — عرض المساعدة\n\n" +
                    "أو اكتب أي سؤال أو طلب مباشرةً! 💬")
                return
            }
        }

        // Show typing indicator while processing
        sendTypingAction(token, chatId)

        // Retrieve or create session history for this chat
        val history = sessionHistory.getOrPut(chatId) { mutableListOf() }

        // Trim oldest message pairs to keep context window manageable
        while (history.size > MAX_HISTORY_MSGS) {
            history.removeAt(0)
            if (history.isNotEmpty()) history.removeAt(0)
        }

        history.add(ChatMessage(role = MessageRole.USER, content = "$senderName: $text"))

        try {
            val modelId = settingsRepository
                .observeModelIdForRole(com.omnidev.workspace.data.model.ModelRole.CHAT)
                .first()
            val model = ModelRegistry.findModelById(modelId)
            val apiKey = model?.let { apiKeyRepository.getApiKey(it.provider) }

            // Optionally inject user persona
            val persona = settingsRepository.observeUserPersona().first()
            val effectiveSystem = if (!persona.isNullOrBlank())
                "$TELEGRAM_SYSTEM_PROMPT\n\n## User Context\n$persona"
            else TELEGRAM_SYSTEM_PROMPT

            val request = CompletionRequest(
                modelId = modelId,
                messages = history.toList(),
                systemPrompt = effectiveSystem,
                maxTokens = 1500,
                temperature = 0.7,
                apiKey = apiKey
            )

            val accumulated = StringBuilder()
            completionService.stream(request) { chunk -> accumulated.append(chunk) }
            val reply = accumulated.toString().trim()

            if (reply.isNotBlank()) {
                history.add(ChatMessage(role = MessageRole.ASSISTANT, content = reply))
                sendReply(token, chatId, messageId, reply)
            }
        } catch (e: Exception) {
            sendReply(token, chatId, messageId,
                "⚠️ حصل خطأ: ${e.message?.take(200) ?: "خطأ غير معروف"}")
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
                // Telegram max message length is 4096 chars; split if needed
                val chunks = text.chunked(4000)
                chunks.forEachIndexed { idx, chunk ->
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
