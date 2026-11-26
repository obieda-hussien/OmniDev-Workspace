package com.omnidev.workspace.data.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class AdvancedFileDiffTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun diffReadErrorIsAnExecutionFailure() = runBlocking {
        files.newFile("left")
        files.newFile("right")
        files.newFile("diff").apply {
            writeText("#!/bin/sh\necho 'diff: right: Permission denied' >&2\nexit 2\n")
            assertTrue(setExecutable(true))
        }

        val result = diff("left", "right")

        assertTrue(result.isError)
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("Permission denied"))
    }

    @Test fun identicalInputsReturnAnExplicitResult() = runBlocking {
        files.newFile("-left's")
        files.newFile("right")

        val result = diff("-left's", "right")

        assertFalse(result.isError)
        assertEquals("No differences found.", result.output)
    }

    @Test fun longDirectoryDiffSucceedsWithTruncationMetadata() = runBlocking {
        val left = files.newFolder("left", "nested").resolve("data.txt")
        val right = files.newFolder("right", "nested").resolve("data.txt")
        left.writeText("before\n".repeat(100))
        right.writeText("after\n".repeat(100))

        val result = diff("left", "right")

        assertFalse(result.isError)
        assertTrue(result.truncated)
        assertEquals(10, result.output.lineSequence().count())
        assertTrue(result.output.contains("nested/data.txt"))
    }

    private suspend fun diff(left: String, right: String) = AdvancedFileTools.diffFiles(
        mapOf("path_a" to left, "path_b" to right, "max_lines" to "10")
    ) { script ->
        val output = files.newFile()
        val runner = ProcessBuilder("bash", "-c", script)
            .directory(files.root)
            .redirectErrorStream(true)
            .redirectOutput(output)
        runner.environment().computeIfPresent("PATH") { _, path -> "${files.root.absolutePath}:$path" }
        val process = runner.start()
        try {
            assertTrue("diff exceeded its deadline", process.waitFor(10, TimeUnit.SECONDS))
            val exitCode = process.exitValue()
            ToolExecutionResult(output.readText().trim(), isError = exitCode != 0, exitCode = exitCode)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
