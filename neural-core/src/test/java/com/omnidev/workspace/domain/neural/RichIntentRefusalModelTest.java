package com.omnidev.workspace.domain.neural;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RichIntentRefusalModelTest {
    private static TinyNeuralRuntime bias(int width, TinyNeuralRuntime.Activation activation, float... values) {
        return new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(width, values.length, new float[width * values.length], values, activation));
    }
    private static GatedTextIntentModel prior(boolean reject) {
        return new GatedTextIntentModel(bias(256, TinyNeuralRuntime.Activation.LINEAR, 10, 0, 0, 0, 0), MultiIntentRefusalModel.labels(), 1,
                bias(256, TinyNeuralRuntime.Activation.LINEAR, reject ? 0 : 10, reject ? 10 : 0), 1, 0.8, BilingualTextEncoder.VERSION);
    }
    private static RichTextEncoder encoder() { return new RichTextEncoder(new String[] {"w:source"}, new float[] {1}, RichTextEncoder.VERSION); }
    private static RichIntentRefusalModel model(boolean reject, boolean strict, float... logits) {
        RichTextEncoder e = encoder();
        return new RichIntentRefusalModel(prior(reject), e, bias(1024, TinyNeuralRuntime.Activation.SIGMOID, logits), 0.7, strict, RichTextEncoder.VERSION, e.fingerprint());
    }
    @Test public void fusedMultiplePresenceOnlyRefusesAndKeepsOriginalProbabilities() {
        TinyDecisionModel.Decision before = prior(false).classify("source", 0.6, 0.15), after = model(false, false, 10, 10, -10, -10, -10).classify("source", 0.6, 0.15);
        assertEquals(TinyDecisionModel.Status.ABSTAIN, after.status); assertArrayEquals(before.probabilitiesCopy(), after.probabilitiesCopy(), 0);
        assertEquals("repository", model(false, true, 10, -10, -10, -10, -10).classify("source", 0.6, 0.15).choice);
    }
    @Test public void priorRefusalsAndStrictUnknownsCannotBecomeChoices() {
        assertEquals(TinyDecisionModel.Status.ABSTAIN, model(true, false, 10, -10, -10, -10, -10).classify("source", 0.6, 0.15).status);
        assertEquals(TinyDecisionModel.Status.ABSTAIN, model(false, true, -10, 10, -10, -10, -10).classify("source", 0.6, 0.15).status);
        assertEquals(TinyDecisionModel.Status.ABSTAIN, model(false, true, -10, -10, -10, -10, -10).classify("source", 0.6, 0.15).status);
        assertEquals(TinyDecisionModel.Status.ABSTAIN, model(false, false, 10, -10, -10, -10, -10).classify("!", 0.6, 0.15).status);
    }
    @Test public void vocabularyPairingAndProbabilityContractsAreEnforced() {
        RichTextEncoder e = encoder(); TinyNeuralRuntime network = bias(1024, TinyNeuralRuntime.Activation.SIGMOID, 10, -10, -10, -10, -10);
        rejects(() -> new RichIntentRefusalModel(prior(false), e, network, 0.7, false, RichTextEncoder.VERSION, "foreign"));
        RichTextEncoder foreign = new RichTextEncoder(new String[] {"w:other"}, new float[] {1}, RichTextEncoder.VERSION);
        rejects(() -> new RichIntentRefusalModel(prior(false), e, network, 0.7, false, RichTextEncoder.VERSION, foreign.fingerprint()));
        rejects(() -> new RichIntentRefusalModel(prior(false), e, network, Double.NaN, false, RichTextEncoder.VERSION, e.fingerprint()));
        RichIntentRefusalModel invalidScores = new RichIntentRefusalModel(prior(false), e, bias(1024, TinyNeuralRuntime.Activation.LINEAR, 2, 0, 0, 0, 0),
                0.7, false, RichTextEncoder.VERSION, e.fingerprint());
        rejects(() -> invalidScores.classify("source", 0.6, 0.15));
    }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } fail("Expected rich contract rejection"); }
    @Test public void optionalPublicGateCanOnlyRefuseWithoutChangingScores() {
        RichTextEncoder e = encoder();
        TinyNeuralRuntime presence = bias(1024, TinyNeuralRuntime.Activation.SIGMOID, 10, -10, -10, -10, -10);
        RichIntentRefusalModel gate = new RichIntentRefusalModel(prior(false), e, presence, 0.7, false, RichTextEncoder.VERSION, e.fingerprint(),
                bias(1024, TinyNeuralRuntime.Activation.SIGMOID, 10), 0.99);
        TinyDecisionModel.Decision before = model(false, false, 10, -10, -10, -10, -10).classify("source", 0.6, 0.15), after = gate.classify("source", 0.6, 0.15);
        assertEquals(TinyDecisionModel.Status.ABSTAIN, after.status); assertArrayEquals(before.probabilitiesCopy(), after.probabilitiesCopy(), 0);
        RichIntentRefusalModel accept = new RichIntentRefusalModel(prior(false), e, presence, 0.7, false, RichTextEncoder.VERSION, e.fingerprint(),
                bias(1024, TinyNeuralRuntime.Activation.SIGMOID, -10), 0.99);
        assertEquals(before.choice, accept.classify("source", 0.6, 0.15).choice);
    }
    @Test public void optionalGateCannotUpgradePriorOrPresenceRefusalsAndSkipsItsWork() {
        RichTextEncoder e = encoder(); TinyNeuralRuntime invalid = bias(1024, TinyNeuralRuntime.Activation.LINEAR, 2);
        TinyNeuralRuntime presence = bias(1024, TinyNeuralRuntime.Activation.SIGMOID, 10, 10, -10, -10, -10);
        for (boolean priorRefusal : new boolean[] {false, true}) {
            RichIntentRefusalModel gate = new RichIntentRefusalModel(prior(priorRefusal), e, presence, 0.7, false, RichTextEncoder.VERSION, e.fingerprint(), invalid, 0.9);
            assertEquals(TinyDecisionModel.Status.ABSTAIN, gate.classify("source", 0.6, 0.15).status);
        }
    }
    @Test public void optionalGateRejectsInvalidShapeCutoffAndInferenceRange() {
        RichTextEncoder e = encoder(); TinyNeuralRuntime presence = bias(1024, TinyNeuralRuntime.Activation.SIGMOID, 10, -10, -10, -10, -10);
        rejects(() -> new RichIntentRefusalModel(prior(false), e, presence, 0.7, false, RichTextEncoder.VERSION, e.fingerprint(), bias(256, TinyNeuralRuntime.Activation.SIGMOID, 1), 0.9));
        rejects(() -> new RichIntentRefusalModel(prior(false), e, presence, 0.7, false, RichTextEncoder.VERSION, e.fingerprint(), bias(1024, TinyNeuralRuntime.Activation.SIGMOID, 1), Double.NaN));
        rejects(() -> new RichIntentRefusalModel(prior(false), e, presence, 0.7, false, RichTextEncoder.VERSION, e.fingerprint(), bias(1024, TinyNeuralRuntime.Activation.SIGMOID, 1, 1), 0.9));
        RichIntentRefusalModel invalid = new RichIntentRefusalModel(prior(false), e, presence, 0.7, false, RichTextEncoder.VERSION, e.fingerprint(), bias(1024, TinyNeuralRuntime.Activation.LINEAR, 2), 0.9);
        rejects(() -> invalid.classify("source", 0.6, 0.15));
    }
}
