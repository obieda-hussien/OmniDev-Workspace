package com.omnidev.workspace.domain.neural;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class TextIntentModelTest {
    private static TinyNeuralRuntime network(int width) {
        return new TinyNeuralRuntime(new TinyNeuralRuntime.Dense(width, 2, new float[width * 2],
                new float[] {0, 100}, TinyNeuralRuntime.Activation.LINEAR));
    }

    @Test public void normalizationPreservesArabicEnglishEquivalenceRules() {
        assertArrayEquals(BilingualTextEncoder.encode("ANDROID"), BilingualTextEncoder.encode("android"), 0);
        assertEquals(BilingualTextEncoder.normalize("\u0625\u0646\u062c\u0627\u0632"),
                BilingualTextEncoder.normalize("\u0627\u0650\u0646\u062c\u0640\u0627\u0632"));
    }

    @Test public void vectorsAreDeterministicNormalizedAndIndependent() {
        float[] first = BilingualTextEncoder.encode("Kotlin source GitHub");
        float[] second = BilingualTextEncoder.encode("Kotlin source GitHub");
        assertArrayEquals(first, second, 0);
        double norm = 0;
        for (float value : first) { assertTrue(Float.isFinite(value)); norm += value * value; }
        assertEquals(1, norm, 1e-6);
        Arrays.fill(first, 0);
        assertArrayEquals(second, BilingualTextEncoder.encode("Kotlin source GitHub"), 0);
    }

    @Test public void emptyOrPunctuationTextCannotUseCheckpointBiasAsIntent() {
        TextIntentModel model = new TextIntentModel(network(256), new String[] {"a", "b"}, 1, BilingualTextEncoder.VERSION);
        for (String text : new String[] {"", "   ", "!?"}) {
            assertEquals(TinyDecisionModel.Status.ABSTAIN, model.classify(text, 0, 0).status);
        }
        assertEquals("b", model.classify("source", 0, 0).choice);
    }

    @Test public void incompatibleCheckpointSchemasAreRejected() {
        rejects(() -> new TextIntentModel(network(5), new String[] {"a", "b"}, 1, BilingualTextEncoder.VERSION));
        rejects(() -> new TextIntentModel(network(256), new String[] {"a", "b"}, 1, "unknown-encoder"));
    }

    @Test public void invalidOrOversizedInputsAreNotSilentlyTruncated() {
        rejects(() -> BilingualTextEncoder.encode(null));
        char[] large = new char[2049]; Arrays.fill(large, 'x');
        rejects(() -> BilingualTextEncoder.encode(new String(large)));
        // NFKC can expand a short input beyond its original UTF-16 length.
        char[] expansion = new char[1000]; Arrays.fill(expansion, '\ufdfa');
        rejects(() -> BilingualTextEncoder.encode(new String(expansion)));
    }

    private static void rejects(Runnable work) {
        try { work.run(); } catch (IllegalArgumentException expected) { return; }
        fail("Expected contract rejection");
    }
}
