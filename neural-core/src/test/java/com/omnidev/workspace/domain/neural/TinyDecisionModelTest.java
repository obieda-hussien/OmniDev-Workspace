package com.omnidev.workspace.domain.neural;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TinyDecisionModelTest {
    private static TinyNeuralRuntime network(float a, float b) {
        return new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(1, 2, new float[] {0, 0},
                new float[] {a, b}, TinyNeuralRuntime.Activation.LINEAR));
    }

    @Test public void returnsOnlyDeclaredLabelAndNormalizedProbabilities() {
        TinyDecisionModel model = new TinyDecisionModel(network(0, 2), new String[] {"inspect", "verify"}, 1);
        TinyDecisionModel.Decision decision = model.decide(new float[] {0}, 0.7, 0.2);
        assertEquals(TinyDecisionModel.Status.CHOICE, decision.status);
        assertEquals("verify", decision.choice);
        assertEquals(1, decision.probabilitiesCopy()[0] + decision.probabilitiesCopy()[1], 1e-12);
    }

    @Test public void labelsAndDistributionsCannotBeMutatedByCaller() {
        String[] labels = {"inspect", "verify"};
        TinyDecisionModel model = new TinyDecisionModel(network(0, 2), labels, 1);
        labels[1] = "foreign";
        TinyDecisionModel.Decision decision = model.decide(new float[] {0}, 0, 0);
        decision.probabilitiesCopy()[1] = 0;
        assertEquals("verify", decision.choice);
        assertTrue(decision.probabilitiesCopy()[1] > 0.8);
    }

    @Test public void tiedOrLowConfidencePredictionsAbstain() {
        TinyDecisionModel tied = new TinyDecisionModel(network(0, 0), new String[] {"a", "b"}, 1);
        assertEquals(TinyDecisionModel.Status.ABSTAIN, tied.decide(new float[] {0}, 0, 0).status);
        TinyDecisionModel uncertain = new TinyDecisionModel(network(0, 0.1f), new String[] {"a", "b"}, 1);
        assertNull(uncertain.decide(new float[] {0}, 0.9, 0).choice);
        assertEquals(TinyDecisionModel.Status.ABSTAIN, uncertain.decide(new float[] {0}, 0, 0.5).status);
    }

    @Test public void stableSoftmaxHandlesExtremeFiniteLogits() {
        TinyDecisionModel model = new TinyDecisionModel(network(-Float.MAX_VALUE, Float.MAX_VALUE), new String[] {"a", "b"}, 1);
        assertEquals(1, model.decide(new float[] {0}, 0, 0).probability, 0);
    }

    @Test public void temperatureChangesProbabilityWithoutReorderingLabels() {
        TinyDecisionModel cool = new TinyDecisionModel(network(0, 2), new String[] {"a", "b"}, 1);
        TinyDecisionModel warm = new TinyDecisionModel(network(0, 2), new String[] {"a", "b"}, 2);
        assertEquals(cool.decide(new float[] {0}, 0, 0).choice, warm.decide(new float[] {0}, 0, 0).choice);
        assertTrue(cool.decide(new float[] {0}, 0, 0).probability > warm.decide(new float[] {0}, 0, 0).probability);
    }

    @Test public void invalidContractsAndThresholdsAreRejected() {
        rejects(() -> new TinyDecisionModel(network(0, 1), new String[] {"a", "a"}, 1));
        rejects(() -> new TinyDecisionModel(network(0, 1), new String[] {"a"}, 1));
        rejects(() -> new TinyDecisionModel(network(0, 1), new String[] {"a", "b"}, Double.NaN));
        rejects(() -> new TinyDecisionModel(network(0, 1), new String[] {"a", "b"}, 0));
        TinyDecisionModel model = new TinyDecisionModel(network(0, 1), new String[] {"a", "b"}, 1);
        rejects(() -> model.decide(new float[] {0}, -0.1, 0));
        rejects(() -> model.decide(new float[] {0}, 0, Double.POSITIVE_INFINITY));
        rejects(() -> model.decide(new float[] {Float.NaN}, 0, 0));
        rejects(() -> model.decide(new float[] {0, 0}, 0, 0));
    }

    private static void rejects(Runnable work) {
        try { work.run(); } catch (IllegalArgumentException expected) { return; }
        fail("Expected contract rejection");
    }
}
