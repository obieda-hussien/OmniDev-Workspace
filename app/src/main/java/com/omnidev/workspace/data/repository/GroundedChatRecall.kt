package com.omnidev.workspace.data.repository

import com.omnidev.workspace.data.db.dao.ChatMessageDao
import com.omnidev.workspace.data.db.dao.ChatMessageSource
import com.omnidev.workspace.data.db.dao.ChatMessageChunk
import com.omnidev.workspace.data.db.dao.ChatSessionDao
import com.omnidev.workspace.data.db.entities.ChatSessionEntity

/**
 * Retrieves original messages, never generated summaries. A source is valid only while its
 * session and message still exist; deleting a conversation removes it from future recall.
 */
class GroundedChatRecall(
    private val sessions: ChatSessionDao,
    private val messages: ChatMessageDao
) {
    data class Hit(val session: ChatSessionEntity, val message: ChatMessageSource, val score: Int)

    suspend fun search(query: String, limit: Int = 8, sessionId: Long? = null): List<Hit> {
        val terms = terms(query).take(MAX_TERMS)
        if (terms.isEmpty()) return emptyList()
        val candidates = LinkedHashMap<Long, ChatMessageSource>()
        val originalTerms = query.lowercase().split(SPLIT)
            .filter { it.length >= 2 && normalize(it) !in STOP }
        val searchTerms = (originalTerms + terms + terms.map {
            if (it.endsWith('ه')) it.dropLast(1) + "ة" else it
        }).distinct().sortedByDescending { it.length }.take(MAX_TERMS * 2)
        for (term in searchTerms) {
            // Term is stripped to letters/digits, so SQL LIKE metacharacters cannot broaden it.
            messages.searchHistoryCandidates(term, CANDIDATES_PER_TERM).forEach { row ->
                if (sessionId == null || row.sessionId == sessionId) candidates[row.id] = row
            }
        }
        // A session title may carry the topic even when its recent messages use pronouns.
        val titledSessions = LinkedHashMap<Long, ChatSessionEntity>()
        for (term in searchTerms.take(3)) {
            sessions.searchTitles(term, 12).forEach { session ->
                if (sessionId == null || sessionId == session.id) titledSessions[session.id] = session
            }
        }
        titledSessions.values.take(12).forEach { session ->
            messages.getHistoryPageBySession(session.id, null, 5).forEach { row ->
                candidates[row.id] = row
            }
        }
        val sessionCache = mutableMapOf<Long, ChatSessionEntity?>()
        return candidates.values.mapNotNull { row ->
            val session = sessionCache.getOrPut(row.sessionId) { sessions.getById(row.sessionId) }
                ?: return@mapNotNull null
            val score = relevance(terms, row.content, session.title)
            if (score <= 0) null else Hit(session, row, score)
        }.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.message.timestamp })
            .take(limit.coerceIn(1, 20))
    }

    suspend fun page(sessionId: Long, beforeId: Long?, limit: Int): Pair<ChatSessionEntity, List<ChatMessageSource>>? {
        val session = sessions.getById(sessionId) ?: return null
        return session to messages.getHistoryPageBySession(sessionId, beforeId, limit.coerceIn(1, 40))
    }

    suspend fun chunk(sessionId: Long, rowId: Long, offset: Int): ChatMessageChunk? {
        if (sessions.getById(sessionId) == null) return null
        return messages.readHistoryChunk(sessionId, rowId, offset.coerceIn(0, 100_000))
    }

    companion object {
        private const val MAX_TERMS = 6
        private const val CANDIDATES_PER_TERM = 80
        private val DIACRITICS = Regex("[\u064B-\u065F\u0670]")
        private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
        private val STOP = setOf(
            "ايه", "كان", "كنت", "فاكر", "افتكر", "قبل", "كده", "شات", "سيشن",
            "محادثة", "محادثات", "المحادثة", "عن", "على", "في", "من", "اللي",
            "what", "when", "where", "remember", "recall", "history", "chat", "session",
            "the", "and", "for", "with"
        )

        internal fun normalize(text: String): String = text.lowercase()
            .replace(DIACRITICS, "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه')

        internal fun terms(query: String): List<String> = normalize(query)
            .split(SPLIT).filter { it.length >= 2 && it !in STOP }.distinct()

        internal fun relevance(queryTerms: List<String>, content: String, title: String): Int {
            val body = normalize(content.take(4_000))
            val heading = normalize(title)
            val hits = queryTerms.count { term -> body.contains(term) || heading.contains(term) }
            if (hits == 0) return 0
            val exact = normalize(queryTerms.joinToString(" "))
            return hits * 10 + (if (body.contains(exact)) 12 else 0) +
                queryTerms.count { heading.contains(it) } * 3
        }
    }
}
