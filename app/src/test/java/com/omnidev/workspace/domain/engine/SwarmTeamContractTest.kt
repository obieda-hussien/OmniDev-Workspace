package com.omnidev.workspace.domain.engine

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwarmTeamContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy task json gets safe defaults for new team fields`() {
        val task = json.decodeFromString<SwarmTask>(
            """{"id":"research","description":"research sources","priority":1,"dependencies":[],"requiredPersona":"Researcher"}"""
        )

        assertTrue(task.parallelSafe)
        assertFalse(task.needsConnectedTools)
    }

    @Test
    fun `mutating task can opt out of parallel execution and request connected tools`() {
        val task = json.decodeFromString<SwarmTask>(
            """{"id":"deploy","description":"update repo","parallelSafe":false,"needsConnectedTools":true}"""
        )

        assertFalse(task.parallelSafe)
        assertTrue(task.needsConnectedTools)
    }

    @Test
    fun `worker usage event keeps both request delta and cumulative total`() {
        val task = SwarmTask(id = "worker-a", description = "test")
        val event = SwarmEvent.WorkerTokenUsage(
            task = task,
            totalTokens = 98_737,
            budget = 180_000,
            iterationTokens = 49_443
        )

        assertEquals(49_443, event.iterationTokens)
        assertEquals(98_737, event.totalTokens)
        assertEquals(180_000, event.budget)
    }
}
