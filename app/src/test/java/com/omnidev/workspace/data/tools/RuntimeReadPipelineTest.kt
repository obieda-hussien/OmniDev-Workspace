package com.omnidev.workspace.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class RuntimeReadPipelineTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun failedListingPreservesExitStatusAndStderr() {
        val result = run(RuntimeReadPipeline.read("ls ./missing-directory", 200))

        assertNotEquals(0, result.exitCode)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.contains("missing-directory"))
    }

    @Test
    fun permissionDeniedFromListingIsARealFailure() {
        val directory = files.newFolder("restricted")
        assertTrue(directory.setReadable(false, false))
        try {
            assumeFalse("The test account can bypass file permissions", Files.isReadable(directory.toPath()))
            val pipeline = RuntimeReadPipeline.read("LC_ALL=C ls ./restricted", 200)
            val result = run(pipeline)

            assertNotEquals(0, result.exitCode)
            assertEquals("", result.stdout)
            assertEquals("FILESYSTEM_PERMISSION_DENIED", pipeline.failureClassification(result.stderr))
        } finally {
            directory.setReadable(true, true)
        }
    }

    @Test
    fun longOutputIsDrainedAndOnlyTheRequestedLinesAreReturned() {
        val pipeline = RuntimeReadPipeline.read("seq 1 100000", 200)
        val result = run(pipeline)

        assertEquals(0, result.exitCode)
        assertEquals((1..201).toList(), result.stdout.lineSequence().filter(String::isNotEmpty).map(String::toInt).toList())
        assertEquals((1..200).joinToString("\n", postfix = "\n"), pipeline.limitStdout(result.stdout))
        assertEquals("", result.stderr)
    }

    @Test
    fun stderrIsIndependentlyLimitedAndNeverAppearsInStdout() {
        val pipeline = RuntimeReadPipeline.read("{ printf 'data\\n'; seq 1 20000 >&2; }", 2)
        val result = run(pipeline)

        assertEquals(0, result.exitCode)
        assertEquals("data\n", result.stdout)
        assertEquals((1..101).joinToString("\n", postfix = "\n"), result.stderr)
        assertEquals((1..100).joinToString("\n", postfix = "\n"), pipeline.limitStderr(result.stderr))
    }

    @Test
    fun grepNoMatchRetainsItsExitCodeAndIsNotAnExecutionError() {
        files.newFile("source").writeText("present\n")
        val pipeline = RuntimeReadPipeline.search("grep -n -- absent source", 100)
        val result = run(pipeline)

        assertEquals(1, result.exitCode)
        assertTrue(pipeline.isNoMatch(result.exitCode, result.stdout, result.stderr))
    }

    @Test
    fun grepErrorKeepsItsDiagnostic() {
        val pipeline = RuntimeReadPipeline.search("grep -n -- anything missing", 100)
        val result = run(pipeline)

        assertTrue(result.exitCode > 1)
        assertTrue(result.stderr.contains("missing"))
        assertFalse(pipeline.isNoMatch(result.exitCode, result.stdout, result.stderr))
    }

    @Test
    fun filteredReadDoesNotMistakeUpstreamExitOneForAnEmptyMatch() {
        val pipeline = RuntimeReadPipeline.filter("(exit 1)", "grep -i -- absent", 60)
        val result = run(pipeline)

        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("stage 1 failed"))
        assertFalse(pipeline.isNoMatch(result.exitCode, result.stdout, result.stderr))
    }

    @Test
    fun filteredReadPreservesUpstreamPartialOutputAndExitCode() {
        val pipeline = RuntimeReadPipeline.filter(
            "{ printf 'match\\n'; printf 'read failed\\n' >&2; exit 23; }", "grep -i -- match", 60
        )
        val result = run(pipeline)

        assertEquals(23, result.exitCode)
        assertEquals("match\n", result.stdout)
        assertTrue(result.stderr.contains("read failed"))
    }

    @Test
    fun exactLineLimitAndFinalLineWithoutNewlineAreRetained() {
        val pipeline = RuntimeReadPipeline.read("printf 'first\\nlast'", 2)
        val result = run(pipeline)

        assertEquals(0, result.exitCode)
        assertEquals("first\nlast", pipeline.limitStdout(result.stdout))
        assertEquals("first\nlast\n", pipeline.limitStdout("first\nlast\n"))
        assertEquals("first\nlast\n", pipeline.limitStdout("first\nlast\nextra"))
    }

    @Test
    fun limiterFailureCannotLookLikeAnEmptyGrepResult() {
        val pipeline = RuntimeReadPipeline.search("grep -n -- absent /dev/null", 100)
        val result = run(pipeline, setup = "sed() { cat >/dev/null; return 1; }\n")

        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("limiter failed"))
        assertFalse(pipeline.isNoMatch(result.exitCode, result.stdout, result.stderr))
    }

    private fun run(pipeline: RuntimeReadPipeline, setup: String = ""): ShellResult {
        val stdout = files.newFile()
        val stderr = files.newFile()
        val process = ProcessBuilder("bash", "-e", "-c", setup + pipeline.script)
            .directory(files.root)
            .redirectOutput(stdout)
            .redirectError(stderr)
            .start()
        try {
            assertTrue("Bash read exceeded its deadline", process.waitFor(10, TimeUnit.SECONDS))
            return ShellResult(process.exitValue(), stdout.readText(), stderr.readText())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)
}
