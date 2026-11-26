package com.omnidev.workspace.data.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class AdvancedFileHexDumpTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun fallbackReadFailureSurvivesSuccessfulFormatter() = runBlocking {
        fake("dd", "printf 'partial'; echo 'dd: Permission denied' >&2; exit 13")
        formatter()

        val result = dump()

        assertTrue(result.isError)
        assertEquals(13, result.exitCode)
        assertTrue(result.output.contains("Permission denied"))
        assertTrue(result.output.contains("partial"))
    }

    @Test fun preferredFormatterKeepsFailureDiagnostic() = runBlocking {
        fake("xxd", "echo 'xxd: Permission denied' >&2; exit 2")

        val result = dump()

        assertTrue(result.isError)
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("Permission denied"))
    }

    @Test fun fallbackAllowsSuccessfulReadWithRequestedBounds() = runBlocking {
        fake("dd", """[ "$3" = "skip=2" ] && [ "$4" = "count=4" ] || exit 42; printf 'data'""")
        formatter()

        val result = dump()

        assertFalse(result.isError)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.endsWith("data"))
    }

    private fun formatter() = fake("cat", "while IFS= read -r line || [ -n \"\$line\" ]; do printf '%s\\n' \"\$line\"; done")

    private fun fake(name: String, command: String) {
        files.newFile(name).apply {
            writeText("#!/bin/sh\n$command\n")
            assertTrue(setExecutable(true))
        }
    }

    private suspend fun dump(): ToolExecutionResult {
        val source = files.newFile("source").apply { writeText("abcdef") }
        return AdvancedFileTools.hexDump(mapOf("path" to source.absolutePath, "bytes" to "4", "offset" to "2")) { script ->
            val output = files.newFile()
            val runner = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).redirectOutput(output)
            runner.environment()["PATH"] = files.root.absolutePath
            val process = runner.start()
            try {
                assertTrue("hex dump exceeded its deadline", process.waitFor(10, TimeUnit.SECONDS))
                val exit = process.exitValue()
                ToolExecutionResult(output.readText().trim(), isError = exit != 0, exitCode = exit)
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }
}
