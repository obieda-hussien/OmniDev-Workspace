package com.omnidev.workspace.data.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class RishCommandReceiptTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun delegatedCommandReportsItsExitAlongsidePartialOutput() {
        val receipt = RishCommandReceipt()
        val (exit, stdout, stderr) = run(receipt.wrap("printf '%s' \"it's partial\"; echo 'read failed' >&2; exit 13"))

        assertEquals(13, exit)
        assertEquals("read failed", stderr.trim())
        assertEquals(RishCommandReceipt.Completed(13, "it's partial"),
            receipt.read(stdout, exit, transportSucceeded = true))
        assertEquals(13, receipt.read(stdout, 0, transportSucceeded = true)?.exitCode)
        assertNull(receipt.read(stdout, 127, transportSucceeded = true))
        assertNull(receipt.read(stdout, 13, transportSucceeded = false))
        assertNull(RishCommandReceipt().read(stdout, 13, transportSucceeded = true))
    }

    @Test fun successKeepsOutputAndStripsReceipt() {
        val receipt = RishCommandReceipt()
        val (exit, stdout) = run(receipt.wrap("printf 'entry\\n'"))

        assertEquals(0, exit)
        assertEquals(RishCommandReceipt.Completed(0, "entry"), receipt.read(stdout, exit, true))
    }

    @Test fun launcherFailureAndAnIncompleteReceiptDoNotProveCompletion() {
        val receipt = RishCommandReceipt()
        assertNull(receipt.read("rish launcher not found", 127, transportSucceeded = true))
        assertNull(receipt.read("incomplete callback", 0, transportSucceeded = true))
    }

    private data class ShellOutput(val exit: Int, val stdout: String, val stderr: String)

    private fun run(command: String): ShellOutput {
        val stdout = files.newFile()
        val stderr = files.newFile()
        val process = ProcessBuilder("sh", "-c", command).redirectOutput(stdout).redirectError(stderr).start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            return ShellOutput(process.exitValue(), stdout.readText(), stderr.readText())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
