package com.omnidev.workspace.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamBudgetAllocatorTest {

    @Test
    fun `allocation never exceeds a viable pool`() {
        val tasks = (1..6).map { index ->
            SwarmTask(
                id = "task-$index",
                description = "Research component $index, inspect code, verify findings and report evidence",
                parallelSafe = true
            )
        }
        val allocation = TeamBudgetAllocator.allocate(tasks, tokenPool = 180_000)

        assertEquals(tasks.size, allocation.budgets.size)
        assertTrue(allocation.allocatedTotal <= 180_000)
        assertTrue(allocation.budgets.values.all { it.tokenBudget > 0 })
    }

    @Test
    fun `small plan keeps requested budgets when pool is sufficient`() {
        val tasks = listOf(
            SwarmTask("a", "Read README and report package", parallelSafe = true),
            SwarmTask("b", "Inspect build configuration without editing", parallelSafe = true)
        )
        val requested = tasks.sumOf { TeamExecutionPolicy.budgetFor(it).tokenBudget }
        val allocation = TeamBudgetAllocator.allocate(tasks, tokenPool = requested + 10_000)

        assertEquals(requested, allocation.allocatedTotal)
        assertEquals(requested, allocation.requestedTotal)
    }

    @Test
    fun `higher complexity keeps at least as much budget under pressure`() {
        val small = SwarmTask("small", "Read README.md", parallelSafe = true)
        val large = SwarmTask(
            "large",
            "Analyze database migration, repository architecture, tests and verification evidence",
            parallelSafe = true
        )
        val allocation = TeamBudgetAllocator.allocate(listOf(small, large), tokenPool = 50_000)

        assertTrue(
            allocation.budgets.getValue("large").tokenBudget >=
                allocation.budgets.getValue("small").tokenBudget
        )
    }
}
