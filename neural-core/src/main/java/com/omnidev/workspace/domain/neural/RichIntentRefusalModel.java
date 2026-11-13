package com.omnidev.workspace.domain.neural;

/** Shared lexical representation and fused domain-presence scores; refusal only. */
public final class RichIntentRefusalModel {
    private static final String[] LABELS = MultiIntentRefusalModel.labels();
    private final GatedTextIntentModel prior;
    private final RichTextEncoder encoder;
    private final TinyNeuralRuntime presence;
    private final double threshold;
    private final boolean requireMatch;

    public RichIntentRefusalModel(GatedTextIntentModel prior, RichTextEncoder encoder, TinyNeuralRuntime presence,
                                 double threshold, boolean requireMatch, String version, String expectedVocabularyHash) {
        if (prior == null || encoder == null || presence == null || !RichTextEncoder.VERSION.equals(version) ||
                presence.inputSize() != RichTextEncoder.DIMENSIONS || presence.outputSize() != 5 || presence.parameterCount() > 6000 ||
                !encoder.fingerprint().equals(expectedVocabularyHash) || !Double.isFinite(threshold) || threshold < 0 || threshold > 1) throw new IllegalArgumentException("Invalid fused presence contract");
        this.prior = prior; this.encoder = encoder; this.presence = presence;
        this.threshold = threshold; this.requireMatch = requireMatch;
    }
    public TinyDecisionModel.Decision classify(String text, double minimumProbability, double minimumMargin) {
        String normalized = BilingualTextEncoder.normalize(text);
        float[] base = BilingualTextEncoder.encodeNormalized(normalized);
        TinyDecisionModel.Decision original = prior.classifyFeatures(base, minimumProbability, minimumMargin);
        if (original.status == TinyDecisionModel.Status.ABSTAIN) return original;
        float[] scores = presence.infer(encoder.extendNormalized(normalized, base));
        return refine(original, scores, threshold, requireMatch);
    }
    static TinyDecisionModel.Decision refine(TinyDecisionModel.Decision original, float[] scores, double threshold, boolean requireMatch) {
        if (original.status == TinyDecisionModel.Status.ABSTAIN) return original;
        if (scores == null || scores.length != 5) throw new IllegalArgumentException("Invalid presence score schema");
        boolean owned = false; for (String label : LABELS) if (label.equals(original.choice)) owned = true;
        if (!owned) return original.asAbstention();
        int count = 0, selected = -1;
        for (int i = 0; i < scores.length; i++) {
            if (!Float.isFinite(scores[i]) || scores[i] < 0 || scores[i] > 1) throw new IllegalArgumentException("Presence score outside probability range");
            if (scores[i] >= threshold) { count++; selected = i; }
        }
        if (count > 1 || requireMatch && (count != 1 || !LABELS[selected].equals(original.choice))) return original.asAbstention();
        return original;
    }
}
