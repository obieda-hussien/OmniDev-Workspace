package com.omnidev.workspace.domain.neural;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class MultiIntentRefusalModelTest {
    private static TinyNeuralRuntime bias(float... values) {
        return new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(256, values.length, new float[256 * values.length], values, TinyNeuralRuntime.Activation.LINEAR));
    }
    private static GatedTextIntentModel prior(boolean refuses) {
        return new GatedTextIntentModel(bias(10, 0, 0, 0, 0), MultiIntentRefusalModel.labels(), 1,
                refuses ? bias(0, 10) : bias(10, 0), 1, 0.8, BilingualTextEncoder.VERSION);
    }
    private static TinyNeuralRuntime[] heads(int mask) {
        TinyNeuralRuntime[] result = new TinyNeuralRuntime[5];
        for (int i = 0; i < 5; i++) result[i] = bias(0, (mask & 1 << i) == 0 ? -10 : 10);
        return result;
    }
    private static MultiIntentRefusalModel model(int mask, boolean strict) {
        return new MultiIntentRefusalModel(prior(false), heads(mask), new double[] {1, 1, 1, 1, 1}, 0.7, strict, BilingualTextEncoder.VERSION);
    }
    @Test public void multipleDomainsCanOnlyRefuseAndPreserveScores() {
        TinyDecisionModel.Decision before = prior(false).classify("source", 0.6, 0.15);
        TinyDecisionModel.Decision after = model(3, false).classify("source", 0.6, 0.15);
        assertEquals(TinyDecisionModel.Status.CHOICE, before.status);
        assertEquals(TinyDecisionModel.Status.ABSTAIN, after.status);
        assertArrayEquals(before.probabilitiesCopy(), after.probabilitiesCopy(), 0);
    }
    @Test public void uniqueMatchingPolicyRejectsZeroOrForeignDomainsWithoutRelabeling() {
        assertEquals("repository", model(1, true).classify("source", 0.6, 0.15).choice);
        assertEquals(TinyDecisionModel.Status.ABSTAIN, model(0, true).classify("source", 0.6, 0.15).status);
        assertEquals(TinyDecisionModel.Status.ABSTAIN, model(2, true).classify("source", 0.6, 0.15).status);
        assertEquals("repository", model(2, false).classify("source", 0.6, 0.15).choice);
    }
    @Test public void noHeadMayUpgradeAPriorRefusalAndCallerArraysAreCopied() {
        TinyNeuralRuntime[] networks = heads(1); double[] temperatures = {1, 1, 1, 1, 1};
        MultiIntentRefusalModel model = new MultiIntentRefusalModel(prior(false), networks, temperatures, 0.7, true, BilingualTextEncoder.VERSION);
        networks[0] = bias(0, -10); temperatures[0] = 0;
        assertEquals("repository", model.classify("source", 0.6, 0.15).choice);
        assertEquals(TinyDecisionModel.Status.ABSTAIN, model.classify("!?", 0.6, 0.15).status);
        assertEquals(TinyDecisionModel.Status.ABSTAIN, new MultiIntentRefusalModel(prior(true), heads(1), new double[] {1, 1, 1, 1, 1}, 0.7, true,
                BilingualTextEncoder.VERSION).classify("source", 0.6, 0.15).status);
    }
    @Test public void invalidShapesVersionsAndThresholdsFailBeforeInference() {
        rejects(() -> new MultiIntentRefusalModel(prior(false), new TinyNeuralRuntime[0], new double[0], 0.5, false, BilingualTextEncoder.VERSION));
        rejects(() -> new MultiIntentRefusalModel(prior(false), heads(1), new double[] {1, 1, 1, 1, 1}, Double.NaN, false, BilingualTextEncoder.VERSION));
        rejects(() -> new MultiIntentRefusalModel(prior(false), heads(1), new double[] {1, 1, 1, 1, 1}, 0.5, false, "wrong"));
        rejects(() -> new MultiIntentRefusalModel(prior(false), heads(1), new double[] {1, 0, 1, 1, 1}, 0.5, false, BilingualTextEncoder.VERSION));
        TinyNeuralRuntime large = new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(256, 32, new float[8192], new float[32], TinyNeuralRuntime.Activation.TANH),
                new TinyNeuralRuntime.Dense(32, 2, new float[64], new float[2], TinyNeuralRuntime.Activation.LINEAR));
        rejects(() -> new MultiIntentRefusalModel(prior(false), new TinyNeuralRuntime[] {large, large, large, large, large},
                new double[] {1, 1, 1, 1, 1}, 0.5, false, BilingualTextEncoder.VERSION));
    }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } fail("Expected contract rejection"); }
}
