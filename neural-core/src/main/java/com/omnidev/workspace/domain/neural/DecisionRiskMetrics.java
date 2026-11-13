package com.omnidev.workspace.domain.neural;

/** Bounded evaluation counters. Expected label -1 means the declared contract requires abstention. */
public final class DecisionRiskMetrics {
    private static final int BINS = 10;
    private final int classes;
    private int total, known, correct, accepted, acceptedErrors, requiredAbstentions, refused;
    private final int[] binCount = new int[BINS], binCorrect = new int[BINS];
    private final double[] binConfidence = new double[BINS];

    public DecisionRiskMetrics(int classes) {
        if (classes < 2 || classes > 32) throw new IllegalArgumentException("Invalid class count");
        this.classes = classes;
    }

    public void add(TinyDecisionModel.Decision decision, int expectedLabel) {
        if (decision == null || expectedLabel < -1 || expectedLabel >= classes || total >= 1_000_000) {
            throw new IllegalArgumentException("Invalid bounded evaluation observation");
        }
        double[] probabilities = decision.probabilitiesCopy();
        if (probabilities.length != classes) throw new IllegalArgumentException("Probability schema mismatch");
        int best = 0;
        for (int i = 1; i < classes; i++) if (probabilities[i] > probabilities[best]) best = i;
        boolean matches = expectedLabel >= 0 && best == expectedLabel;
        total++;
        if (decision.status == TinyDecisionModel.Status.CHOICE) {
            accepted++;
            if (!matches) acceptedErrors++;
        }
        if (expectedLabel == -1) {
            requiredAbstentions++;
            if (decision.status == TinyDecisionModel.Status.ABSTAIN) refused++;
        } else {
            known++;
            if (matches) correct++;
            int bin = Math.min(BINS - 1, (int) (probabilities[best] * BINS));
            binCount[bin]++;
            if (matches) binCorrect[bin]++;
            binConfidence[bin] += probabilities[best];
        }
    }

    public int total() { return total; }
    public int accepted() { return accepted; }
    public int acceptedErrors() { return acceptedErrors; }
    public int known() { return known; }
    public int correct() { return correct; }
    public int requiredAbstentions() { return requiredAbstentions; }
    public int refused() { return refused; }
    public Double coverage() { return total == 0 ? null : (double) accepted / total; }
    public Double selectiveError() { return accepted == 0 ? null : (double) acceptedErrors / accepted; }
    /** Equal-width top-label ECE on known-label rows only; not an OOD correctness score. */
    public Double expectedCalibrationError() {
        if (known == 0) return null;
        double error = 0;
        for (int i = 0; i < BINS; i++) error += Math.abs(binConfidence[i] - binCorrect[i]);
        return error / known;
    }
}
