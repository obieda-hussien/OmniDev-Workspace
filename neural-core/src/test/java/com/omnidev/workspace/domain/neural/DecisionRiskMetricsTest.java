package com.omnidev.workspace.domain.neural;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DecisionRiskMetricsTest {
    private static TinyDecisionModel.Decision prediction(double probability, double threshold) {
        TinyNeuralRuntime network = new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(1, 2,
                new float[] {0, 0}, new float[] {(float) Math.log(probability), (float) Math.log(1 - probability)},
                TinyNeuralRuntime.Activation.LINEAR));
        return new TinyDecisionModel(network, new String[] {"a", "b"}, 1).decide(new float[] {0}, threshold, 0);
    }

    @Test public void confidentWrongPredictionsRemainErrors() {
        DecisionRiskMetrics metrics = new DecisionRiskMetrics(2);
        metrics.add(prediction(0.99, 0.6), 1);
        assertEquals(1, metrics.acceptedErrors());
        assertEquals(1, metrics.selectiveError(), 0);
        assertEquals(0.99, metrics.expectedCalibrationError(), 1e-6);
    }

    @Test public void acceptedOutOfScopeChoicesAreNotKnownClassSuccesses() {
        DecisionRiskMetrics metrics = new DecisionRiskMetrics(2);
        metrics.add(prediction(0.99, 0.6), -1);
        assertEquals(1, metrics.requiredAbstentions());
        assertEquals(1, metrics.acceptedErrors());
        assertEquals(0, metrics.known());
        assertNull(metrics.expectedCalibrationError());
    }

    @Test public void zeroCoverageDoesNotMasqueradeAsPerfectSelectiveAccuracy() {
        DecisionRiskMetrics metrics = new DecisionRiskMetrics(2);
        assertNull(metrics.coverage());
        metrics.add(prediction(0.55, 0.9), -1);
        assertEquals(0, metrics.coverage(), 0);
        assertNull(metrics.selectiveError());
        assertEquals(1, metrics.refused());
    }

    @Test public void weightedCalibrationUsesKnownRowsIncludingAbstentionsOnly() {
        DecisionRiskMetrics metrics = new DecisionRiskMetrics(2);
        metrics.add(prediction(0.8, 0.6), 0);
        metrics.add(prediction(0.55, 0.9), 0);
        metrics.add(prediction(0.99, 0.6), -1);
        assertEquals(0.325, metrics.expectedCalibrationError(), 1e-6);
        assertEquals(2.0 / 3, metrics.coverage(), 1e-6);
        assertEquals(0.5, metrics.selectiveError(), 0);
    }

    @Test public void invalidObservationDoesNotPartiallyUpdateCounters() {
        DecisionRiskMetrics metrics = new DecisionRiskMetrics(3);
        try { metrics.add(prediction(0.8, 0.6), 0); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(0, metrics.total());
        try { metrics.add(null, -1); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals(0, metrics.total());
    }
}
