package com.omnidev.workspace.data.model

import org.junit.Assert.*
import org.junit.Test

class ProfilePersonalizationTest {
    @Test fun optOutRemovesNameBioAndStyleFromPromptsWithoutLosingValues() {
        val saved = ProfilePersonalization(enabled = false, style = ReplyStyle.DETAILED, emoji = ReplyLevel.MORE)
        assertEquals("", saved.prompt("Obieda", "Kotlin developer"))
        assertEquals(saved, ProfilePersonalization.decode(saved.encode()))
        assertTrue(saved.copy(enabled = true).prompt("Obieda", "Kotlin developer").contains("Kotlin developer"))
    }
    @Test fun everyStyleAndLevelRoundTripsByStableName() {
        for (style in ReplyStyle.entries) for (level in ReplyLevel.entries) {
            val p = ProfilePersonalization(style = style, warmth = level, enthusiasm = level, structure = level, emoji = level)
            assertEquals(p, ProfilePersonalization.decode(p.encode()))
        }
    }
    @Test fun corruptOrFutureSettingsKeepLegacyProfileAvailable() {
        for (value in listOf(null, "", "99|true|DIRECT|DEFAULT|DEFAULT|DEFAULT|DEFAULT", "1|yes|missing")) {
            assertEquals(ProfilePersonalization(), ProfilePersonalization.decode(value))
        }
        assertTrue(ProfilePersonalization().prompt("User", "Existing bio").contains("Existing bio"))
    }
    @Test fun defaultEmptyProfileAddsNoTokensAndLargeBioCannotCrowdOutStyle() {
        assertEquals("", ProfilePersonalization().prompt(null, ""))
        val prompt = ProfilePersonalization(style = ReplyStyle.DIRECT, emoji = ReplyLevel.LESS).prompt("X".repeat(1000), "Y".repeat(10000))
        assertTrue(prompt.contains("Be direct and concise."))
        assertTrue(prompt.contains("Emoji: less."))
        assertTrue(prompt.length < 1500)
        assertTrue(prompt.contains("never authorization"))
    }
}
