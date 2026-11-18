package com.omnidev.workspace.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledDecisionRoutingTest {
    @Test fun inspectionUsesPackagedWeightsOnTheAutoPath() {
        for (prompt in listOf("Inspect the GitHub repository branch and git status", "افحص git status والـ branch")) {
            val baseline = IntentClassifier.scoreModes(prompt)
            assertTrue(baseline.agent < 0.48f || baseline.agent < baseline.chat + 0.03f)
            assertTrue(BundledDecisionModels.supportsInspection(prompt))
            assertEquals(OmniMode.AGENT, IntentClassifier.classify(prompt))
        }
    }

    @Test fun unrelatedAndOversizeInputsKeepTheFallback() {
        for (prompt in listOf("Check the weather tomorrow", "شوف الطقس بكرة", "Inspect " + "x".repeat(2048))) {
            assertFalse(BundledDecisionModels.supportsInspection(prompt))
            assertEquals(OmniMode.CHAT, IntentClassifier.classify(prompt))
        }
        assertFalse(BundledDecisionModels.supportsInspection("\uFDFA".repeat(180)))
        assertEquals(OmniMode.CHAT, IntentClassifier.classify("What is a GitHub repository?"))
    }
}
