package com.omnidev.workspace.domain.neural;

import java.util.Arrays;

/** Experimental independent domain-presence heads. They can only veto an existing proposal. */
public final class MultiIntentRefusalModel {
    private static final String[] LABELS = {"repository", "build", "device", "knowledge", "chat"};
    private final GatedTextIntentModel prior;
    private final TinyDecisionModel[] heads;
    private final double threshold;
    private final boolean requireUniqueMatch;

    public MultiIntentRefusalModel(GatedTextIntentModel prior, TinyNeuralRuntime[] networks, double[] temperatures,
                                   double threshold, boolean requireUniqueMatch, String encoderVersion) {
        if (prior == null || networks == null || networks.length != 5 || temperatures == null || temperatures.length != 5 ||
                !Double.isFinite(threshold) || threshold < 0 || threshold > 1 || !BilingualTextEncoder.VERSION.equals(encoderVersion)) {
            throw new IllegalArgumentException("Invalid multi-intent contract");
        }
        heads = new TinyDecisionModel[5];
        int parameters = 0;
        for (int i = 0; i < heads.length; i++) {
            if (networks[i] == null || networks[i].inputSize() != BilingualTextEncoder.DIMENSIONS || networks[i].outputSize() != 2) {
                throw new IllegalArgumentException("Invalid domain-presence head");
            }
            heads[i] = new TinyDecisionModel(networks[i], new String[] {"absent", "present"}, temperatures[i]);
            parameters += networks[i].parameterCount();
        }
        if (parameters > 10_000) throw new IllegalArgumentException("Presence heads exceed parameter budget");
        this.prior = prior; this.threshold = threshold; this.requireUniqueMatch = requireUniqueMatch;
    }
    public static String[] labels() { return LABELS.clone(); }

    public TinyDecisionModel.Decision classify(String text, double minimumProbability, double minimumMargin) {
        float[] features = BilingualTextEncoder.encode(text);
        TinyDecisionModel.Decision original = prior.classifyFeatures(features, minimumProbability, minimumMargin);
        if (original.status == TinyDecisionModel.Status.ABSTAIN) return original;
        if (!Arrays.asList(LABELS).contains(original.choice)) return original.asAbstention();
        int count = 0, selected = -1;
        for (int i = 0; i < heads.length; i++) if (heads[i].decide(features, 0, 0).probabilitiesCopy()[1] >= threshold) {
            count++; selected = i;
            if (count > 1) return original.asAbstention();
        }
        if (requireUniqueMatch && (count != 1 || !LABELS[selected].equals(original.choice))) return original.asAbstention();
        return original;
    }
}
