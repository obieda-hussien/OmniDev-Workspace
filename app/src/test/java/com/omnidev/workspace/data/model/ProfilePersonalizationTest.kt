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

    @Test fun legacyVersionMigratesWithoutResettingTone() {
        val p = ProfilePersonalization.decode("1|true|DIRECT|MORE|LESS|DEFAULT|LESS")
        assertEquals(ReplyStyle.DIRECT, p.style)
        assertEquals(ReplyLevel.MORE, p.warmth)
        assertTrue(p.memoryEnabled)
        assertEquals(p, ProfilePersonalization.decode(p.encode()))
    }
    @Test fun textDelimitersUnicodeAndNewlinesRoundTrip() {
        val p = ProfilePersonalization(occupation = "Developer | designer", customInstructions = "مرحبا 👋\nUse | literally",
            quickAnswers = true, suggestedPrompts = false, richResponses = false, memoryEnabled = false, referenceChatHistory = false)
        assertEquals(p, ProfilePersonalization.decode(p.encode()))
    }
    @Test fun behaviorControlsSurviveProfileOptOutWithoutLeakingPersonalFields() {
        val p = ProfilePersonalization(enabled = false, occupation = "Private role", customInstructions = "Private instruction",
            quickAnswers = true, richResponses = false, memoryEnabled = false, referenceChatHistory = false)
        val prompt = p.prompt("Private name", "Private bio")
        assertFalse(prompt.contains("Private"))
        assertTrue(prompt.contains("short, direct"))
        assertTrue(prompt.contains("Saved memory is disabled"))
        assertTrue(prompt.contains("plain prose"))
    }
    @Test fun disablingMemoryBlocksAllFactAliasesButKeepsSkillsAndDeletionAvailable() {
        val p = ProfilePersonalization(memoryEnabled = false)
        for (tool in listOf("remember_fact", "search_knowledge", "vector_store", "vector_search", "vector_similar"))
            assertFalse(p.permitsMemoryTool(tool, mapOf("content" to "fact", "query" to "my preferences")))
        assertTrue(p.permitsMemoryTool("search_knowledge", mapOf("query" to " SKILL:kotlin ")))
        assertTrue(p.permitsMemoryTool("search_knowledge", mapOf("query" to "skills")))
        assertTrue(p.permitsMemoryTool("remember_fact", mapOf("category" to "agent_skill")))
        assertTrue(p.permitsMemoryTool("delete_memory", emptyMap()))
        assertTrue(p.permitsMemoryTool("update_memory", emptyMap()))
    }
    @Test fun customInstructionBudgetFitsAgentAndTeamContext() {
        val prompt = ProfilePersonalization(style = ReplyStyle.DETAILED, occupation = "O".repeat(1000),
            customInstructions = "C".repeat(10000), quickAnswers = true, memoryEnabled = false,
            richResponses = false, referenceChatHistory = false).prompt("N".repeat(1000), "B".repeat(10000))
        assertTrue(prompt.contains("C".repeat(2400)))
        assertTrue(prompt.contains("B".repeat(900)))
        assertTrue(prompt.length < 5000)
    }
}
