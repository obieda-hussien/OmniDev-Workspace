package com.omnidev.workspace.data.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class AdvancedFileDiskUsageTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun missingPathFailsWithDiagnostic() = runBlocking {
        val result = measure("missing")

        assertTrue(result.isError)
        assertTrue(result.output.contains("missing"))
    }

    @Test fun traversalErrorSurvivesPartialOutputAndSorting() = runBlocking {
        files.newFile("du").apply {
            writeText("#!/bin/sh\nprintf '8K\\tvisible.txt\\n'\necho 'du: private: Permission denied' >&2\nexit 13\n")
            assertTrue(setExecutable(true))
        }

        val result = measure(".")

        assertTrue(result.isError)
        assertEquals(13, result.exitCode)
        assertTrue(result.output.contains("Permission denied"))
        assertTrue(result.output.contains("visible.txt"))
    }

    @Test fun longReportKeepsItsSummaryAndMarksTruncation() = runBlocking {
        val directory = files.newFolder("-storage")
        repeat(70) { directory.resolve("item-$it").mkdir() }

        val result = measure(directory.name)

        assertFalse(result.isError)
        assertTrue(result.truncated)
        assertEquals(63, result.output.lineSequence().count())
        assertTrue(result.output.contains("Filesystem:"))
    }

    private suspend fun measure(path: String) =
        AdvancedFileTools.diskUsage(mapOf("path" to path)) { script ->
            val output = files.newFile()
            val runner = ProcessBuilder("bash", "-c", script)
                .directory(files.root)
                .redirectErrorStream(true)
                .redirectOutput(output)
            val environment = runner.environment()
            environment["PATH"] = "${files.root.absolutePath}:${environment.getValue("PATH")}"
            val process = runner.start()
            try {
                assertTrue("disk usage exceeded its deadline", process.waitFor(10, TimeUnit.SECONDS))
                val exitCode = process.exitValue()
                ToolExecutionResult(output.readText().trim(), isError = exitCode != 0, exitCode = exitCode)
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
}
