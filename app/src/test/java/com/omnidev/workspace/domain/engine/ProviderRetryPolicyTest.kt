package com.omnidev.workspace.domain.engine

import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.net.SocketTimeoutException

class ProviderRetryPolicyTest {
    @Test fun `dns failures are terminal to current attempt`() {
        assertTrue(ProviderRetryPolicy.isDnsUnavailable(UnknownHostException("generativelanguage.googleapis.com")))
        assertTrue(ProviderRetryPolicy.isDnsUnavailable(IllegalStateException(
            "wrapper", UnknownHostException("name lookup"))))
        assertTrue(ProviderRetryPolicy.isDnsUnavailable(IllegalStateException(
            "Unable to resolve host generativelanguage.googleapis.com: No address associated with hostname")))
    }

    @Test fun `temporary timeout is not confused with dns`() {
        assertFalse(ProviderRetryPolicy.isDnsUnavailable(SocketTimeoutException("Read timed out")))
        assertFalse(ProviderRetryPolicy.isDnsUnavailable(IllegalArgumentException("bad json")))
    }
}
