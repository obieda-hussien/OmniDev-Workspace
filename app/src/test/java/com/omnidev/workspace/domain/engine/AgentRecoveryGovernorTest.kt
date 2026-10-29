package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.domain.engine.AgentRecoveryGovernor.Decision
import com.omnidev.workspace.domain.engine.AgentRecoveryGovernor.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRecoveryGovernorTest {
    @Test fun `each kind recovers up to its limit and then gives up`() {
        val governor = AgentRecoveryGovernor()
        repeat(3) { assertTrue(governor.decide(Kind.EMPTY_RESPONSE) is Decision.Recover) }
        assertEquals(Decision.GiveUp(3), governor.decide(Kind.EMPTY_RESPONSE))
    }

    @Test fun `recovery messages escalate with the attempt number`() {
        val governor = AgentRecoveryGovernor()
        val first = governor.decide(Kind.UNRESOLVED_FAILURE, "HTTP 500") as Decision.Recover
        assertTrue(first.message.contains("HTTP 500"))
        assertTrue(first.message.contains("Fix the call"))
        val second = AgentRecoveryGovernor(mapOf(Kind.UNRESOLVED_FAILURE to 2)).also { it.decide(Kind.UNRESOLVED_FAILURE, "x") }
            .decide(Kind.UNRESOLVED_FAILURE, "x") as Decision.Recover
        assertTrue(second.message.contains("materially different approach"))
    }

    @Test fun `progress starts a fresh episode so long tasks never run out of recoveries`() {
        val governor = AgentRecoveryGovernor()
        repeat(3) { governor.decide(Kind.EMPTY_RESPONSE) }
        assertTrue(governor.decide(Kind.EMPTY_RESPONSE) is Decision.GiveUp)
        governor.onProgress()
        assertTrue(governor.decide(Kind.EMPTY_RESPONSE) is Decision.Recover)
    }

    @Test fun `a global cap stops a run that keeps stalling between progress steps`() {
        val governor = AgentRecoveryGovernor(maxTotalRecoveries = 4)
        repeat(4) {
            assertTrue(governor.decide(Kind.EMPTY_RESPONSE) is Decision.Recover)
            governor.onProgress()
        }
        assertTrue(governor.decide(Kind.EMPTY_RESPONSE) is Decision.GiveUp)
    }

    @Test fun `invalid batch recovery lists the exact tool names`() {
        val decision = AgentRecoveryGovernor().decide(Kind.INVALID_BATCHES, toolDigest = "read_file(path*)") as Decision.Recover
        assertTrue(decision.message.contains("read_file(path*)"))
        assertEquals(0L, decision.waitMs)
    }

    @Test fun `failures wait before the next attempt and the wait grows`() {
        val governor = AgentRecoveryGovernor(mapOf(Kind.UNRESOLVED_FAILURE to 2))
        val first = governor.decide(Kind.UNRESOLVED_FAILURE) as Decision.Recover
        val second = governor.decide(Kind.UNRESOLVED_FAILURE) as Decision.Recover
        assertTrue(first.waitMs > 0 && second.waitMs > first.waitMs)
    }

    @Test fun `kinds are counted independently`() {
        val governor = AgentRecoveryGovernor()
        repeat(2) { governor.decide(Kind.UNKEPT_PROMISE) }
        assertTrue(governor.decide(Kind.UNKEPT_PROMISE) is Decision.GiveUp)
        assertTrue(governor.decide(Kind.STAGNATION) is Decision.Recover)
    }
}
