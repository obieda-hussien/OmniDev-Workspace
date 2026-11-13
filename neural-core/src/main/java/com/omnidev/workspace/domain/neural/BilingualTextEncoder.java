package com.omnidev.workspace.domain.neural;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded lexical features, not a pretrained semantic language encoder. */
public final class BilingualTextEncoder {
    public static final String VERSION = "ar-en-word-char-fnv256-v1";
    public static final int DIMENSIONS = 256;
    public static final int MAX_CHARS = 2048;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+");

    private BilingualTextEncoder() { }

    public static String normalize(String text) {
        if (text == null || text.length() > MAX_CHARS) throw new IllegalArgumentException("Invalid bounded text");
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_CHARS) throw new IllegalArgumentException("Expanded text exceeds limit");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '\u0640' || c >= '\u064b' && c <= '\u065f' || c == '\u0670') continue;
            if (c == '\u0622' || c == '\u0623' || c == '\u0625') c = '\u0627';
            if (c == '\u0649') c = '\u064a';
            out.append(c);
        }
        return out.toString();
    }

    public static float[] encode(String text) {
        return encodeNormalized(normalize(text));
    }

    static float[] encodeNormalized(String normalized) {
        if (normalized == null || normalized.length() > MAX_CHARS) throw new IllegalArgumentException("Invalid bounded normalized text");
        float[] features = new float[DIMENSIONS];
        Matcher words = WORD.matcher(normalized);
        while (words.find()) {
            String word = words.group();
            add(features, "w:" + word);
            String bounded = "^" + word + "$";
            for (int length = 3; length <= 5; length++) {
                for (int start = 0; start + length <= bounded.length(); start++) {
                    add(features, "c:" + bounded.substring(start, start + length));
                }
            }
        }
        double norm = 0;
        for (int i = 0; i < features.length; i++) {
            features[i] = (float) Math.copySign(Math.log1p(Math.abs(features[i])), features[i]);
            norm += features[i] * features[i];
        }
        if (norm > 0) {
            double divisor = Math.sqrt(norm);
            for (int i = 0; i < features.length; i++) features[i] /= divisor;
        }
        return features;
    }

    private static void add(float[] features, String token) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < token.length(); i++) hash = (hash ^ token.charAt(i)) * 0x01000193;
        features[hash & (DIMENSIONS - 1)] += (hash & 0x100) == 0 ? 1 : -1;
    }
}
