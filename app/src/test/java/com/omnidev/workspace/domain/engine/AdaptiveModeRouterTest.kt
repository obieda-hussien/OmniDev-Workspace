package com.omnidev.workspace.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveModeRouterTest {

    @Test
    fun `agent max iteration failure escalates to team`() {
        val result = AdaptiveModeRouter.fromAgentFailure(
            errorMessage = "Agent reached maximum iterations (50) without completing.",
            userRequest = "Fix UI, database and tests"
        )

        requireNotNull(result)
        assertEquals(OmniMode.AGENT, result.from)
        assertEquals(OmniMode.SWARM, result.to)
        assertEquals(AdaptiveModeRouter.Trigger.AGENT_STUCK, result.trigger)
        assertTrue(result.confidence >= 0.78f)
    }

    @Test
    fun `provider failure never fans out into team`() {
        val result = AdaptiveModeRouter.fromAgentFailure(
            errorMessage = "Rate limit reached. HTTP 429",
            userRequest = "Fix everything"
        )

        assertNull(result)
    }

    @Test
    fun `single task team plan collapses to agent`() {
        val result = AdaptiveModeRouter.fromTeamPlan(taskCount = 1, parallelSafeTaskCount = 1)

        requireNotNull(result)
        assertEquals(OmniMode.SWARM, result.from)
        assertEquals(OmniMode.AGENT, result.to)
    }

    @Test
    fun `real team plan stays in team mode`() {
        assertNull(AdaptiveModeRouter.fromTeamPlan(taskCount = 3, parallelSafeTaskCount = 2))
    }
}
