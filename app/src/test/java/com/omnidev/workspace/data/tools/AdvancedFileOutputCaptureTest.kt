package com.omnidev.workspace.data.tools

import com.omnidev.workspace.data.ipc.PrivilegedCommandOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedFileOutputCaptureTest {
    @Test fun routerLimitProducesTruncationBelowTheToolLimit() {
        val captured = PrivilegedCommandOutput("x".repeat(8_001)).limitedTo(8_000)

        val result = AdvancedFileTools.formatPrivilegedOutput(captured, headTruncate = true)

        assertEquals(8_000, captured.output.length)
        assertTrue(result.truncated)
        assertFalse(result.isError)
        assertTrue(result.output.contains("TRUNCATED"))
    }

    @Test fun exactlyAtTheRouterLimitStaysComplete() {
        val output = "x".repeat(8_000)
        val captured = PrivilegedCommandOutput(output).limitedTo(8_000)

        val result = AdvancedFileTools.formatPrivilegedOutput(captured, headTruncate = true)

        assertFalse(result.truncated)
        assertEquals(output, result.output)
    }

    @Test fun priorTransportTruncationSurvivesABelowLimitCapture() {
        val captured = PrivilegedCommandOutput("partial data", truncated = true).limitedTo(8_000)

        val result = AdvancedFileTools.formatPrivilegedOutput(captured, headTruncate = false)

        assertTrue(result.truncated)
        assertTrue(result.output.startsWith("partial data"))
        assertTrue(result.output.contains("TRUNCATED"))
    }
}
