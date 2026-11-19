package com.omnidev.workspace.domain.neural;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;

/** Local INT8 inference. The separate heads return scores or reference metadata. */
public final class OmniSpecialistModels {
    private static final int SLOTS = 4096;
    private static final int DIMENSIONS = 16;
    private static final int FEATURES = SLOTS + DIMENSIONS;
    private static final int TEXT_LIMIT = 4096;

    private final byte[] encoder = new byte[SLOTS * DIMENSIONS];
    private final float[] embeddingScale;
    private final Head catalog;
    private final Head operations;
    private final Head explanations;
    private final float operationMargin;
    private final String[] operationUsage;
    private final String[] operationRequired;
    private final String[] summaryArabic;
    private final String[] summaryEnglish;
    private final Pattern[] exactNames;

    public static OmniSpecialistModels read(InputStream stream) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(stream)))) {
            return new OmniSpecialistModels(input);
        }
    }

    private OmniSpecialistModels(DataInputStream input) throws IOException {
        if (input.readInt() != 0x4F4D5331) throw new IOException("Unsupported specialist model format");
        input.readFully(encoder);
        embeddingScale = floats(input, DIMENSIONS);
        catalog = new Head(input, 170);
        operations = new Head(input, 32);
        operationMargin = finite(input.readFloat());
        operationUsage = new String[operations.labels.length];
        operationRequired = new String[operations.labels.length];
        for (int i = 0; i < operationUsage.length; i++) {
            operationUsage[i] = string(input);
            operationRequired[i] = string(input);
        }
        explanations = new Head(input, 16);
        summaryArabic = new String[explanations.labels.length];
        summaryEnglish = new String[explanations.labels.length];
        exactNames = new Pattern[explanations.labels.length];
        for (int i = 0; i < summaryArabic.length; i++) {
            summaryArabic[i] = string(input);
            summaryEnglish[i] = string(input);
            exactNames[i] = Pattern.compile("(?<![\\p{L}\\p{N}_])"
                    + Pattern.quote(explanations.labels[i]) + "(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE);
        }
        if (input.read() != -1) throw new IOException("Unexpected specialist model data");
    }

    private static float finite(float value) throws IOException {
        if (!Float.isFinite(value)) throw new IOException("Invalid model weight");
        return value;
    }

    private static float[] floats(DataInputStream input, int count) throws IOException {
        float[] values = new float[count];
        for (int i = 0; i < count; i++) values[i] = finite(input.readFloat());
        return values;
    }

    private static String string(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        if (count > 2048) throw new IOException("Specialist model string too long");
        byte[] bytes = new byte[count];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static final class Head {
        final String[] labels;
        final byte[] matrix;
        final float[] scale;
        final float[] bias;

        Head(DataInputStream input, int limit) throws IOException {
            int count = input.readUnsignedShort();
            if (count < 2 || count > limit) throw new IOException("Invalid specialist label count");
            labels = new String[count];
            for (int i = 0; i < count; i++) labels[i] = string(input);
            matrix = new byte[FEATURES * count];
            input.readFully(matrix);
            scale = floats(input, count);
            bias = floats(input, count);
        }

        float[] score(float[] features) {
            float[] scores = new float[labels.length];
            for (int feature = 0; feature < features.length; feature++) {
                float value = features[feature];
                if (value == 0f) continue;
                int offset = feature * labels.length;
                for (int label = 0; label < labels.length; label++) scores[label] += matrix[offset + label] * value;
            }
            for (int i = 0; i < scores.length; i++) scores[i] = scores[i] * scale[i] + bias[i];
            return scores;
        }

        int best(float[] scores) {
            int best = 0;
            for (int i = 1; i < scores.length; i++) if (scores[i] > scores[best]) best = i;
            return best;
        }

        float margin(float[] scores, int best) {
            float second = -Float.MAX_VALUE;
            for (int i = 0; i < scores.length; i++) if (i != best) second = Math.max(second, scores[i]);
            return scores[best] - second;
        }
    }

    private static boolean isWord(int cp) {
        int type = Character.getType(cp);
        return cp == '_' || Character.isLetterOrDigit(cp)
                || type == Character.LETTER_NUMBER || type == Character.OTHER_NUMBER;
    }

    private static double[] textFeatures(String text) {
        int length = text.codePointCount(0, text.length());
        if (length > TEXT_LIMIT) text = text.substring(0, text.offsetByCodePoints(0, TEXT_LIMIT));
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
                .replace('ى', 'ي').replace('ة', 'ه');
        Map<String, Double> terms = new TreeMap<>();
        StringBuilder word = new StringBuilder();
        String previous = "";
        for (int index = 0; index <= normalized.length();) {
            int cp = index == normalized.length() ? ' ' : normalized.codePointAt(index);
            index += Character.charCount(cp);
            if (cp == 'ـ' || Character.getType(cp) == Character.NON_SPACING_MARK) continue;
            if (isWord(cp)) {
                word.appendCodePoint(cp);
            } else if (word.length() > 0) {
                String token = word.toString();
                terms.put("w:" + token, 2.0);
                if (!previous.isEmpty()) terms.put("p:" + previous + " " + token, 0.7);
                previous = token;
                int[] padded = ("^" + token + "$").codePoints().toArray();
                for (int i = 0; i + 2 < padded.length; i++) terms.put("c:" + new String(padded, i, 3), 1.0);
                word.setLength(0);
            }
        }
        CRC32 hash = new CRC32();
        double[] dense = new double[SLOTS];
        for (Map.Entry<String, Double> term : terms.entrySet()) {
            hash.reset();
            hash.update(term.getKey().getBytes(StandardCharsets.UTF_8));
            long value = hash.getValue();
            dense[(int) (value % SLOTS)] += value < 0x80000000L ? term.getValue() : -term.getValue();
        }
        double norm = 0.0;
        for (double value : dense) norm += value * value;
        norm = Math.max(1.0, Math.sqrt(norm));
        for (int i = 0; i < dense.length; i++) dense[i] /= norm;
        return dense;
    }

    private float[] embedding(double[] dense) {
        double[] sums = new double[DIMENSIONS];
        for (int i = 0; i < dense.length; i++) {
            if (dense[i] == 0.0) continue;
            int offset = i * DIMENSIONS;
            for (int j = 0; j < DIMENSIONS; j++) sums[j] += dense[i] * encoder[offset + j];
        }
        double norm = 0.0;
        for (int i = 0; i < sums.length; i++) {
            sums[i] *= embeddingScale[i];
            norm += sums[i] * sums[i];
        }
        norm = Math.max(1e-9, Math.sqrt(norm));
        float[] result = new float[DIMENSIONS];
        for (int i = 0; i < sums.length; i++) result[i] = (float) (sums[i] / norm);
        return result;
    }

    public float[] embed(String text) {
        return embedding(textFeatures(text));
    }

    private float[] vector(String text) {
        double[] dense = textFeatures(text);
        float[] result = new float[FEATURES];
        for (int i = 0; i < dense.length; i++) result[i] = (float) dense[i];
        float[] encoded = embedding(dense);
        for (int i = 0; i < encoded.length; i++) result[SLOTS + i] = encoded[i] * .5f;
        return result;
    }

    public List<String> rankTools(String text, Set<String> available) {
        if (text.trim().isEmpty() || available.isEmpty()) return Collections.emptyList();
        float[] scores = catalog.score(vector(text));
        float minimum = 0.06f;
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < catalog.labels.length; i++) {
            String label = catalog.labels[i];
            if (label.equals("NO_TOOL")) minimum = Math.max(minimum, scores[i]);
            else if (available.contains(label)) candidates.add(i);
        }
        candidates.sort((left, right) -> Float.compare(scores[right], scores[left]));
        List<String> ranked = new ArrayList<>(3);
        for (int i : candidates) {
            if (scores[i] <= minimum || ranked.size() == 3) break;
            ranked.add(catalog.labels[i]);
        }
        return ranked;
    }

    public static final class Operation {
        public final String key;
        public final String usage;
        public final String required;

        Operation(String key, String usage, String required) {
            this.key = key;
            this.usage = usage;
            this.required = required;
        }
    }

    public Operation adviseOperation(String text) {
        if (text.trim().isEmpty()) return null;
        float[] scores = operations.score(vector(text));
        int best = operations.best(scores);
        String key = operations.labels[best];
        if (key.equals("NO_TOOL") || operations.margin(scores, best) <= operationMargin) return null;
        return new Operation(key, operationUsage[best], operationRequired[best]);
    }

    public List<String> explain(String text) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < exactNames.length; i++) {
            if (!summaryEnglish[i].isEmpty() && exactNames[i].matcher(text).find()) result.add(summary(i));
        }
        if (!result.isEmpty()) return result;
        float[] scores = explanations.score(vector(text));
        int best = explanations.best(scores);
        if (!summaryEnglish[best].isEmpty() && explanations.margin(scores, best) >= .45f) {
            result.add(summary(best));
        }
        return result;
    }

    private String summary(int index) {
        return explanations.labels[index] + ": " + summaryEnglish[index] + " / " + summaryArabic[index];
    }
}
