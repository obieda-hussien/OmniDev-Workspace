package com.omnidev.workspace.data.db

import com.omnidev.workspace.data.db.entities.SharedMemoryMergePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedMemoryMergePolicyTest {
    @Test
    fun newerRevisionWins() {
        assertTrue(SharedMemoryMergePolicy.shouldAccept(2, 100, 3, 90))
    }

    @Test
    fun staleRevisionIsRejected() {
        assertFalse(SharedMemoryMergePolicy.shouldAccept(3, 100, 2, 200))
    }

    @Test
    fun timestampBreaksSameRevisionTie() {
        assertTrue(SharedMemoryMergePolicy.shouldAccept(4, 100, 4, 101))
        assertFalse(SharedMemoryMergePolicy.shouldAccept(4, 100, 4, 100))
    }
}
