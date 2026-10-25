package com.omnidev.workspace.data.network

import com.omnidev.workspace.data.routing.AutoModelRouter
import java.io.IOException
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AutoSentinelGuardTest {
    @Test fun `auto policy ids never reach the model registry`() {
        for (policy in listOf(AutoModelRouter.FREE_ONLY, AutoModelRouter.FREE_FIRST_WITH_PAID)) {
            try {
                requireConcreteModel(policy)
                fail("Expected IOException for $policy")
            } catch (error: IOException) {
                assertTrue(error.message.orEmpty().contains("Auto has no eligible"))
            }
        }
    }

    @Test fun `concrete model ids pass through`() {
        requireConcreteModel("gpt-4o")
    }
}
