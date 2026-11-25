package com.omnidev.workspace.data.tools

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeShellPipelineTest {
    @Test fun upstreamFailureRemainsVisibleAfterSuccessfulFilter() {
        val (exit, output) = run("""["printf 'partial'; exit 17", "cat"]""")
        assertEquals(17, exit)
        assertEquals("partial", output)
    }

    @Test fun supportsEscapedQuotesCommasAndStageComments() {
        val (exit, output) = run("""["printf '%s' \"a,b\" # stage comment", "tr a-z A-Z"]""")
        assertEquals(0, exit)
        assertEquals("A,B", output)
    }

    @Test fun aSingleStagePreservesItsExitStatus() {
        assertEquals(7, run("""["exit 7"]""").first)
    }

    @Test fun rejectsMalformedOrNonStringStagesBeforeExecution() {
        val invalid = listOf(
            "", "echo bare", """["echo ok",]""", "{}", "[]", """["echo ok", " "]""",
            """["echo ok", false]""", """[null]""", """[{"command":"echo ok"}]"""
        )
        invalid.forEach { assertNull(it, RuntimeShellPipeline.script(it)) }
    }

    private fun run(pipelineJson: String): Pair<Int, String> {
        val script = RuntimeShellPipeline.script(pipelineJson)
        assertNotNull(script)
        val process = ProcessBuilder("bash", "-c", script!!).redirectErrorStream(true).start()
        try {
            assertTrue("Bash did not finish in time", process.waitFor(10, TimeUnit.SECONDS))
            return process.exitValue() to process.inputStream.bufferedReader().use { it.readText() }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
