package com.omnidev.workspace.data.db.entities

import androidx.room.Index
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted chat session record.
 *
 * @property id Auto-generated primary key.
 * @property title Human-readable session title (derived from the first user message).
 * @property lastUpdated Unix timestamp (ms) of the most recent activity.
 * @property createdAt Unix timestamp (ms) when the session started.
 * @property isPinned Whether this session is pinned to the top of the history list.
 * @property source Origin of the session: "app" for in-app conversations, "telegram" for Telegram-originated sessions, "discord" for Discord-originated sessions, "whatsapp_bridge" for WhatsApp Bridge-originated sessions.
 * @property telegramChatId Telegram chat ID (0 if source != "telegram").
 * @property discordChannelId Discord channel ID (empty string if source != "discord").
 * @property whatsappJid WhatsApp JID/number (empty string if source != "whatsapp_bridge").
 */
@Entity(
    tableName = "chat_sessions",
    indices = [
        Index(value = ["backgroundKey"], unique = true),
        Index(value = ["sourceAppPackage", "externalConversationId"], name = "index_chat_sessions_external_source")
    ]
)
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val lastUpdated: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val source: String = SOURCE_APP,
    val telegramChatId: Long = 0L,
    val discordChannelId: String = "",
    val whatsappJid: String = "",
    val backgroundKey: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "''")
    val sourceAppPackage: String = "",
    @androidx.room.ColumnInfo(defaultValue = "''")
    val sourceAppName: String = "",
    @androidx.room.ColumnInfo(defaultValue = "''")
    val externalConversationId: String = ""
) {
    companion object {
        const val SOURCE_APP = "app"
        const val SOURCE_TELEGRAM = "telegram"
        const val SOURCE_DISCORD = "discord"
        const val SOURCE_WHATSAPP_BRIDGE = "whatsapp_bridge"
        const val SOURCE_EXTERNAL_APP = "external_app"
    }
}
