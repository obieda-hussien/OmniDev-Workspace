package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.db.entities.KnowledgeSnippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridMemorySearchEngineTest {

    @Test
    fun arabicQueryRetrievesArabicPreference() {
        val target = KnowledgeSnippet(
            id = 1,
            category = "user_preference",
            content = "المستخدم يفضل تطوير تطبيقات اندرويد باستخدام كوتلن و Jetpack Compose",
            tags = "android,kotlin,compose"
        )
        val noise = KnowledgeSnippet(
            id = 2,
            category = "general",
            content = "المستخدم يحب لعب الدومينو في وقت الفراغ",
            tags = "games"
        )

        val matches = HybridMemorySearchEngine.rank(
            query = "ايه تفضيلات المستخدم في برمجة اندرويد بكوتلن؟",
            corpus = listOf(noise, target),
            topK = 2
        )

        assertTrue(matches.isNotEmpty())
        assertEquals(target.id, matches.first().snippet.id)
    }

    @Test
    fun englishQueryCanRetrieveMixedLanguageMemory() {
        val target = KnowledgeSnippet(
            id = 10,
            category = "architecture",
            content = "OmniDev يستخدم Jetpack Compose للواجهات و Kotlin في Android",
            tags = "android,architecture,compose,kotlin"
        )
        val other = KnowledgeSnippet(
            id = 11,
            category = "general",
            content = "Coffee preference is black coffee",
            tags = "coffee"
        )

        val matches = HybridMemorySearchEngine.rank(
            query = "Android UI architecture with Jetpack Compose and Kotlin",
            corpus = listOf(other, target),
            topK = 2
        )

        assertTrue(matches.isNotEmpty())
        assertEquals(target.id, matches.first().snippet.id)
    }

    @Test
    fun tagsBoostRelevantMemory() {
        val tagged = KnowledgeSnippet(
            id = 20,
            category = "general",
            content = "Use the preferred mobile UI stack",
            tags = "jetpack compose,kotlin,android"
        )
        val untagged = KnowledgeSnippet(
            id = 21,
            category = "general",
            content = "Use the preferred mobile UI stack",
            tags = ""
        )

        val matches = HybridMemorySearchEngine.rank(
            query = "jetpack compose android",
            corpus = listOf(untagged, tagged),
            topK = 2
        )

        assertEquals(tagged.id, matches.first().snippet.id)
        assertTrue(matches.first().score >= matches.last().score)
    }
}
