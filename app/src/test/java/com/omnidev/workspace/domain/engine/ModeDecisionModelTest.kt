package com.omnidev.workspace.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeDecisionModelTest {
    @Test fun `verified feedback changes matching prediction without unbounded weights`() {
        val features = ModeDecisionModel.features(IntentClassifier.analyze("Fix Kotlin tests and verify the build"))
        var weights = FloatArray(ModeDecisionModel.DIMENSIONS)
        val before = ModeDecisionModel.probability(weights, features)
        repeat(20) { weights = ModeDecisionModel.updated(weights, features, true, 1f) }
        assertTrue(ModeDecisionModel.probability(weights, features) > before)
        assertTrue(weights.all { it in -3f..3f })
        assertEquals(weights.toList(), ModeDecisionModel.decode(ModeDecisionModel.encode(weights)).toList())
    }

    @Test fun `malformed persisted model resets safely`() {
        assertEquals(ModeDecisionModel.DIMENSIONS, ModeDecisionModel.decode("NaN,invalid").size)
        assertEquals(0.5f, ModeDecisionModel.probability(
            ModeDecisionModel.decode("NaN,invalid"), FloatArray(ModeDecisionModel.DIMENSIONS)
        ), 0.001f)
    }

    @Test fun `small task has smaller output cap but never exceeds model or remaining budget`() {
        val simple = IntentClassifier.analyze("Read the project file")
        assertTrue(AgentDecisionPolicy.outputCap(simple, 8_192, 10_000) <= 4_096)
        assertEquals(700, AgentDecisionPolicy.outputCap(simple, 8_192, 700))
        assertTrue(!AgentDecisionPolicy.useModelCompaction(8_000, 10_000, 2_000))
    }
}
