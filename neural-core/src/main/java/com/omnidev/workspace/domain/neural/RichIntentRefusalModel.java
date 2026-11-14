package com.omnidev.workspace.domain.neural;

/** Shared lexical representation and fused domain-presence scores; refusal only. */
public final class RichIntentRefusalModel {
    private static final String[] LABELS = MultiIntentRefusalModel.labels();
    private final GatedTextIntentModel prior;
    private final RichTextEncoder encoder;
    private final TinyNeuralRuntime presence;
    private final double threshold;
    private final boolean requireMatch;
    private final TinyNeuralRuntime refusal;
    private final double refusalCutoff;

    public RichIntentRefusalModel(GatedTextIntentModel prior, RichTextEncoder encoder, TinyNeuralRuntime presence,
                                 double threshold, boolean requireMatch, String version, String expectedVocabularyHash) {
        this(prior, encoder, presence, threshold, requireMatch, version, expectedVocabularyHash, null, 1);
    }
    /** Optional calibrated sigmoid head shares the existing representation and can only refuse. */
    public RichIntentRefusalModel(GatedTextIntentModel prior, RichTextEncoder encoder, TinyNeuralRuntime presence,
                                 double threshold, boolean requireMatch, String version, String expectedVocabularyHash,
                                 TinyNeuralRuntime refusal, double refusalCutoff) {
        if (prior == null || encoder == null || presence == null || !RichTextEncoder.VERSION.equals(version) ||
                presence.inputSize() != RichTextEncoder.DIMENSIONS || presence.outputSize() != 5 || presence.parameterCount() > 6000 ||
                !encoder.fingerprint().equals(expectedVocabularyHash) || !Double.isFinite(threshold) || threshold < 0 || threshold > 1) throw new IllegalArgumentException("Invalid fused presence contract");
        this.prior = prior; this.encoder = encoder; this.presence = presence;
        this.threshold = threshold; this.requireMatch = requireMatch;
        if (!Double.isFinite(refusalCutoff) || refusalCutoff < 0 || refusalCutoff > 1 || refusal != null &&
                (refusal.inputSize() != RichTextEncoder.DIMENSIONS || refusal.outputSize() != 1 || refusal.parameterCount() > 1100)) {
            throw new IllegalArgumentException("Invalid optional rich refusal head");
        }
        this.refusal = refusal; this.refusalCutoff = refusalCutoff;
    }
    public TinyDecisionModel.Decision classify(String text, double minimumProbability, double minimumMargin) {
        String normalized = BilingualTextEncoder.normalize(text);
        float[] base = BilingualTextEncoder.encodeNormalized(normalized);
        TinyDecisionModel.Decision original = prior.classifyFeatures(base, minimumProbability, minimumMargin);
        if (original.status == TinyDecisionModel.Status.ABSTAIN) return original;
        float[] features = encoder.extendNormalized(normalized, base);
        return classifyFeatures(original, features);
    }
    private TinyDecisionModel.Decision classifyFeatures(TinyDecisionModel.Decision original, float[] features) {
        TinyDecisionModel.Decision decision = refine(original, presence.infer(features), threshold, requireMatch);
        if (decision.status == TinyDecisionModel.Status.ABSTAIN || refusal == null) return decision;
        float score = refusal.infer(features)[0];
        if (!Float.isFinite(score) || score < 0 || score > 1) throw new IllegalArgumentException("Refusal score outside probability range");
        return score >= refusalCutoff ? decision.asAbstention() : decision;
    }
    static final class Prepared {
        final TinyDecisionModel.Decision decision; final float[] features;
        Prepared(TinyDecisionModel.Decision decision,float[] features){this.decision=decision;this.features=features;}
    }
    Prepared prepare(String text,double probability,double margin) {
        String normalized=BilingualTextEncoder.normalize(text);float[] base=BilingualTextEncoder.encodeNormalized(normalized);
        TinyDecisionModel.Decision original=prior.classifyFeatures(base,probability,margin);
        if(original.status==TinyDecisionModel.Status.ABSTAIN)return new Prepared(original,null);
        float[] features=encoder.extendNormalized(normalized,base);return new Prepared(classifyFeatures(original,features),features);
    }
    String featureFingerprint(){return encoder.fingerprint();}
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
    // Optional proposal adapters share these immutable weights; this score never grants execution authority.
    float[] domainSupport(float[] features) { return presence.infer(features); }
}
