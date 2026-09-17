package com.omnidev.workspace.domain.engine

import org.junit.Assert.assertEquals
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
    fun `negated edit remains read only`() {
        val task = SwarmTask(
            id = "inspect-only",
            description = "Inspect app/src/main/java/com/example/ChatDao.kt without editing or changing files",
            parallelSafe = true
        )
        assertTrue(TeamExecutionPolicy.classify(task).effectiveParallelSafe)
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
        assertEquals(1, waves.first { wave -> wave.any { it.id == "writer" } }.size)
    }

    @Test
    fun `exclusive barrier preserves priority order`() {
        val firstRead = SwarmTask("a", "Inspect auth docs without editing", priority = 1, parallelSafe = true)
        val write = SwarmTask("b", "Patch Repository.kt", priority = 2, parallelSafe = true)
        val laterRead = SwarmTask("c", "Inspect UI docs without editing", priority = 3, parallelSafe = true)

        val waves = TeamExecutionPolicy.buildExecutionWaves(listOf(firstRead, write, laterRead), 3)

        assertEquals(listOf("a"), waves[0].map { it.id })
        assertEquals(listOf("b"), waves[1].map { it.id })
        assertEquals(listOf("c"), waves[2].map { it.id })
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

    @Test
    fun `near duplicate tasks collapse and dependencies rewire`() {
        val first = SwarmTask(
            id = "db-a",
            description = "Inspect Room database migration in app/src/main/java/com/example/Db.kt and report migration risks",
            priority = 1,
            parallelSafe = true
        )
        val duplicate = SwarmTask(
            id = "db-b",
            description = "Inspect the Room database migration at app/src/main/java/com/example/Db.kt and report migration risks",
            priority = 2,
            parallelSafe = true
        )
        val dependent = SwarmTask(
            id = "verify",
            description = "Verify the migration findings",
            priority = 3,
            dependencies = listOf("db-b"),
            parallelSafe = true
        )

        val normalized = TeamExecutionPolicy.normalizePlan(listOf(first, duplicate, dependent))

        assertEquals(2, normalized.tasks.size)
        assertEquals("db-a", normalized.aliases["db-b"])
        assertEquals(listOf("db-a"), normalized.tasks.first { it.id == "verify" }.dependencies)
    }
}
