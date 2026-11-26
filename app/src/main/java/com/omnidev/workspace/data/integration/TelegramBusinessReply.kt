package com.omnidev.workspace.data.integration

import org.json.JSONObject

internal object TelegramBusinessReply {
    data class Incoming(val connectionId: String, val peerId: Long)

    fun incoming(message: JSONObject, ownerId: Long, nowSeconds: Long): Incoming? {
        val chat = message.optJSONObject("chat") ?: return null
        val sender = message.optJSONObject("from") ?: return null
        val peerId = chat.optLong("id")
        val senderId = sender.optLong("id")
        val connectionId = message.optString("business_connection_id")
        val date = message.optLong("date")
        if (ownerId <= 0 || peerId <= 0 || peerId != senderId || senderId == ownerId ||
            chat.optString("type") != "private" || sender.optBoolean("is_bot") ||
            message.has("sender_business_bot") || message.has("sender_chat") ||
            connectionId.isBlank() || connectionId.length > 256 ||
            message.optString("text").isBlank() || date <= 0 || nowSeconds - date !in 0L until 86_400L
        ) return null
        return Incoming(connectionId, peerId)
    }

    fun canReply(connection: JSONObject, connectionId: String, ownerId: Long): Boolean =
        ownerId > 0 && connection.optString("id") == connectionId &&
            connection.optBoolean("is_enabled") && connection.optJSONObject("user")?.optLong("id") == ownerId &&
            connection.optJSONObject("rights")?.optBoolean("can_reply") == true

    fun payload(incoming: Incoming, text: String): JSONObject = JSONObject().apply {
        put("business_connection_id", incoming.connectionId)
        put("chat_id", incoming.peerId)
        put("text", text)
    }
}
