package com.omnidev.workspace.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRepetitionGuardTest {

    @Test
    fun identicalCallsAreBlockedAtTheConfiguredLimit() {
        val guard = ToolRepetitionGuard(3)
        repeat(3) { assertTrue(guard.allow("read_file_lines", mapOf("path" to "a"))) }
        assertFalse(guard.allow("read_file_lines", mapOf("path" to "a")))
        assertTrue(guard.allow("read_file_lines", mapOf("path" to "b")))
    }

    @Test
    fun argumentOrderDoesNotBypassTheLimit() {
        val guard = ToolRepetitionGuard(1)
        assertTrue(guard.allow("read", linkedMapOf("path" to "a", "start" to "1")))
        assertFalse(guard.allow("read", linkedMapOf("start" to "1", "path" to "a")))
    }

    @Test
    fun argumentDelimitersCannotCauseFalseCollisions() {
        val guard = ToolRepetitionGuard(1)
        assertTrue(guard.allow("read", mapOf("a" to "b,c=d")))
        assertTrue(guard.allow("read", mapOf("a" to "b", "c" to "d")))
    }

    @Test
    fun searchWhitespaceAndCaseDoNotBypassLimit() {
        val guard = ToolRepetitionGuard(1)
        assertTrue(guard.allow("web_search", mapOf("query" to "Android   Room Database")))
        assertFalse(guard.allow("web_search", mapOf("query" to " android room database ")))
    }

    @Test
    fun equivalentPathsDoNotBypassLimit() {
        val guard = ToolRepetitionGuard(1)
        assertTrue(guard.allow("read_file", mapOf("path" to "./app//src/main/")))
        assertFalse(guard.allow("read_file", mapOf("path" to "app/src/main")))
    }

    @Test
    fun volatileTracingArgumentsDoNotCreateFakeNovelCalls() {
        val guard = ToolRepetitionGuard(1)
        assertTrue(
            guard.allow(
                "fetch_page",
                mapOf("url" to "https://example.com", "request_id" to "abc")
            )
        )
        assertFalse(
            guard.allow(
                "fetch_page",
                mapOf("url" to "https://example.com", "request_id" to "xyz")
            )
        )
        assertEquals(2, guard.seenCount("fetch_page", mapOf("url" to "https://example.com")))
    }
}
