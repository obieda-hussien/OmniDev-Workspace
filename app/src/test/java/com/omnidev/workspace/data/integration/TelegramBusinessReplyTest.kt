package com.omnidev.workspace.data.integration

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TelegramBusinessReplyTest {
    private fun message() = JSONObject("""{
        "business_connection_id":"connection", "date":999,
        "chat":{"id":22,"type":"private"}, "from":{"id":22,"is_bot":false},
        "text":"Hello"
    }""")

    @Test fun routesReplyToTheIncomingPeerOnTheBusinessConnection() {
        val incoming = checkNotNull(TelegramBusinessReply.incoming(message(), 11, 1000))
        val payload = TelegramBusinessReply.payload(incoming, "I'll get back to you.")
        assertEquals("connection", payload.getString("business_connection_id"))
        assertEquals(22L, payload.getLong("chat_id"))
        assertEquals("I'll get back to you.", payload.getString("text"))
    }

    @Test fun skipsOutgoingBotGroupAndExpiredMessages() {
        assertNull(TelegramBusinessReply.incoming(message(), 22, 1000))
        assertNull(TelegramBusinessReply.incoming(message().put("sender_business_bot", JSONObject()), 11, 1000))
        val group = message().apply { getJSONObject("chat").put("type", "group") }
        assertNull(TelegramBusinessReply.incoming(group, 11, 1000))
        val bot = message().apply { getJSONObject("from").put("is_bot", true) }
        assertNull(TelegramBusinessReply.incoming(bot, 11, 1000))
        assertNull(TelegramBusinessReply.incoming(message(), 11, 999 + 86_400L))
    }

    @Test fun onlyEnabledConnectionsWithReplyRightsAndThePairedOwnerQualify() {
        val connection = JSONObject("""{
            "id":"connection","user":{"id":11},"is_enabled":true,"rights":{"can_reply":true}
        }""")
        assertTrue(TelegramBusinessReply.canReply(connection, "connection", 11))
        assertFalse(TelegramBusinessReply.canReply(connection, "another", 11))
        assertFalse(TelegramBusinessReply.canReply(connection, "connection", 12))
        assertFalse(TelegramBusinessReply.canReply(connection.put("is_enabled", false), "connection", 11))
        connection.put("is_enabled", true).getJSONObject("rights").remove("can_reply")
        assertFalse(TelegramBusinessReply.canReply(connection, "connection", 11))
    }
}
