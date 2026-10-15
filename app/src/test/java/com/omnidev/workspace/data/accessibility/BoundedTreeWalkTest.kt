package com.omnidev.workspace.data.accessibility

import org.junit.Assert.*
import org.junit.Test

class BoundedTreeWalkTest {
    private data class Node(val depth: Int, val index: Int)

    @Test fun veryDeepTreeStopsWithoutRecursionAndReleasesChildren() {
        val released = mutableListOf<Node>()
        val result = BoundedTreeWalk.scan(Node(0, 0), { 1 },
            { node, _ -> Node(node.depth + 1, 0) }, released::add, {}, { 0L })
        assertEquals(49, result.visited)
        assertTrue(result.truncated)
        assertEquals(48, released.size)
        assertTrue(released.none { it.depth == 0 })
    }

    @Test fun wideTreeDoesNotEagerlyAcquireThousandsOfChildren() {
        var lookups = 0
        val released = mutableListOf<Node>()
        val result = BoundedTreeWalk.scan(Node(0, 0), { if (it.depth == 0) 100_000 else 0 },
            { node, index -> lookups++; Node(node.depth + 1, index) }, released::add,
            {}, { 0L }, maxNodes = 16)
        assertEquals(16, result.visited)
        assertEquals(15, lookups)
        assertEquals(15, released.size)
        assertTrue(result.truncated)
    }

    @Test fun missingChildrenAlsoConsumeTheLookupBudget() {
        var lookups = 0
        val result = BoundedTreeWalk.scan(Node(0, 0), { Int.MAX_VALUE },
            { _, _ -> lookups++; null }, {}, {}, { 0L }, maxNodes = 16)
        assertEquals(1, result.visited)
        assertEquals(15, lookups)
        assertTrue(result.truncated)
    }

    @Test fun slowBinderCallPreventsFurtherLookupsAfterItReturns() {
        var elapsed = 0L
        var lookups = 0
        var released = 0
        val result = BoundedTreeWalk.scan(Node(0, 0), { 10 },
            { node, index -> lookups++; elapsed += 5_000; Node(node.depth + 1, index) },
            { released++ }, {}, { elapsed })
        assertEquals(1, lookups)
        assertEquals(1, released)
        assertTrue(result.truncated)
    }

    @Test fun childMetadataFailureReleasesEveryAcquiredChildButNotRoot() {
        val released = mutableListOf<Node>()
        try {
            BoundedTreeWalk.scan(Node(0, 0), { if (it.depth == 3) error("Stale node") else 1 },
                { node, _ -> Node(node.depth + 1, 0) }, released::add, {}, { 0L })
            fail("Expected stale node failure")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf(3, 2, 1), released.map { it.depth })
    }

    @Test fun completeSmallTreeIsNotReportedAsTruncated() {
        val result = BoundedTreeWalk.scan(Node(0, 0), { if (it.depth == 0) 2 else 0 },
            { node, index -> Node(node.depth + 1, index) }, {}, {}, { 0L })
        assertEquals(3, result.visited)
        assertFalse(result.truncated)
    }
}
