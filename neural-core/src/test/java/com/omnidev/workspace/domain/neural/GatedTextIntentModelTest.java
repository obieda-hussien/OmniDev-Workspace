package com.omnidev.workspace.domain.neural;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class GatedTextIntentModelTest {
    private static TinyNeuralRuntime bias(float... values) {
        return new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(256, values.length,
                new float[256 * values.length], values, TinyNeuralRuntime.Activation.LINEAR));
    }
    private static GatedTextIntentModel model(TinyNeuralRuntime domain, TinyNeuralRuntime gate, double cutoff) {
        return new GatedTextIntentModel(domain, new String[] {"a", "b"}, 1, gate, 1, cutoff, BilingualTextEncoder.VERSION);
    }

    @Test public void rejectionCannotChangeDomainScoresOrUpgradeOriginalAbstention() {
        TinyNeuralRuntime domain = bias(0, 3);
        TextIntentModel old = new TextIntentModel(domain, new String[] {"a", "b"}, 1, BilingualTextEncoder.VERSION);
        GatedTextIntentModel gate = model(domain, bias(0, 10), 0.8);
        TinyDecisionModel.Decision before = old.classify("read source", 0.6, 0.15), after = gate.classify("read source", 0.6, 0.15);
        assertEquals(TinyDecisionModel.Status.CHOICE, before.status);
        assertEquals(TinyDecisionModel.Status.ABSTAIN, after.status);
        assertNull(after.choice);
        assertArrayEquals(before.probabilitiesCopy(), after.probabilitiesCopy(), 0);
        assertEquals(TinyDecisionModel.Status.ABSTAIN, model(bias(0, 0), bias(10, 0), 0.8).classify("source", 0, 0).status);
    }

    @Test public void originalChoicesRemainUnchangedWhenGateAllowsThem() {
        TinyNeuralRuntime domain = bias(0, 3);
        assertEquals("b", model(domain, bias(10, 0), 0.8).classify("source", 0.6, 0.15).choice);
        assertEquals(TinyDecisionModel.Status.ABSTAIN, model(domain, bias(10, 0), 0.8).classify("!?", 0, 0).status);
    }

    @Test public void arbitraryGateCannotInventLabelsAcrossRandomDomainModels() {
        Random random = new Random(100);
        for (int run = 0; run < 25; run++) {
            float[] weights = new float[512];
            for (int i = 0; i < weights.length; i++) weights[i] = random.nextFloat() - 0.5f;
            TinyNeuralRuntime domain = new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(256, 2, weights,
                    new float[] {0, 0}, TinyNeuralRuntime.Activation.LINEAR));
            TextIntentModel old = new TextIntentModel(domain, new String[] {"a", "b"}, 1, BilingualTextEncoder.VERSION);
            GatedTextIntentModel gated = model(domain, bias(random.nextFloat(), random.nextFloat()), 0.5);
            for (String text : Arrays.asList("source code", "phone volume", "hello", "")) {
                TinyDecisionModel.Decision before = old.classify(text, 0, 0), after = gated.classify(text, 0, 0);
                assertArrayEquals(before.probabilitiesCopy(), after.probabilitiesCopy(), 0);
                if (after.status == TinyDecisionModel.Status.CHOICE) assertEquals(before.choice, after.choice);
            }
        }
    }

    @Test public void malformedContractsAreRejectedBeforeInference() {
        rejects(() -> model(bias(0, 3), bias(0, 1, 2), 0.8));
        rejects(() -> model(bias(0, 3), bias(0, 1), Double.NaN));
        rejects(() -> model(bias(0, 3), bias(0, 1), 0.4));
        rejects(() -> new GatedTextIntentModel(bias(0, 1), new String[] {"a", "b"}, 1, bias(0, 1), 1, 0.8, "wrong"));
        rejects(() -> model(bias(0, 3), bias(0, 1), 0.8).classify("source", Double.NaN, 0));
    }

    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        fail("Expected contract rejection");
    }
}
