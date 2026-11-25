package com.omnidev.workspace.domain.teamstate

import com.omnidev.workspace.data.network.RateLimitException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamFailurePolicyTest {
    @Test
    fun fileNamesAndLineNumbersDoNotPauseWorkersAsProviderFailures() {
        val localErrors = listOf(
            "fatal: cannot read ./build-1429/README.md",
            "fatal: missing file ./build-1401/README.md",
            "src/project.kt:429: invalid argument",
            "README.md:401: missing required section"
        )
        localErrors.forEach { assertFalse(it, TeamFailurePolicy.pauseWithoutWorkerRetry(it)) }
    }

    @Test
    fun providerStatusesAndTimeoutStillPauseWorkers() {
        val providerErrors = mapOf(
            "429: retry later" to TeamFailureCategory.RATE_LIMIT,
            "Provider HTTP/2 429" to TeamFailureCategory.RATE_LIMIT,
            "Too many requests" to TeamFailureCategory.RATE_LIMIT,
            RateLimitException(1_000L).message.orEmpty() to TeamFailureCategory.RATE_LIMIT,
            "GET failed with HTTP 401" to TeamFailureCategory.AUTHENTICATION,
            "API error 401 from provider" to TeamFailureCategory.AUTHENTICATION,
            "Provider request timed out" to TeamFailureCategory.BACKEND
        )
        providerErrors.forEach { (message, expected) ->
            assertEquals(message, expected, TeamFailurePolicy.classify(message))
            assertTrue(message, TeamFailurePolicy.pauseWithoutWorkerRetry(message))
        }
    }
}
