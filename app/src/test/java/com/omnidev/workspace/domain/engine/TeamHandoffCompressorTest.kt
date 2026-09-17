package com.omnidev.workspace.domain.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamHandoffCompressorTest {

    @Test
    fun `compression keeps failures verification and paths`() {
        val raw = buildString {
            appendLine("Long worker narrative that is not especially important. ".repeat(80))
            appendLine("ERROR: Room migration failed with FOREIGN KEY constraint")
            appendLine("Changed app/src/main/java/com/example/ChatDao.kt")
            appendLine("Tests passed: ChatMigrationTest")
            appendLine("Verification: exit_code: 0")
        }

        val compact = TeamHandoffCompressor.compact(raw, 1_600)

        assertTrue(compact.contains("FOREIGN KEY"))
        assertTrue(compact.contains("ChatDao.kt"))
        assertTrue(compact.contains("Tests passed"))
        assertTrue(compact.contains("exit_code: 0"))
        assertTrue(compact.length <= 1_600)
    }

    @Test
    fun `secrets are redacted before handoff`() {
        val raw = "Verification succeeded token=super-secret-123 and api_key:abcd1234"
        val compact = TeamHandoffCompressor.compact(raw, 2_000)

        assertFalse(compact.contains("super-secret-123"))
        assertFalse(compact.contains("abcd1234"))
        assertTrue(compact.contains("[REDACTED]"))
    }

    @Test
    fun `duplicate evidence lines are removed`() {
        val raw = listOf(
            "ERROR: build failed at app/src/main/Foo.kt:123",
            "ERROR: build failed at app/src/main/Foo.kt:456",
            "ERROR: build failed at app/src/main/Foo.kt:789",
            "Decision: patch Foo.kt and rerun tests"
        ).joinToString("\n")

        val compact = TeamHandoffCompressor.compact(raw, 2_000)
        val errorCount = compact.lineSequence().count { it.startsWith("ERROR:") }

        assertTrue(errorCount <= 2)
        assertTrue(compact.contains("Decision:"))
    }
}
