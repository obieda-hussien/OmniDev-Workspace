package com.omnidev.workspace.domain.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveModeRouterTest {

    @After
    fun resetPreferenceSource() {
        AdaptiveModeRouter.installPreferenceSource(null)
    }

    @Test
    fun `agent max iteration failure escalates splittable work to team`() {
        val result = AdaptiveModeRouter.fromAgentFailure(
            errorMessage = "Agent reached maximum iterations (50) without completing.",
            userRequest = "Fix UI, database and tests"
        )

        requireNotNull(result)
        assertEquals(OmniMode.AGENT, result.from)
        assertEquals(OmniMode.SWARM, result.to)
        assertEquals(AdaptiveModeRouter.Trigger.AGENT_STUCK, result.trigger)
        assertTrue(result.confidence >= 0.78f)
        assertTrue(result.evidence.isNotEmpty())
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
    fun `tiny serial dependency plan collapses to agent`() {
        val result = AdaptiveModeRouter.fromTeamPlan(
            taskCount = 2,
            parallelSafeTaskCount = 0,
            dependencyEdgeCount = 1
        )
        requireNotNull(result)
        assertEquals(OmniMode.AGENT, result.to)
    }

    @Test
    fun `real parallel team plan stays in team mode`() {
        assertNull(
            AdaptiveModeRouter.fromTeamPlan(
                taskCount = 4,
                parallelSafeTaskCount = 3,
                dependencyEdgeCount = 1
            )
        )
    }

    @Test
    fun `repeated rejection suppresses noncritical team nagging`() {
        AdaptiveModeRouter.installPreferenceSource(object : ModePreferenceSource {
            override fun confidenceAdjustment(from: OmniMode, to: OmniMode) = -0.10f
            override fun stronglyDisliked(from: OmniMode, to: OmniMode) = true
        })

        val result = AdaptiveModeRouter.fromAgentFailure(
            errorMessage = "Build failed after verification.",
            userRequest = "Fix the build and verify it"
        )
        assertNull(result)
    }

    @Test
    fun `chat capability gap chooses team only for parallel execution`() {
        val result = AdaptiveModeRouter.fromChatRequest(
            "Implement the UI, database migration, security checks and tests in parallel"
        )
        requireNotNull(result)
        assertEquals(OmniMode.CHAT, result.from)
        assertTrue(result.to == OmniMode.AGENT || result.to == OmniMode.SWARM)
        if (result.to == OmniMode.SWARM) {
            assertTrue(result.confidence >= 0.66f)
        }
    }
}
