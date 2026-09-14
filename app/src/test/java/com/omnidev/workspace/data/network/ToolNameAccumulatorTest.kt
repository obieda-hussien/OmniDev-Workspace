package com.omnidev.workspace.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolNameAccumulatorTest {
    @Test fun repeatedFullNamesDoNotMultiply() {
        val result = List(1000) { "privileged_tool" }.fold("", ::mergeToolName)
        assertEquals("privileged_tool", result)
    }

    @Test fun fragmentsAndCumulativeNamesAreAccepted() {
        assertEquals("task_scheduler", listOf("task_", "scheduler", "task_scheduler", "").fold("", ::mergeToolName))
        assertEquals("read_file_lines", listOf("read", "read_file", "read_file_lines").fold("", ::mergeToolName))
    }

    @Test fun independentCallsDoNotShareState() {
        assertEquals("file_info", mergeToolName("file_", "info"))
        assertEquals("run_terminal", mergeToolName("run_", "terminal"))
    }
}
