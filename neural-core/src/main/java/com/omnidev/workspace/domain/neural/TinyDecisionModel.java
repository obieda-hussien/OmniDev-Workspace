package com.omnidev.workspace.domain.neural;

import java.util.HashSet;
import java.util.Set;

/** Typed, bounded choices over caller-declared labels. Predictions are never authorization. */
public final class TinyDecisionModel {
    public enum Status { CHOICE, ABSTAIN }

    public static final class Decision {
        public final Status status;
        public final String choice;
        public final double probability;
        public final double margin;
        private final double[] probabilities;

        private Decision(Status status, String choice, double[] probabilities, double probability, double margin) {
            this.status = status;
            this.choice = choice;
            this.probabilities = probabilities.clone();
            this.probability = probability;
            this.margin = margin;
        }

        public double[] probabilitiesCopy() { return probabilities.clone(); }
        /** A caller may refuse a proposal; this never upgrades abstention into a choice. */
        public Decision asAbstention() {
            return status == Status.ABSTAIN ? this : new Decision(Status.ABSTAIN, null, probabilities, probability, margin);
        }
    }

    private final TinyNeuralRuntime network;
    private final String[] labels;
    private final double temperature;

    public TinyDecisionModel(TinyNeuralRuntime network, String[] labels, double temperature) {
        if (network == null || labels == null || labels.length < 2 || labels.length > 32 ||
                network.outputSize() != labels.length || !Double.isFinite(temperature) || temperature <= 0) {
            throw new IllegalArgumentException("Invalid decision contract");
        }
        Set<String> unique = new HashSet<>();
        for (String label : labels) {
            if (label == null || label.trim().isEmpty() || label.length() > 160 || !unique.add(label)) {
                throw new IllegalArgumentException("Invalid or duplicate decision label");
            }
        }
        this.network = network;
        this.labels = labels.clone();
        this.temperature = temperature;
    }

    public Decision decide(float[] features, double minimumProbability, double minimumMargin) {
        if (!unit(minimumProbability) || !unit(minimumMargin)) {
            throw new IllegalArgumentException("Invalid abstention thresholds");
        }
        float[] logits = network.infer(features);
        double max = -Double.MAX_VALUE;
        for (float logit : logits) max = Math.max(max, logit);
        double[] probabilities = new double[logits.length];
        double sum = 0;
        for (int i = 0; i < logits.length; i++) {
            probabilities[i] = Math.exp(((double) logits[i] - max) / temperature);
            sum += probabilities[i];
        }
        int best = 0;
        double second = 0;
        for (int i = 0; i < probabilities.length; i++) {
            probabilities[i] /= sum;
            if (probabilities[i] > probabilities[best]) best = i;
        }
        for (int i = 0; i < probabilities.length; i++) if (i != best) second = Math.max(second, probabilities[i]);
        double margin = probabilities[best] - second;
        boolean abstain = probabilities[best] < minimumProbability || margin <= minimumMargin;
        return new Decision(abstain ? Status.ABSTAIN : Status.CHOICE, abstain ? null : labels[best],
                probabilities, probabilities[best], margin);
    }

    private static boolean unit(double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }
}
