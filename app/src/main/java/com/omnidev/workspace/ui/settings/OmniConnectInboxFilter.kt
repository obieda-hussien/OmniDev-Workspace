package com.omnidev.workspace.ui.settings

import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import java.text.Normalizer
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
        val terms = normalize(query.take(256)).split(Regex("\\s+")).filter { it.isNotEmpty() }
        return sessions.asSequence()
            .filter { source == null || it.source == source }
            .filter { session ->
                if (terms.isEmpty()) true else {
                    val title = normalize(session.title)
                    terms.all { title.contains(it) }
                }
            }
            .sortedWith(compareByDescending<ChatSessionEntity> { it.lastUpdated }.thenByDescending { it.id })
            .toList()
    }

    /** Search normalization only; never used for sender identity or session merging. */
    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        return buildString(decomposed.length) {
            for (character in decomposed) {
                if (Character.getType(character) == Character.NON_SPACING_MARK.toInt() || character == 'ـ') continue
                append(when (character) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ى' -> 'ي'
                    else -> character
                })
            }
        }
    }
}
