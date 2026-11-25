package com.omnidev.workspace.data.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class AdvancedFileFindTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun missingDirectoryIsAnExecutionFailure() = runBlocking {
        val result = find("missing")

        assertTrue(result.isError)
        assertTrue(result.output.contains("missing"))
        assertFalse(result.output.startsWith("NO_MATCHES"))
    }

    @Test
    fun longListingSucceedsWithTruncationMetadata() = runBlocking {
        repeat(250) { files.newFile("entry-$it.txt") }

        val result = find(".")

        assertFalse(result.isError)
        assertTrue(result.truncated)
        assertEquals(200, result.output.lineSequence().count())
    }

    @Test
    fun emptyListingKeepsNoMatchSemantics() = runBlocking {
        val result = find(".")

        assertFalse(result.isError)
        assertFalse(result.truncated)
        assertTrue(result.output.startsWith("NO_MATCHES"))
    }

    private suspend fun find(directory: String) = AdvancedFileTools.findFiles(
        mapOf("directory" to directory, "name_pattern" to "*.txt")
    ) { script ->
        val output = files.newFile()
        val process = ProcessBuilder("bash", "-c", script)
            .directory(files.root)
            .redirectErrorStream(true)
            .redirectOutput(output)
            .start()
        try {
            assertTrue("find script exceeded its deadline", process.waitFor(10, TimeUnit.SECONDS))
            ToolExecutionResult(output.readText().trim(), isError = process.exitValue() != 0)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
