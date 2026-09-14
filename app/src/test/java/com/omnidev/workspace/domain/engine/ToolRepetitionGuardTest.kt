package com.omnidev.workspace.domain.engine

import org.junit.Assert.*
import org.junit.Test

class ToolRepetitionGuardTest {
    @Test fun identicalCallsAreBlockedAtTheConfiguredLimit() {
        val guard = ToolRepetitionGuard(3)
        repeat(3) { assertTrue(guard.allow("read_file_lines", mapOf("path" to "a"))) }
        assertFalse(guard.allow("read_file_lines", mapOf("path" to "a")))
        assertTrue(guard.allow("read_file_lines", mapOf("path" to "b")))
    }

    @Test fun argumentOrderDoesNotBypassTheLimit() {
        val guard = ToolRepetitionGuard(1)
        assertTrue(guard.allow("read", linkedMapOf("path" to "a", "start" to "1")))
        assertFalse(guard.allow("read", linkedMapOf("start" to "1", "path" to "a")))
    }

    @Test fun argumentDelimitersCannotCauseFalseCollisions() {
        val guard = ToolRepetitionGuard(1)
        assertTrue(guard.allow("read", mapOf("a" to "b,c=d")))
        assertTrue(guard.allow("read", mapOf("a" to "b", "c" to "d")))
    }
}
