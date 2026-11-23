package com.omnidev.workspace.ui.settings

import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import java.util.Locale

/**
 * Read-only filtering over existing Room sessions. Source tags are not identity proof.
 * Never coalesce conversations based on a display title or unverified sender ID.
 */
internal object OmniConnectInboxFilter {
    fun select(
        sessions: List<ChatSessionEntity>,
        source: String?,
        query: String
    ): List<ChatSessionEntity> {
        val term = query.trim().lowercase(Locale.ROOT)
        return sessions.asSequence()
            .filter { source == null || it.source == source }
            .filter { term.isEmpty() || it.title.lowercase(Locale.ROOT).contains(term) }
            .sortedWith(compareByDescending<ChatSessionEntity> { it.lastUpdated }.thenByDescending { it.id })
            .toList()
    }
}
