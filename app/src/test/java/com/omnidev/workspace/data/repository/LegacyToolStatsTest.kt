package com.omnidev.workspace.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyToolStatsTest {
    @Test fun restoresDataClassStringWrittenByOldVersions() {
        val stats = parseLegacyToolStats("read_file_lines",
            "ToolStats(toolName=read_file_lines, executionCount=276, successCount=238, totalDurationMs=9000)")
        assertEquals(ToolStats("read_file_lines", 276, 238, 9000), stats)
    }

    @Test fun numericCountsDoNotInventSuccesses() {
        assertEquals(ToolStats("read_file_lines", 42), parseLegacyToolStats("read_file_lines", 42L))
    }

    @Test fun corruptedValuesDoNotInventCounts() {
        assertEquals(0L, parseLegacyToolStats("x", "garbage").executionCount)
        assertEquals(0L, parseLegacyToolStats("x", -1).executionCount)
    }
}
