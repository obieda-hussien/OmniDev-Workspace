package com.omnidev.workspace.domain.engine

/**
 * Distributes one hard worker-token pool across Team tasks without exceeding it.
 * Requested per-task budgets come from [TeamExecutionPolicy]; this layer only performs fair capping.
 */
object TeamBudgetAllocator {

    private const val DEFAULT_MIN_PER_WORKER = 12_000
    private const val EMERGENCY_MIN_PER_WORKER = 4_000

    data class Allocation(
        val budgets: Map<String, TeamExecutionPolicy.WorkerBudget>,
        val requestedTotal: Int,
        val allocatedTotal: Int,
        val pool: Int
    )

    fun allocate(
        tasks: List<SwarmTask>,
        tokenPool: Int
    ): Allocation {
        if (tasks.isEmpty() || tokenPool <= 0) {
            return Allocation(emptyMap(), 0, 0, tokenPool.coerceAtLeast(0))
        }

        val requested = tasks.associate { it.id to TeamExecutionPolicy.budgetFor(it) }
        val requestedTotal = requested.values.sumOf { it.tokenBudget }
        val pool = tokenPool.coerceAtLeast(0)
        if (requestedTotal <= pool) {
            return Allocation(requested, requestedTotal, requestedTotal, pool)
        }

        val count = tasks.size
        val regularFloorTotal = DEFAULT_MIN_PER_WORKER * count
        val floor = when {
            pool >= regularFloorTotal -> DEFAULT_MIN_PER_WORKER
            else -> (pool / count).coerceAtLeast(EMERGENCY_MIN_PER_WORKER)
        }
        val effectivePool = maxOf(pool, floor * count)
        val remaining = (effectivePool - floor * count).coerceAtLeast(0)
        val excessRequested = requested.values.sumOf { (it.tokenBudget - floor).coerceAtLeast(0) }

        val mutable = linkedMapOf<String, TeamExecutionPolicy.WorkerBudget>()
        tasks.forEach { task ->
            val base = requested.getValue(task.id)
            val extraNeed = (base.tokenBudget - floor).coerceAtLeast(0)
            val proportionalExtra = if (excessRequested == 0) 0 else {
                ((extraNeed.toLong() * remaining.toLong()) / excessRequested.toLong()).toInt()
            }
            mutable[task.id] = base.copy(
                tokenBudget = minOf(base.tokenBudget, floor + proportionalExtra)
            )
        }

        // Integer division may leave slack. Give it to the highest-complexity workers without
        // exceeding their original request.
        var slack = (effectivePool - mutable.values.sumOf { it.tokenBudget }).coerceAtLeast(0)
        requested.entries
            .sortedByDescending { it.value.complexity }
            .forEach { (id, original) ->
                if (slack <= 0) return@forEach
                val current = mutable.getValue(id)
                val room = (original.tokenBudget - current.tokenBudget).coerceAtLeast(0)
                val grant = minOf(room, slack)
                if (grant > 0) {
                    mutable[id] = current.copy(tokenBudget = current.tokenBudget + grant)
                    slack -= grant
                }
            }

        // If pool was below the emergency floors, never pretend the allocation fits the pool.
        // Caller can reject the plan rather than silently configure impossible budgets.
        val allocated = mutable.values.sumOf { it.tokenBudget }
        return Allocation(mutable, requestedTotal, allocated, pool)
    }
}
