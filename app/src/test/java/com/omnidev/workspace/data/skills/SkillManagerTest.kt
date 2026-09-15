package com.omnidev.workspace.data.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillManagerTest {

    @Test
    fun parseDocument_acceptsValidSkill() {
        val result = SkillManager.parseDocument(
            """
            ---
            name: test-skill
            description: Use for deterministic test workflows.
            ---

            # Test Skill
            Verify the artifact before reporting success.
            """.trimIndent()
        )

        assertTrue(result.isSuccess)
        assertEquals("test-skill", result.getOrThrow().name)
    }

    @Test
    fun parseDocument_acceptsFoldedDescription() {
        val result = SkillManager.parseDocument(
            """
            ---
            name: folded-skill
            description: >
              Use when a workflow needs
              multiple validation stages.
            ---

            # Folded
            Run all required checks.
            """.trimIndent()
        )

        assertTrue(result.isSuccess)
        assertEquals("Use when a workflow needs multiple validation stages.", result.getOrThrow().description)
    }

    @Test
    fun parseDocument_rejectsUnsafeName() {
        val result = SkillManager.parseDocument(
            """
            ---
            name: ../escape
            description: Invalid path attempt.
            ---

            Body.
            """.trimIndent()
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun parseDocument_requiresInstructionBody() {
        val result = SkillManager.parseDocument(
            """
            ---
            name: empty-skill
            description: Has no body.
            ---
            """.trimIndent()
        )

        assertTrue(result.isFailure)
    }
}
