package com.omnidev.workspace.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedChatRecallTest {
    @Test
    fun arabicVariantsAndRecallFillerDoNotHideTheSubject() {
        assertEquals(listOf("اللعبه", "اوفرلاين"), GroundedChatRecall.terms("فاكر اللعبة أوفَرلاين؟"))
        assertTrue(
            GroundedChatRecall.relevance(
                listOf("اللعبه", "اوفرلاين"),
                "خلينا اللعبة أوفَرلاين دلوقتي",
                "مش نفس القصة"
            ) > 0
        )
    }

    @Test
    fun unrelatedMessageCannotBeUsedAsRecallEvidence() {
        val terms = GroundedChatRecall.terms("Blender render")
        assertEquals(0, GroundedChatRecall.relevance(terms, "We discussed Gradle CI", "AndroidIDE"))
        assertTrue(
            GroundedChatRecall.relevance(terms, "Open Blender and run a render", "Game assets") >
                GroundedChatRecall.relevance(terms, "Open Blender", "Game assets")
        )
    }
}
