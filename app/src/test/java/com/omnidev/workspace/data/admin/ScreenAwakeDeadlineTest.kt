package com.omnidev.workspace.data.admin

import org.junit.Assert.*
import org.junit.Test

class ScreenAwakeDeadlineTest {
    @Test fun displayHoldLastsExactlyTwoMinutes() {
        val lease = ScreenAwakeDeadline()
        lease.start(10_000)
        assertEquals(120_000, lease.remaining(10_000))
        assertEquals(1, lease.remaining(129_999))
        assertEquals(0, lease.remaining(130_000))
        assertEquals(0, lease.remaining(150_000))
    }
    @Test fun authenticationHandoffAndRecreationCannotExtendTheTimer() {
        val lease = ScreenAwakeDeadline()
        lease.start(10_000)
        lease.start(50_000)
        lease.start(80_000)
        assertEquals(50_000, lease.remaining(80_000))
        lease.start(150_000)
        assertEquals(0, lease.remaining(150_000))
    }
    @Test fun explicitNewInvocationGetsANewBoundedWindow() {
        val lease = ScreenAwakeDeadline()
        lease.start(0)
        lease.start(130_000, newInvocation = true)
        assertEquals(120_000, lease.remaining(130_000))
        assertEquals(0, lease.remaining(250_000))
    }
    @Test fun powerOffOrRevocationCancelsWithoutHandoffReacquisition() {
        val lease = ScreenAwakeDeadline()
        lease.start(10_000)
        lease.cancel()
        lease.start(20_000)
        assertEquals(0, lease.remaining(20_000))
        lease.start(25_000, newInvocation = true)
        assertEquals(120_000, lease.remaining(25_000))
    }
    @Test fun releaseBeforeFirstActivationDoesNotBlockTheFirstSession() {
        val lease = ScreenAwakeDeadline()
        lease.cancel()
        lease.start(10_000)
        assertEquals(120_000, lease.remaining(10_000))
    }
}
