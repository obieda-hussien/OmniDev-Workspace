package com.omnidev.workspace.data.ipc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.omnilink.publicsdk.OmniPublicConstants
import com.omnilink.publicsdk.PublicGatewayPolicy
import com.omnilink.publicsdk.PublicOmniRequest
import com.omnidev.workspace.MainActivity
import com.omnidev.workspace.data.db.OmniDevDatabase
import com.omnidev.workspace.data.model.ChatMessage
import com.omnidev.workspace.data.model.MessageRole
import com.omnidev.workspace.data.repository.ChatRepository
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * User-visible, unprivileged ingress for ordinary third-party apps.
 *
 * It never executes Agent/Team work directly. A validated request is persisted as an ordinary chat
 * message and the user is brought into OmniDev to continue the conversation.
 */
class PublicOmniGatewayActivity : ComponentActivity() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raw = intent.getStringExtra(OmniPublicConstants.EXTRA_PUBLIC_REQUEST_JSON)
        if (raw.isNullOrBlank()) {
            finish()
            return
        }
        if (!PublicGatewayRateLimiter.tryAcquire()) {
            finish()
            return
        }
        if (PublicGatewayPolicy.validate(raw) != null) {
            finish()
            return
        }

        val request = runCatching {
            json.decodeFromString<PublicOmniRequest>(raw)
        }.getOrElse {
            finish()
            return
        }

        val prompt = listOfNotNull(
            request.text?.trim()?.takeIf { it.isNotEmpty() },
            request.url?.trim()?.takeIf { it.isNotEmpty() }
        ).joinToString("\n").take(32 * 1024)

        if (prompt.isBlank()) {
            finish()
            return
        }

        lifecycleScope.launch {
            val db = OmniDevDatabase.getInstance(applicationContext)
            val repository = ChatRepository(db.chatSessionDao(), db.chatMessageDao())
            val conversationId = request.clientRequestId
                ?.take(120)
                ?.takeIf { PUBLIC_ID.matches(it) }
                ?: UUID.randomUUID().toString()

            val sessionId = repository.getOrCreateExternalSession(
                packageName = PUBLIC_SOURCE,
                appName = "External app",
                conversationId = conversationId,
                topicTitle = "External Omni request"
            )
            repository.saveMessage(
                sessionId = sessionId,
                message = ChatMessage(MessageRole.USER, prompt),
                sourceContextJson = buildJsonObject {
                    put("trust", "UNTRUSTED")
                    put("surface", "omni-link-public")
                    put("requestKind", request.kind.name)
                    request.mimeType?.let { put("mimeType", it.take(128)) }
                }.toString()
            )

            startActivity(
                Intent(this@PublicOmniGatewayActivity, MainActivity::class.java).apply {
                    putExtra("deep_link_session_id", sessionId)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            finish()
        }
    }

    companion object {
        private const val PUBLIC_SOURCE = "omnilink.public"
        private val PUBLIC_ID = Regex("[A-Za-z0-9._:@/-]{1,120}")
    }
}

private object PublicGatewayRateLimiter {
    private const val WINDOW_MS = 60_000L
    private const val MAX_REQUESTS_PER_WINDOW = 20
    private val timestamps = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(now: Long = System.currentTimeMillis()): Boolean {
        while (timestamps.isNotEmpty() && now - timestamps.first() >= WINDOW_MS) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= MAX_REQUESTS_PER_WINDOW) return false
        timestamps.addLast(now)
        return true
    }
}
