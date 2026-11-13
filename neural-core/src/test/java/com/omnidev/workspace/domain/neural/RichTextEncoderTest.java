package com.omnidev.workspace.domain.neural;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class RichTextEncoderTest {
    private static RichTextEncoder encoder() { return new RichTextEncoder(new String[] {"b:read|source", "w:read", "w:source"}, new float[] {2, 1, 1}, RichTextEncoder.VERSION); }
    @Test public void lexicalOrderAddsEvidenceWithoutChangingHashBackoff() {
        RichTextEncoder e = encoder(); float[] a = e.encode("read source"), b = e.encode("source read");
        assertTrue(a[256] > 0); assertEquals(0, b[256], 0);
        float[] base = BilingualTextEncoder.encode("read source");
        for (int i = 0; i < 256; i++) assertEquals((float) (base[i] / Math.sqrt(2)), a[i], 0);
        double norm = 0; for (float value : a) { assertTrue(Float.isFinite(value)); assertTrue(Math.abs(value) <= 1); norm += (double) value * value; }
        assertEquals(1, norm, 1e-6);
    }
    @Test public void callerMutationCannotAlterVocabularyOrFingerprint() {
        String[] words = {"w:source"}; float[] idf = {2}; RichTextEncoder e = new RichTextEncoder(words, idf, RichTextEncoder.VERSION);
        String hash = e.fingerprint(); float[] expected = e.encode("source"); words[0] = null; idf[0] = Float.NaN;
        e.vocabularyCopy()[0] = "w:other"; e.idfCopy()[0] = 12;
        assertEquals(hash, e.fingerprint()); assertArrayEquals(expected, e.encode("source"), 0);
        assertNotEquals(hash, new RichTextEncoder(new String[] {"w:other"}, new float[] {2}, RichTextEncoder.VERSION).fingerprint());
    }
    @Test public void absentWordsAndLargeInputsStayBounded() {
        assertArrayEquals(new float[1024], encoder().encode("!!!"), 0);
        char[] longWord = new char[2048]; Arrays.fill(longWord, 'x'); assertEquals(1024, encoder().encode(new String(longWord)).length);
        rejects(() -> encoder().encode(null)); rejects(() -> encoder().encode(new String(longWord) + "x"));
    }
    @Test public void invalidMetadataFailsBeforeInference() {
        rejects(() -> new RichTextEncoder(new String[] {"w:z", "w:a"}, new float[] {1, 1}, RichTextEncoder.VERSION));
        rejects(() -> new RichTextEncoder(new String[] {"w:a", "w:a"}, new float[] {1, 1}, RichTextEncoder.VERSION));
        rejects(() -> new RichTextEncoder(new String[] {"w:bad space"}, new float[] {1}, RichTextEncoder.VERSION));
        rejects(() -> new RichTextEncoder(new String[] {"w:a"}, new float[] {Float.NaN}, RichTextEncoder.VERSION));
        rejects(() -> new RichTextEncoder(new String[] {"w:a"}, new float[] {1}, "wrong"));
    }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } fail("Expected metadata rejection"); }
}
