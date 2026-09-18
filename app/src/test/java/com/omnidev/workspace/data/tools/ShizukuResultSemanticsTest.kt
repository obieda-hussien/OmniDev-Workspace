package com.omnidev.workspace.data.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuResultSemanticsTest {

    @Test
    fun `legacy partial result is never useful success evidence`() {
        val result = ShizukuResult.PartialSuccess(
            output = "some stdout despite failure",
            exitCode = 1
        )

        assertFalse(result.hasUsefulOutput())
        assertNull(result.outputOrNull())
        assertTrue(result.toDisplayString().startsWith("Error:"))
    }
}
