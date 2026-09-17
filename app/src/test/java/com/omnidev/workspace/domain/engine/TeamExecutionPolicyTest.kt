package com.omnidev.workspace.domain.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamExecutionPolicyTest {

    @Test
    fun `mutation task is serialized even when planner marks parallel safe`() {
        val task = SwarmTask(
            id = "write-db",
            description = "Edit app/src/main/java/com/example/ChatDao.kt and update the Room migration",
            parallelSafe = true
        )

        val classified = TeamExecutionPolicy.classify(task)

        assertFalse(classified.effectiveParallelSafe)
        assertTrue(classified.mutationScore >= 0.42f)
    }

    @Test
    fun `independent read only tasks can share one wave`() {
        val a = SwarmTask(
            id = "research-auth",
            description = "Inspect authentication documentation and report findings without editing files",
            parallelSafe = true
        )
        val b = SwarmTask(
            id = "research-ui",
            description = "Inspect Compose UI architecture and report findings without editing files",
            parallelSafe = true
        )

        val waves = TeamExecutionPolicy.buildExecutionWaves(listOf(a, b), maxParallelWorkers = 3)

        assertTrue(waves.any { wave -> wave.map { it.id }.toSet() == setOf("research-auth", "research-ui") })
    }

    @Test
    fun `planner marked mutation does not share wave with peers`() {
        val writer = SwarmTask(
            id = "writer",
            description = "Patch app/src/main/java/com/example/Repository.kt to fix persistence",
            parallelSafe = true
        )
        val reader = SwarmTask(
            id = "reader",
            description = "Inspect app/src/main/java/com/example/Repository.kt for persistence risks",
            parallelSafe = true
        )

        val waves = TeamExecutionPolicy.buildExecutionWaves(listOf(writer, reader), maxParallelWorkers = 3)
        val writerWave = waves.first { wave -> wave.any { it.id == "writer" } }

        assertTrue(writerWave.size == 1)
    }

    @Test
    fun `complex worker receives more budget than small atomic worker`() {
        val small = SwarmTask(
            id = "small",
            description = "Read README.md and report the package name",
            parallelSafe = true
        )
        val complex = SwarmTask(
            id = "complex",
            description = "Refactor database, repository and Compose UI, verify migrations, run tests and benchmark the result",
            parallelSafe = false
        )

        val smallBudget = TeamExecutionPolicy.budgetFor(small)
        val complexBudget = TeamExecutionPolicy.budgetFor(complex)

        assertTrue(complexBudget.tokenBudget > smallBudget.tokenBudget)
        assertTrue(complexBudget.maxIterations >= smallBudget.maxIterations)
    }

    @Test
    fun `parallel worker cap is respected`() {
        val tasks = (1..6).map { index ->
            SwarmTask(
                id = "r$index",
                description = "Research independent topic $index and summarize evidence",
                parallelSafe = true
            )
        }

        val waves = TeamExecutionPolicy.buildExecutionWaves(tasks, maxParallelWorkers = 3)

        assertTrue(waves.all { it.size <= 3 })
    }
}
