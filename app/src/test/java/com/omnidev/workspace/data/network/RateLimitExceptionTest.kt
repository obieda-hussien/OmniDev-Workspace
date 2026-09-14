package com.omnidev.workspace.data.network

import org.junit.Assert.*
import org.junit.Test

class RateLimitExceptionTest {
    @Test fun acceptsSecondsAndRejectsInvalidValues() {
        assertEquals(30_000L, parseRetryAfter("30"))
        assertEquals(0L, parseRetryAfter("0"))
        assertNull(parseRetryAfter("-1"))
        assertNull(parseRetryAfter("invalid"))
        assertNull(parseRetryAfter(null))
    }

    @Test fun acceptsHttpDateWithoutRetryingBeforeIt() {
        assertEquals(60_000L, parseRetryAfter("Thu, 01 Jan 1970 00:01:00 GMT", 0))
        assertEquals(0L, parseRetryAfter("Thu, 01 Jan 1970 00:01:00 GMT", 120_000))
    }

    @Test fun hugeDelayCannotOverflowIntoImmediateRetry() {
        assertTrue(parseRetryAfter(Long.MAX_VALUE.toString())!! > 60_000)
        assertTrue(RateLimitException(120_000).message!!.contains("120 seconds"))
    }
}
