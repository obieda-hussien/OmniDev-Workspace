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
        lease.start(130_000, ScreenAwakeDeadline.Origin.ASSISTANT_INVOCATION)
        assertEquals(120_000, lease.remaining(130_000))
        assertEquals(0, lease.remaining(250_000))
    }
    @Test fun powerOffOrRevocationCancelsWithoutHandoffReacquisition() {
        val lease = ScreenAwakeDeadline()
        lease.start(10_000)
        lease.cancel()
        lease.start(20_000)
        assertEquals(0, lease.remaining(20_000))
        lease.start(25_000, ScreenAwakeDeadline.Origin.ASSISTANT_INVOCATION)
        assertEquals(120_000, lease.remaining(25_000))
    }
    @Test fun releaseBeforeFirstActivationDoesNotBlockTheFirstSession() {
        val lease = ScreenAwakeDeadline()
        lease.cancel()
        lease.start(10_000)
        assertEquals(120_000, lease.remaining(10_000))
    }
    @Test fun standaloneRequestAfterReleaseGetsItsOwnTwoMinutes() {
        val lease = ScreenAwakeDeadline()
        lease.start(10_000, ScreenAwakeDeadline.Origin.STANDALONE_REQUEST)
        lease.cancel()
        lease.start(20_000, ScreenAwakeDeadline.Origin.STANDALONE_REQUEST)
        assertEquals(120_000, lease.remaining(20_000))
        // Activity creation and a rotation keep the request's deadline.
        lease.start(20_050)
        lease.start(80_000)
        assertEquals(60_000, lease.remaining(80_000))
        assertEquals(0, lease.remaining(140_000))
    }
    @Test fun standaloneRequestAfterExpiryStartsAgainWithoutProcessRestart() {
        val lease = ScreenAwakeDeadline()
        lease.start(10_000, ScreenAwakeDeadline.Origin.STANDALONE_REQUEST)
        assertEquals(0, lease.remaining(130_000))
        lease.start(150_000, ScreenAwakeDeadline.Origin.STANDALONE_REQUEST)
        assertEquals(120_000, lease.remaining(150_000))
        assertEquals(0, lease.remaining(270_000))
    }
    @Test fun standaloneRequestReplacesAnEarlierStillActiveLease() {
        val lease = ScreenAwakeDeadline()
        lease.start(10_000, ScreenAwakeDeadline.Origin.ASSISTANT_INVOCATION)
        lease.start(100_000, ScreenAwakeDeadline.Origin.STANDALONE_REQUEST)
        assertEquals(120_000, lease.remaining(100_000))
    }
    @Test fun assistantAndVoiceHandoffsKeepTheOriginalActivationDeadline() {
        val lease = ScreenAwakeDeadline()
        lease.start(10_000, ScreenAwakeDeadline.Origin.ASSISTANT_INVOCATION)
        lease.start(40_000, ScreenAwakeDeadline.Origin.HOST_HANDOFF)
        lease.start(60_000, ScreenAwakeDeadline.Origin.HOST_HANDOFF)
        assertEquals(70_000, lease.remaining(60_000))
        lease.start(140_000, ScreenAwakeDeadline.Origin.HOST_HANDOFF)
        assertEquals(0, lease.remaining(140_000))
    }
    @Test fun cancellationCannotBeUndoneByARecreatedHost() {
        val lease = ScreenAwakeDeadline()
        lease.start(10_000, ScreenAwakeDeadline.Origin.STANDALONE_REQUEST)
        lease.cancel()
        lease.start(20_000, ScreenAwakeDeadline.Origin.HOST_HANDOFF)
        assertEquals(0, lease.remaining(20_000))
    }
}
