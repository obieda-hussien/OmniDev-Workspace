package com.omnidev.workspace.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlannerToolTest {
    @Test fun `alarm request rejects past and distant dates`() {
        val now = 1_800_000_000_000L
        assertEquals("Alarm time must be in the future (epoch milliseconds).", PlannerTool.alarmTimeError(now, now))
        assertNull(PlannerTool.alarmTimeError(now + 60_000, now))
        assertEquals(
            "Android's alarm app Intent only accepts hour and minute, not a calendar date. Choose a time within the next 24 hours.",
            PlannerTool.alarmTimeError(now + 2 * 24 * 60 * 60_000L, now)
        )
    }
}
