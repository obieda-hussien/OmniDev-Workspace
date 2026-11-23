package com.omnidev.workspace.ui.settings

import com.omnidev.workspace.data.db.entities.ChatSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class OmniConnectInboxFilterTest {
    private fun session(id: Long, title: String, source: String, updated: Long) =
        ChatSessionEntity(id = id, title = title, source = source, lastUpdated = updated)

    @Test fun sourceFilterDoesNotMergeSameTitleAcrossChannels() {
        val app = session(1, "Support", ChatSessionEntity.SOURCE_APP, 10)
        val telegram = session(2, "Support", ChatSessionEntity.SOURCE_TELEGRAM, 20)
        assertEquals(listOf(telegram, app), OmniConnectInboxFilter.select(listOf(app, telegram), null, ""))
        assertEquals(listOf(telegram), OmniConnectInboxFilter.select(listOf(app, telegram), ChatSessionEntity.SOURCE_TELEGRAM, ""))
    }

    @Test fun searchIsCaseInsensitiveAndScopedToSource() {
        val a = session(1, "Project Alpha", ChatSessionEntity.SOURCE_APP, 10)
        val b = session(2, "Project Beta", ChatSessionEntity.SOURCE_TELEGRAM, 20)
        assertEquals(listOf(a), OmniConnectInboxFilter.select(listOf(a, b), null, " ALPHA "))
        assertEquals(emptyList<ChatSessionEntity>(), OmniConnectInboxFilter.select(listOf(a, b), ChatSessionEntity.SOURCE_TELEGRAM, "alpha"))
    }

    @Test fun tiesSortByStableSessionIdWithoutDeduplication() {
        val a = session(1, "Same", ChatSessionEntity.SOURCE_APP, 100)
        val b = session(2, "Same", ChatSessionEntity.SOURCE_APP, 100)
        assertEquals(listOf(b, a), OmniConnectInboxFilter.select(listOf(a, b), null, "same"))
    }
    @Test fun arabicVariantsAndDiacriticsMatchWithoutChangingIdentity() {
        val a = session(1, "إدارة المُهام", ChatSessionEntity.SOURCE_APP, 10)
        val b = session(2, "ادارة المهام", ChatSessionEntity.SOURCE_TELEGRAM, 20)
        assertEquals(listOf(b, a), OmniConnectInboxFilter.select(listOf(a, b), null, "ادارة"))
        assertEquals(listOf(a), OmniConnectInboxFilter.select(listOf(a, b), ChatSessionEntity.SOURCE_APP, "إدارة مهام"))
    }

    @Test fun accentInsensitiveMultiTermSearchPreservesSessionRows() {
        val a = session(1, "Résumé Project", ChatSessionEntity.SOURCE_APP, 10)
        val b = session(2, "Project notes", ChatSessionEntity.SOURCE_APP, 20)
        assertEquals(listOf(a), OmniConnectInboxFilter.select(listOf(a, b), null, "project resume"))
    }

}
