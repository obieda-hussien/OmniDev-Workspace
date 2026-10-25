package com.omnidev.workspace.domain.engine

import com.omnidev.workspace.data.model.CompletionResponse
import com.omnidev.workspace.data.network.AutoRoutingExhaustedException
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AutoRoutingBudgetTest {
    @Test fun `exhausted Auto routing is terminal even when its cause looks transient`() = runTest {
        val exhausted = AutoRoutingExhaustedException(IOException("HTTP 503 service unavailable"))
        var attempts = 0
        try {
            CompletionRetry.run { attempts++; throw exhausted }
            fail("Expected failure")
        } catch (error: AutoRoutingExhaustedException) {
            assertSame(exhausted, error)
            assertEquals(1, attempts)
        }
    }

    @Test fun `exhausted message keeps the underlying provider failure for the user`() {
        val error = AutoRoutingExhaustedException(IOException("rate limit reached"))
        assertTrue(error.message.orEmpty().contains("rate limit reached"))
        assertTrue(error.cause is IOException)
    }

    @Test fun `routed model id defaults to null and survives copy`() {
        val plain = CompletionResponse("hi")
        assertEquals(null, plain.routedModelId)
        assertEquals("m-1", plain.copy(routedModelId = "m-1").routedModelId)
    }
}
