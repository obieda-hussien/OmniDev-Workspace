package com.omnidev.workspace.domain.neural;

import java.util.*;
import java.util.regex.*;
import java.nio.charset.StandardCharsets;
import java.security.*;

/** Bounded fitted lexical vocabulary plus unchanged hash backoff, not a pretrained semantic encoder. */
public final class RichTextEncoder {
    public static final String VERSION = "ar-en-word-bigram-idf768-hash256-v1";
    public static final int DIMENSIONS = 1024, MAX_VOCABULARY = 768;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final Pattern TERM = Pattern.compile("(?:w:[\\p{L}\\p{N}_]{1,64}|b:[\\p{L}\\p{N}_]{1,64}\\|[\\p{L}\\p{N}_]{1,64})");
    private final String[] vocabulary;
    private final float[] idf;
    private final Map<String, Integer> indices;
    private final String fingerprint;

    public RichTextEncoder(String[] vocabulary, float[] idf, String version) {
        if (!VERSION.equals(version) || vocabulary == null || idf == null || vocabulary.length != idf.length ||
                vocabulary.length == 0 || vocabulary.length > MAX_VOCABULARY) throw new IllegalArgumentException("Invalid rich vocabulary contract");
        this.vocabulary = vocabulary.clone(); this.idf = idf.clone();
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < this.vocabulary.length; i++) {
            String term = this.vocabulary[i];
            if (term == null || !TERM.matcher(term).matches() || i > 0 && this.vocabulary[i - 1].compareTo(term) >= 0 ||
                    !Float.isFinite(this.idf[i]) || this.idf[i] < 1 || this.idf[i] > 12) throw new IllegalArgumentException("Invalid vocabulary term/order/IDF");
            map.put(term, i);
        }
        indices = Collections.unmodifiableMap(map);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); digest.update(VERSION.getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < this.vocabulary.length; i++) {
                digest.update((byte) 0); digest.update(this.vocabulary[i].getBytes(StandardCharsets.UTF_8));
                int bits = Float.floatToIntBits(this.idf[i]);
                digest.update((byte) (bits >>> 24)); digest.update((byte) (bits >>> 16)); digest.update((byte) (bits >>> 8)); digest.update((byte) bits);
            }
            StringBuilder hash = new StringBuilder(); for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            fingerprint = hash.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    public String[] vocabularyCopy() { return vocabulary.clone(); }
    public float[] idfCopy() { return idf.clone(); }
    public int vocabularySize() { return vocabulary.length; }
    public String fingerprint() { return fingerprint; }

    public float[] encode(String text) {
        String normalized = BilingualTextEncoder.normalize(text);
        return extendNormalized(normalized, BilingualTextEncoder.encodeNormalized(normalized));
    }

    float[] extendNormalized(String normalized, float[] base) {
        if (base == null || base.length != 256) throw new IllegalArgumentException("Invalid base feature schema");
        float[] features = new float[DIMENSIONS];
        for (int i = 0; i < 256; i++) {
            if (!Float.isFinite(base[i]) || Math.abs(base[i]) > 1) throw new IllegalArgumentException("Invalid base feature");
            features[i] = (float) (base[i] / Math.sqrt(2));
        }
        for (String term : termsNormalized(normalized)) {
            Integer position = indices.get(term);
            if (position != null) features[256 + position]++;
        }
        double norm = 0;
        for (int i = 0; i < idf.length; i++) {
            int index = 256 + i;
            // Most vocabulary slots are absent; log1p(0) cannot affect the feature or norm.
            if (features[index] == 0) continue;
            features[index] = (float) Math.log1p(features[index]) * idf[i];
            norm += (double) features[index] * features[index];
        }
        if (norm > 0) {
            double divisor = Math.sqrt(2 * norm);
            for (int i = 256; i < features.length; i++) if (features[i] != 0) features[i] /= divisor;
        }
        return features;
    }
    // Shared by the desktop fitter so tokenization cannot drift from inference.
    static List<String> terms(String text) {
        return termsNormalized(BilingualTextEncoder.normalize(text));
    }
    static List<String> termsNormalized(String normalized) {
        if (normalized == null || normalized.length() > 2048) throw new IllegalArgumentException("Invalid normalized vocabulary input");
        Matcher matcher = WORD.matcher(normalized);
        List<String> result = new ArrayList<>(); String previous = null; int count = 0;
        while (matcher.find() && count++ < 256) {
            String word = matcher.group();
            if (word.length() > 64) { previous = null; continue; }
            result.add("w:" + word);
            if (previous != null) result.add("b:" + previous + "|" + word);
            previous = word;
        }
        return result;
    }
}
