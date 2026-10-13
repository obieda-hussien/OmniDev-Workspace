package com.omnidev.workspace.data.debug

import org.junit.Assert.*
import org.junit.Test

class MainThreadStallTrackerTest {
    @Test fun reportsOnceAndRearmsOnlyAfterRecovery() {
        val tracker = MainThreadStallTracker(100)
        assertTrue(tracker.enqueue(10))
        assertFalse(tracker.enqueue(50))
        assertFalse(tracker.shouldReport(109, false))
        assertTrue(tracker.shouldReport(110, false))
        assertFalse(tracker.shouldReport(10_000, false))
        tracker.acknowledge()
        assertFalse(tracker.shouldReport(20_000, false))
        assertTrue(tracker.enqueue(20_000))
        assertTrue(tracker.shouldReport(20_100, false))
    }

    @Test fun debuggerPauseDoesNotConsumeReport() {
        val tracker = MainThreadStallTracker(100)
        tracker.enqueue(0)
        assertFalse(tracker.shouldReport(200, true))
        assertTrue(tracker.shouldReport(201, false))
    }
}
