package com.omnidev.workspace.domain.engine;

import com.omnidev.workspace.domain.neural.BilingualTextEncoder;
import com.omnidev.workspace.domain.neural.InspectionTopicHead;
import com.omnidev.workspace.domain.neural.RichTextDecisionModel;
import com.omnidev.workspace.domain.neural.RichTextEncoder;
import com.omnidev.workspace.domain.neural.TinyDecisionModel;
import com.omnidev.workspace.domain.neural.TinyNeuralRuntime;
import com.omnidev.workspace.domain.neural.bundled.TrainedPublicDecision;
import com.omnidev.workspace.domain.neural.bundled.TrainedPublicRefusal;
import com.omnidev.workspace.domain.neural.bundled.TrainedRichVocabulary;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BundledDecisionModels {
    private BundledDecisionModels() {}

    private static final RichTextEncoder ENCODER = TrainedRichVocabulary.encoder();
    private static final TinyNeuralRuntime REFUSAL = TrainedPublicRefusal.model();
    private static final TinyDecisionModel DOMAIN = new TinyDecisionModel(
            TrainedPublicDecision.model(), RichTextDecisionModel.labels(), TrainedPublicDecision.TEMPERATURE);

    static {
        if (!ENCODER.fingerprint().equals(TrainedPublicDecision.VOCABULARY_SHA256)
                || !ENCODER.fingerprint().equals(TrainedPublicRefusal.VOCABULARY_SHA256)) {
            throw new IllegalStateException("Bundled decision vocabulary mismatch");
        }
    }

    static final int MAX_CONTEXT_CHARS = 8192;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    static boolean supportsInspection(String input) {
        if (input == null || input.isEmpty() || input.length() > MAX_CONTEXT_CHARS) return false;
        String text = input.trim();
        if (text.isEmpty()) return false;
        if (text.length() <= BilingualTextEncoder.MAX_CHARS) return supportsShortInspection(text);
        if (!topicMatches(text)) return false;
        // Check the fitted short heads on whole-word windows as well as the full topic vector.
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + BilingualTextEncoder.MAX_CHARS);
            if (end < text.length()) {
                while (end > start && !Character.isWhitespace(text.charAt(end))) end--;
                if (end == start) {
                    while (start < text.length() && !Character.isWhitespace(text.charAt(start))) start++;
                    while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
                    continue;
                }
            }
            if (supportsShortInspection(text.substring(start, end))) return true;
            start = end;
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        }
        return false;
    }

    private static final Pattern DIACRITICS = Pattern.compile("[ـً-ٰٟ]");
    private static boolean topicMatches(String text) {
        Set<String> seen = new HashSet<>();
        int score = InspectionTopicHead.BIAS;
        String normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT);
        if (normalized.length() > MAX_CONTEXT_CHARS) return false;
        normalized = DIACRITICS.matcher(normalized).replaceAll("")
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي');
        Matcher words = WORD.matcher(normalized);
        while (words.find()) {
            String term = words.group();
            int weight = InspectionTopicHead.weight(term);
            if (weight != 0 && seen.add(term)) score += weight;
        }
        return score > 0;
    }

    private static boolean supportsShortInspection(String input) {
        if (input == null || input.trim().isEmpty() || input.length() > BilingualTextEncoder.MAX_CHARS) return false;
        float[] features;
        try {
            features = ENCODER.encode(input);
        } catch (IllegalArgumentException expandedBeyondLimit) {
            return false;
        }
        if (REFUSAL.infer(features)[0] >= TrainedPublicRefusal.CUTOFF) return false;
        TinyDecisionModel.Decision decision = DOMAIN.decide(features,
                TrainedPublicDecision.MINIMUM_PROBABILITY, TrainedPublicDecision.MINIMUM_MARGIN);
        return "repository".equals(decision.choice) || "build".equals(decision.choice)
                || "device".equals(decision.choice) || (decision.choice == null && topicMatches(input));
    }
}
