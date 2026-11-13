package com.omnidev.workspace.domain.neural;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RichTextDecisionModelTest {
    private static RichTextEncoder encoder() { return new RichTextEncoder(new String[] {"w:source"}, new float[] {1}, RichTextEncoder.VERSION); }
    private static TinyNeuralRuntime network(int label) {
        float[] bias = new float[6]; bias[label] = 10;
        return new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(1024, 6, new float[6144], bias, TinyNeuralRuntime.Activation.LINEAR));
    }
    private static RichTextDecisionModel model(int label) { RichTextEncoder e = encoder(); return new RichTextDecisionModel(e, network(label), 1, RichTextEncoder.VERSION, e.fingerprint()); }
    @Test public void rejectionLabelAlwaysAbstainsAndPreservesSixScores() {
        TinyDecisionModel.Decision d = model(5).classify("source", 0, 0);
        assertEquals(TinyDecisionModel.Status.ABSTAIN, d.status); assertNull(d.choice); assertEquals(6, d.probabilitiesCopy().length);
        assertTrue(d.probabilitiesCopy()[5] > 0.99);
    }
    @Test public void proposalsAndLabelsRemainBoundedAndCopied() {
        assertEquals("device", model(2).classify("source", 0.6, 0.15).choice);
        String[] labels = RichTextDecisionModel.labels(); labels[2] = "permission_granted";
        assertEquals("device", RichTextDecisionModel.labels()[2]); assertEquals("device", model(2).classify("source", 0.6, 0.15).choice);
    }
    @Test public void emptyAndPunctuationCannotBecomeBiasOnlyChoices() {
        for (String text : new String[] {"", "!!!", "😀", "ـَُ"}) assertEquals(TinyDecisionModel.Status.ABSTAIN, model(0).classify(text, 0, 0).status);
        rejects(() -> model(0).classify(null, 0.6, 0.15));
        rejects(() -> model(0).classify("source", Double.NaN, 0.15));
    }
    @Test public void foreignVocabularyAndWrongOutputSchemaAreRejected() {
        RichTextEncoder e = encoder();
        rejects(() -> new RichTextDecisionModel(e, network(0), 1, RichTextEncoder.VERSION, "foreign"));
        rejects(() -> new RichTextDecisionModel(e, network(0), 1, "foreign-version", e.fingerprint()));
        TinyNeuralRuntime wrong = new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(1024, 5, new float[5120], new float[5], TinyNeuralRuntime.Activation.LINEAR));
        rejects(() -> new RichTextDecisionModel(e, wrong, 1, RichTextEncoder.VERSION, e.fingerprint()));
        rejects(() -> new RichTextDecisionModel(e, network(0), 0, RichTextEncoder.VERSION, e.fingerprint()));
    }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException expected) { return; } fail("Expected rejection"); }
}
