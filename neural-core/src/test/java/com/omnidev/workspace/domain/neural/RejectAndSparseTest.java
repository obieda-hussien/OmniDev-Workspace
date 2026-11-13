package com.omnidev.workspace.domain.neural;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RejectAndSparseTest {
    @Test public void sparseAndDenseHaveIdenticalBitsAcrossActivationsAndDensities() {
        Random random = new Random(52);
        for (TinyNeuralRuntime.Activation activation : TinyNeuralRuntime.Activation.values()) {
            float[] weights = new float[256 * 8], bias = new float[8];
            for (int i = 0; i < weights.length; i++) weights[i] = random.nextFloat() - 0.5f;
            for (int i = 0; i < bias.length; i++) bias[i] = random.nextFloat() - 0.5f;
            TinyNeuralRuntime model = new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(256, 8, weights, bias, activation),
                    new TinyNeuralRuntime.Dense(8, 2, new float[16], new float[] {1, -1}, TinyNeuralRuntime.Activation.LINEAR));
            TinyNeuralRuntime first = new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(256, 8, weights, bias, activation));
            for (int stride : new int[] {1, 2, 4, 64, 300}) {
                float[] input = new float[256];
                for (int i = 0; i < input.length; i += stride) input[i] = random.nextFloat() - 0.5f;
                equalBits(first.infer(input), first.withSparseInput().infer(input));
                equalBits(model.infer(input), model.withSparseInput().infer(input));
            }
        }
    }

    @Test public void sparsePathPreservesSignedZeroAndInputValidation() {
        float[] negativeZero = new float[64]; Arrays.fill(negativeZero, -0.0f);
        TinyNeuralRuntime model = new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(64, 1, new float[64],
                new float[] {-0.0f}, TinyNeuralRuntime.Activation.LINEAR));
        equalBits(model.infer(negativeZero), model.withSparseInput().infer(negativeZero));
        negativeZero[1] = Float.NaN;
        rejects(() -> model.withSparseInput().infer(negativeZero));
        rejects(() -> model.withSparseInput().infer(new float[63]));
    }

    @Test public void learnedRejectionNeverEscapesAsAnExecutableChoice() {
        TinyNeuralRuntime network = new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(256, 2, new float[512],
                new float[] {0, 10}, TinyNeuralRuntime.Activation.LINEAR));
        RejectingTextIntentModel model = new RejectingTextIntentModel(network, new String[] {"chat", "__reject__"},
                1, BilingualTextEncoder.VERSION);
        TinyDecisionModel.Decision decision = model.classify("unfamiliar topic", 0.6, 0.15);
        assertEquals(TinyDecisionModel.Status.ABSTAIN, decision.status);
        assertNull(decision.choice);
        assertTrue(decision.probabilitiesCopy()[1] > 0.99);
        assertSame(decision, decision.asAbstention());
        rejects(() -> new RejectingTextIntentModel(network, new String[] {"a", "b"}, 1, BilingualTextEncoder.VERSION));
    }

    @Test public void refusingAChoicePreservesScoresAndCannotUpgradeAnAbstention() {
        TinyDecisionModel model = new TinyDecisionModel(new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(1, 2,
                new float[] {0, 0}, new float[] {0, 2}, TinyNeuralRuntime.Activation.LINEAR)), new String[] {"a", "b"}, 1);
        TinyDecisionModel.Decision choice = model.decide(new float[] {0}, 0, 0);
        assertEquals(TinyDecisionModel.Status.CHOICE, choice.status);
        TinyDecisionModel.Decision refused = choice.asAbstention();
        assertEquals(TinyDecisionModel.Status.ABSTAIN, refused.status);
        assertArrayEquals(choice.probabilitiesCopy(), refused.probabilitiesCopy(), 0);
        assertEquals(TinyDecisionModel.Status.CHOICE, choice.status);
        assertSame(refused, refused.asAbstention());
    }

    private static void equalBits(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) assertEquals(Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(actual[i]));
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        fail("Expected contract rejection");
    }
}
