package com.omnidev.workspace.data.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RishFailureClassifierTest {

    @Test
    fun `shell uid smoke test is ready`() {
        val state = RishFailureClassifier.classify(
            exitCode = 0,
            output = "uid=2000(shell) gid=2000(shell) groups=2000(shell)"
        )
        assertEquals(RishRuntimeHealth.State.READY, state)
    }

    @Test
    fun `root uid smoke test is ready`() {
        val state = RishFailureClassifier.classify(
            exitCode = 0,
            output = "uid=0(root) gid=0(root)"
        )
        assertEquals(RishRuntimeHealth.State.READY, state)
    }

    @Test
    fun `app uid with exit zero is not ready`() {
        val output = "uid=10221(u0_a221) gid=10221(u0_a221) context=u:r:untrusted_app_27:s0"
        assertFalse(RishFailureClassifier.looksLikePrivilegedId(output))
        assertEquals(
            RishRuntimeHealth.State.EXECUTION_FAILED,
            RishFailureClassifier.classify(0, output)
        )
    }

    @Test
    fun `librish unsatisfied link error is classified and trips guardrail`() {
        val output = """
java.lang.reflect.InvocationTargetException
Caused by: java.lang.UnsatisfiedLinkError: DexClassLoader couldn't find "librish.so"
""".trimIndent()
        val state = RishFailureClassifier.classify(1, output)
        assertEquals(RishRuntimeHealth.State.NATIVE_LIBRARY_LOAD_FAILURE, state)
        val remediation = RishFailureClassifier.remediation(state)
        assertTrue(remediation.contains("do NOT copy librish.so"))
        assertTrue(remediation.contains("LD_LIBRARY_PATH"))
    }

    @Test
    fun `broken bin rish directory is classified`() {
        val state = RishFailureClassifier.classify(
            exitCode = 64,
            output = "OMNIDEV_RISH_LAYOUT_ERROR: /data/data/com.termux/files/usr/bin/rish is a directory"
        )
        assertEquals(RishRuntimeHealth.State.TERMUX_LAYOUT_BROKEN, state)
    }

    @Test
    fun `missing launcher is command not found`() {
        assertEquals(
            RishRuntimeHealth.State.COMMAND_NOT_FOUND,
            RishFailureClassifier.classify(127, "rish: command not found")
        )
    }

    @Test
    fun `cross sandbox permission denial is classified`() {
        val output = "/system/bin/sh: /data/user/0/com.omnidev.workspace/files/rish: Permission denied"
        assertEquals(
            RishRuntimeHealth.State.CROSS_SANDBOX_PERMISSION_FAILURE,
            RishFailureClassifier.classify(1, output)
        )
    }

    @Test
    fun `android fourteen writable dex failure is classified`() {
        val output = "On Android 14+, app_process cannot load writable dex. Cannot remove the write permission of rish_shizuku.dex."
        assertEquals(
            RishRuntimeHealth.State.DEX_PERMISSION_FAILURE,
            RishFailureClassifier.classify(1, output)
        )
    }

    @Test
    fun `termux transport opt in failure is not reported as rish failure`() {
        val output = "RunCommandService requires allow-external-apps property to be set to true"
        assertEquals(
            RishRuntimeHealth.State.TERMUX_UNAVAILABLE,
            RishFailureClassifier.classify(-1, output, transportSucceeded = false)
        )
    }
}
