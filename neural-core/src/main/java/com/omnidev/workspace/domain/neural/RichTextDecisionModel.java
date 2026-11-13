package com.omnidev.workspace.domain.neural;

/** Experimental six-class proposals; a reject label never becomes an executable choice. */
public final class RichTextDecisionModel {
    private static final String[] LABELS = {"repository", "build", "device", "knowledge", "chat", "__reject__"};
    private final RichTextEncoder encoder;
    private final TinyDecisionModel decision;

    public RichTextDecisionModel(RichTextEncoder encoder, TinyNeuralRuntime network, double temperature,
                                 String encoderVersion, String vocabularyFingerprint) {
        if (encoder == null || network == null || !RichTextEncoder.VERSION.equals(encoderVersion) ||
                !encoder.fingerprint().equals(vocabularyFingerprint) || network.inputSize() != RichTextEncoder.DIMENSIONS ||
                network.outputSize() != LABELS.length || network.parameterCount() > 20_000) throw new IllegalArgumentException("Invalid rich decision schema");
        this.encoder = encoder; this.decision = new TinyDecisionModel(network, LABELS, temperature);
    }
    public TinyDecisionModel.Decision classify(String text, double minimumProbability, double minimumMargin) {
        float[] features = encoder.encode(text);
        TinyDecisionModel.Decision result = decision.decide(features, minimumProbability, minimumMargin);
        boolean nonzero = false; for (float feature : features) if (feature != 0) { nonzero = true; break; }
        return !nonzero || "__reject__".equals(result.choice) ? result.asAbstention() : result;
    }
    public static String[] labels() { return LABELS.clone(); }
}
